package forge.ai.llm;

import forge.ai.LobbyPlayerAi;
import forge.ai.llm.tools.ObserveBattlefield;
import forge.ai.llm.tools.ObserveHand;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.event.GameEventCardTapped;
import forge.game.event.GameEventGameFinished;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
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

public class CardObservationShould {
    @BeforeClass
    public void initializeLanguage() {
        Localizer.getInstance().initialize("en-US", "res/languages/");
        Lang.createInstance("en-US");
    }

    private Game game() {
        final var players = List.of(
                new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("One", null)),
                new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("Two", null)));
        return new Match(new GameRules(GameType.Constructed), players, "Card observation").createGame();
    }

    private Card card(Player owner, String name, ZoneType zone) {
        final var rules = new CardRules.Reader().readCard(List.of(
                "Name:" + name, "ManaCost:0", "Types:Creature", "PT:1/1", "Oracle:"));
        final var paper = new PaperCard(rules, "TST", CardRarity.Common);
        final Card card = new Card(owner.getGame().nextCardId(), paper, owner.getGame());
        card.setOwner(owner);
        card.setController(owner, 0);
        card.setName(name);
        owner.getZone(zone).add(card);
        return card;
    }

    private McpSyncClient connect(ForgeMcpServer server) {
        final var transport = HttpClientStreamableHttpTransport
                .builder(URI.create(server.getUrl()).resolve("/").toString()).endpoint("/mcp").build();
        final var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build();
        client.initialize();
        return client;
    }

    private CardsSnapshot read(McpSyncClient client, String tool) throws Exception {
        final var result = client.callTool(new CallToolRequest(tool, Map.of()));
        Assert.assertFalse(result.isError());
        return McpJsonDefaults.getMapper().readValue(((TextContent) result.content().get(0)).text(), CardsSnapshot.class);
    }

    @Test
    public void listOwnHandAndAllBattlefieldCardsAndTrackUpdates() throws Exception {
        final Game game = game();
        final Player one = game.getPlayers().get(0);
        final Player two = game.getPlayers().get(1);
        try (ForgeMcpServer server = ForgeMcpServer.start(one); var client = connect(server)) {
            Assert.assertTrue(read(client, ObserveHand.NAME).cards().isEmpty());
            Assert.assertTrue(read(client, ObserveBattlefield.NAME).cards().isEmpty());
            final Card first = card(one, "Lightning Bolt", ZoneType.Hand);
            final Card second = card(one, "Lightning Bolt", ZoneType.Hand);
            card(two, "Secret opponent hand", ZoneType.Hand);
            final Card ownPermanent = card(one, "Own permanent", ZoneType.Battlefield);
            final Card otherPermanent = card(two, "Other permanent", ZoneType.Battlefield);
            otherPermanent.setController(one, 1);
            otherPermanent.setTapped(true);
            game.fireEvent(new GameEventCardTapped(otherPermanent, true));

            final var hand = read(client, ObserveHand.NAME);
            Assert.assertEquals(hand.cards().stream().map(CardsSnapshot.CardInfo::id).toList(),
                    List.of(first.getId(), second.getId()));
            Assert.assertEquals(hand.cards().stream().map(CardsSnapshot.CardInfo::name).toList(),
                    List.of("Lightning Bolt", "Lightning Bolt"));
            Assert.assertEquals(hand.cards().get(0).owner(), new CardsSnapshot.PlayerInfo(one.getId(), "One"));
            Assert.assertFalse(hand.cards().get(0).faceDown());
            final var battlefield = read(client, ObserveBattlefield.NAME);
            Assert.assertEquals(battlefield.cards().stream().map(CardsSnapshot.CardInfo::id).toList(),
                    List.of(ownPermanent.getId(), otherPermanent.getId()));
            final var controlled = battlefield.cards().get(1);
            Assert.assertEquals(controlled.owner(), new CardsSnapshot.PlayerInfo(two.getId(), "Two"));
            Assert.assertEquals(controlled.controller(), new CardsSnapshot.PlayerInfo(one.getId(), "One"));
            Assert.assertTrue(controlled.tapped());

            one.getZone(ZoneType.Hand).remove(first);
            one.getZone(ZoneType.Battlefield).add(first);
            Assert.assertEquals(read(client, ObserveHand.NAME).cards().size(), 1);
            Assert.assertEquals(read(client, ObserveBattlefield.NAME).cards().size(), 3);
            Assert.assertEquals(hand.cards().size(), 2, "Previously returned snapshots stay unchanged");
            Assert.assertEquals(battlefield.cards().size(), 2);
            Assert.expectThrows(UnsupportedOperationException.class, () -> hand.cards().clear());

            otherPermanent.setTapped(false);
            server.refreshGameState(); // Also refreshed before every ACP prompt.
            Assert.assertFalse(read(client, ObserveBattlefield.NAME).cards().stream()
                    .filter(c -> c.id() == otherPermanent.getId()).findFirst().orElseThrow().tapped());
        }
    }

    @Test
    public void concealOpponentsFaceDownIdentitiesAndKeepHandsSeparate() throws Exception {
        final Game game = game();
        final Player one = game.getPlayers().get(0);
        final Player two = game.getPlayers().get(1);
        card(one, "One's hand card", ZoneType.Hand);
        card(two, "Two's hand card", ZoneType.Hand);
        card(one, "One's face-down card", ZoneType.Battlefield).turnFaceDown(true);
        card(two, "Two's face-down card", ZoneType.Battlefield).turnFaceDown(true);
        try (ForgeMcpServer first = ForgeMcpServer.start(one); var firstClient = connect(first);
             ForgeMcpServer second = ForgeMcpServer.start(two); var secondClient = connect(second)) {
            Assert.assertEquals(read(firstClient, ObserveHand.NAME).cards().get(0).name(), "One's hand card");
            Assert.assertEquals(read(secondClient, ObserveHand.NAME).cards().get(0).name(), "Two's hand card");
            final var firstView = read(firstClient, ObserveBattlefield.NAME);
            final var secondView = read(secondClient, ObserveBattlefield.NAME);
            Assert.assertEquals(firstView.cards().stream().map(CardsSnapshot.CardInfo::name).toList(),
                    List.of("One's face-down card", "Face-down card"));
            Assert.assertEquals(secondView.cards().stream().map(CardsSnapshot.CardInfo::name).toList(),
                    List.of("Face-down card", "Two's face-down card"));
            Assert.assertTrue(firstView.cards().stream().allMatch(CardsSnapshot.CardInfo::faceDown));
            Assert.assertEquals(firstView.cards().get(1).id(), secondView.cards().get(1).id());
        }
    }

    @Test
    public void validateArgumentsAndRejectUnavailableOrFinishedGames() {
        Assert.assertEquals(ObserveHand.compile(new CallToolRequest(ObserveHand.NAME, Map.of())), new ObserveHand());
        Assert.assertEquals(ObserveHand.compile(new CallToolRequest(ObserveHand.NAME, (Map<String, Object>) null)),
                new ObserveHand());
        Assert.assertEquals(ObserveBattlefield.compile(new CallToolRequest(ObserveBattlefield.NAME, Map.of())),
                new ObserveBattlefield());
        Assert.assertEquals(ObserveBattlefield.compile(new CallToolRequest(ObserveBattlefield.NAME,
                (Map<String, Object>) null)), new ObserveBattlefield());
        Assert.expectThrows(IllegalArgumentException.class, () -> ObserveHand.compile(
                new CallToolRequest(ObserveHand.NAME, Map.of("playerId", 2))));
        Assert.expectThrows(IllegalArgumentException.class, () -> ObserveBattlefield.compile(
                new CallToolRequest(ObserveBattlefield.NAME, Map.of("extra", true))));
        try (ForgeMcpServer server = ForgeMcpServer.start(); var client = connect(server)) {
            for (String tool : List.of(ObserveHand.NAME, ObserveBattlefield.NAME)) {
                Assert.assertTrue(client.callTool(new CallToolRequest(tool, Map.of())).isError());
            }
        }
        final Game game = game();
        try (ForgeMcpServer server = ForgeMcpServer.start(game.getPlayers().get(0)); var client = connect(server)) {
            for (String tool : List.of(ObserveHand.NAME, ObserveBattlefield.NAME)) {
                Assert.assertTrue(client.callTool(new CallToolRequest(tool, Map.of("extra", true))).isError());
            }
            game.fireEvent(new GameEventGameFinished());
            server.refreshGameState();
            for (String tool : List.of(ObserveHand.NAME, ObserveBattlefield.NAME)) {
                Assert.assertTrue(client.callTool(new CallToolRequest(tool, Map.of())).isError());
            }
        }
    }
}
