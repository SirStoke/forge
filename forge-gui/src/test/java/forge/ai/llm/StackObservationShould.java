package forge.ai.llm;

import forge.ai.LobbyPlayerAi;
import forge.ai.llm.tools.ObserveStack;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.event.GameEventGameFinished;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.Lang;
import forge.util.Localizer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class StackObservationShould {
    @BeforeClass
    public void initializeLanguage() {
        Localizer.getInstance().initialize("en-US", "res/languages/");
        Lang.createInstance("en-US");
    }

    private Game game() {
        final var players = List.of(
                new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("One", null)),
                new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("Two", null)));
        return new Match(new GameRules(GameType.Constructed), players, "Stack observation").createGame();
    }

    private Card card(Player owner, String name, ZoneType zone) {
        final var rules = new CardRules.Reader().readCard(List.of(
                "Name:" + name, "ManaCost:0", "Types:Creature", "PT:1/1", "Oracle:"));
        final var paper = new PaperCard(rules, "TST", CardRarity.Common);
        final Card card = new Card(owner.getGame().nextCardId(), paper, owner.getGame());
        card.setOwner(owner);
        card.setController(owner, 0);
        card.setName(name);
        (zone == ZoneType.Stack ? owner.getGame().getStackZone() : owner.getZone(zone)).add(card);
        return card;
    }

    private SpellAbility ability(Card source, String text, boolean spell) {
        final var ability = new SpellAbility.EmptySa(source) {
            @Override
            public boolean isSpell() {
                return spell;
            }

            @Override
            public boolean isAbility() {
                return !spell;
            }
        };
        ability.setCopied(true);
        ability.setStackDescription(text);
        return ability;
    }

    private McpSyncClient connect(ForgeMcpServer server) {
        final var transport = HttpClientStreamableHttpTransport
                .builder(URI.create(server.getUrl()).resolve("/").toString()).endpoint("/mcp").build();
        final var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build();
        client.initialize();
        return client;
    }

    private StackSnapshot read(McpSyncClient client) throws Exception {
        final var result = client.callTool(new CallToolRequest(ObserveStack.NAME, Map.of()));
        Assert.assertFalse(result.isError());
        return McpJsonDefaults.getMapper().readValue(((TextContent) result.content().get(0)).text(), StackSnapshot.class);
    }

    @Test
    public void observeStackOrderTargetsAndUpdatesThroughMcp() throws Exception {
        final Game game = game();
        final Player one = game.getPlayers().get(0);
        final Player two = game.getPlayers().get(1);
        try (ForgeMcpServer server = ForgeMcpServer.start(one); var client = connect(server)) {
            Assert.assertTrue(read(client).items().isEmpty());
            final var bolt = ability(card(one, "Lightning Bolt", ZoneType.Stack), "Deal 3 damage to Two", true);
            bolt.getTargets().add(two);
            game.getStack().add(bolt);
            final var boltInstance = game.getStack().peek();
            final var counter = ability(card(two, "Counterspell", ZoneType.Stack), "Counter Lightning Bolt", true);
            counter.getTargets().add(bolt);
            game.getStack().add(counter);

            final var snapshot = read(client);
            Assert.assertEquals(snapshot.items().size(), 2);
            Assert.assertEquals(snapshot.items().get(0).source().name(), "Counterspell");
            Assert.assertEquals(snapshot.items().get(0).kind(), "spell");
            Assert.assertEquals(snapshot.items().get(0).controller().name(), "Two");
            Assert.assertEquals(snapshot.items().get(0).targets(),
                    List.of(new StackSnapshot.Target("stack_item", boltInstance.getId(), "Lightning Bolt")));
            Assert.assertEquals(snapshot.items().get(1).targets(),
                    List.of(new StackSnapshot.Target("player", two.getId(), "Two")));

            game.getStack().remove(game.getStack().peek());
            Assert.assertEquals(read(client).items().size(), 1);
            Assert.assertEquals(snapshot.items().size(), 2, "The previously returned snapshot is unchanged");
            game.getStack().clear();
            Assert.assertTrue(read(client).items().isEmpty());
            game.fireEvent(new GameEventGameFinished());
            Assert.assertTrue(client.callTool(new CallToolRequest(ObserveStack.NAME, Map.of())).isError());
        }
    }

    @Test
    public void hideOpponentsFaceDownSourcesAndHiddenTargets() {
        final Game game = game();
        final Player one = game.getPlayers().get(0);
        final Player two = game.getPlayers().get(1);
        final Card hiddenSource = card(two, "Secret creature", ZoneType.Battlefield);
        hiddenSource.turnFaceDown(true);
        game.getStack().add(ability(hiddenSource, "Secret creature's ability", false));
        final Card hiddenTarget = card(two, "Secret hand card", ZoneType.Hand);
        final var visibleAbility = ability(card(one, "Public source", ZoneType.Battlefield),
                "Discard Secret hand card", false);
        visibleAbility.getTargets().add(hiddenTarget);
        game.getStack().add(visibleAbility);

        final var snapshot = StackSnapshot.capture(one);
        Assert.assertEquals(snapshot.items().get(0).targets().get(0).name(), "Hidden card");
        Assert.assertFalse(snapshot.items().get(0).description().contains("Secret hand card"));
        Assert.assertEquals(snapshot.items().get(1).source().name(), "Face-down card");
        Assert.assertTrue(snapshot.items().get(1).source().faceDown());
        Assert.assertFalse(snapshot.items().get(1).description().contains("Secret creature"));
        final var ownerView = StackSnapshot.capture(two);
        Assert.assertEquals(ownerView.items().get(0).targets().get(0).name(), "Secret hand card");
        Assert.assertEquals(ownerView.items().get(1).source().name(), "Secret creature");
        Assert.expectThrows(UnsupportedOperationException.class, () -> snapshot.items().clear());
    }

    @Test
    public void reportUnavailableGameContextAndValidateArguments() throws Exception {
        Assert.assertEquals(ObserveStack.compile(new CallToolRequest(ObserveStack.NAME, Map.of())), new ObserveStack());
        Assert.assertEquals(ObserveStack.compile(new CallToolRequest(ObserveStack.NAME, (Map<String, Object>) null)),
                new ObserveStack());
        Assert.expectThrows(IllegalArgumentException.class, () -> ObserveStack.compile(
                new CallToolRequest(ObserveStack.NAME, Map.of("extra", true))));
        try (ForgeMcpServer server = ForgeMcpServer.start(); var client = connect(server)) {
            Assert.assertTrue(client.callTool(new CallToolRequest(ObserveStack.NAME, Map.of())).isError());
            Assert.assertTrue(client.callTool(new CallToolRequest(ObserveStack.NAME, Map.of("extra", true))).isError());
        }
    }
}
