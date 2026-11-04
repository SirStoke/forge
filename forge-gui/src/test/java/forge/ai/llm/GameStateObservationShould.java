package forge.ai.llm;

import forge.ai.LobbyPlayerAi;
import forge.ai.llm.tools.ObserveGameState;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.MagicColor;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.combat.Combat;
import forge.game.event.GameEventGameFinished;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
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

public class GameStateObservationShould {
    @BeforeClass
    public void initializeLanguage() {
        Localizer.getInstance().initialize("en-US", "res/languages/");
        Lang.createInstance("en-US");
    }

    private Game game() {
        return new Match(new GameRules(GameType.Constructed), List.of(
                new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("One", null)),
                new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("Two", null))), "Observation test").createGame();
    }

    private Card card(Player player, ZoneType zone, String name) {
        final var paper = new PaperCard(CardRules.fromScript(List.of("Name:" + name, "ManaCost:0",
                "Types:Creature", "PT:1/1", "Oracle:")), "", CardRarity.Common);
        final Card card = CardFactory.getCard(paper, player, player.getGame());
        player.getZone(zone).add(card);
        return card;
    }

    private McpSyncClient connect(ForgeMcpServer server) {
        final var transport = HttpClientStreamableHttpTransport
                .builder(URI.create(server.getUrl()).resolve("/").toString()).endpoint("/mcp").build();
        final var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build();
        client.initialize();
        return client;
    }

    private GameStateSnapshot read(McpSyncClient client) throws Exception {
        final var result = client.callTool(new CallToolRequest(ObserveGameState.NAME, Map.of()));
        Assert.assertFalse(result.isError());
        return McpJsonDefaults.getMapper().readValue(((TextContent) result.content().get(0)).text(), GameStateSnapshot.class);
    }

    @Test
    public void exposePublicResourcesAndGraveyardsWithoutHiddenCardsOrLibraryOrder() throws Exception {
        final Game game = game();
        final Player one = game.getPlayers().get(0);
        final Player two = game.getPlayers().get(1);
        one.setStartingLife(1);
        two.setStartingLife(3);
        one.setLandsPlayedThisTurn(1);
        card(one, ZoneType.Hand, "Private own hand");
        card(two, ZoneType.Hand, "Private opponent hand");
        final Card top = card(one, ZoneType.Library, "Private library top");
        card(one, ZoneType.Library, "Private library second");
        card(two, ZoneType.Library, "Private opponent library");
        final Card ownGraveyard = card(one, ZoneType.Graveyard, "Own graveyard card");
        final Card opponentGraveyard = card(two, ZoneType.Graveyard, "Opponent graveyard card");
        card(two, ZoneType.Exile, "Private face-down exile").turnFaceDown(true);
        final Card manaSource = card(one, ZoneType.Battlefield, "Mana source");
        one.getManaPool().addMana(new Mana(MagicColor.BLUE, manaSource, null, one));
        try (ForgeMcpServer server = ForgeMcpServer.start(one); var client = connect(server);
             ForgeMcpServer other = ForgeMcpServer.start(two); var otherClient = connect(other)) {
            final var snapshot = read(client);
            Assert.assertEquals(snapshot.viewerId(), one.getId());
            Assert.assertEquals(read(otherClient).viewerId(), two.getId());
            Assert.assertNull(snapshot.phase(), "The tool is also available before the first turn");
            Assert.assertNull(snapshot.turnPlayerId());
            Assert.assertEquals(snapshot.players().get(0).life(), 1);
            Assert.assertEquals(snapshot.players().get(1).life(), 3);
            Assert.assertEquals(snapshot.players().get(0).mana(), Map.of("U", 1));
            Assert.assertEquals(snapshot.players().get(0).landsPlayedThisTurn(), 1);
            Assert.assertEquals(snapshot.players().get(0).handSize(), 1);
            Assert.assertEquals(snapshot.players().get(0).librarySize(), 2);
            Assert.assertEquals(snapshot.players().get(0).graveyard().cards().get(0).id(), ownGraveyard.getId());
            Assert.assertEquals(snapshot.players().get(1).graveyard().cards().get(0).name(), opponentGraveyard.getName());
            final String json = McpJsonDefaults.getMapper().writeValueAsString(snapshot);
            Assert.assertFalse(json.contains("Private"), "Public state must not disclose hidden identities or library contents");
            Assert.assertEquals(one.getCardsIn(ZoneType.Library).get(0), top, "Reading must not change library order");
            Assert.expectThrows(UnsupportedOperationException.class, () -> snapshot.players().clear());
            Assert.expectThrows(UnsupportedOperationException.class, () -> snapshot.players().get(0).mana().put("R", 1));
            one.setLife(2, null);
            server.refreshGameState();
            Assert.assertEquals(read(client).players().get(0).life(), 2);
            Assert.assertEquals(snapshot.players().get(0).life(), 1, "Previous snapshots must remain immutable");
        }
    }

    @Test
    public void exposeTurnAndCombatAssignmentsAndRejectInvalidOrFinishedRequests() throws Exception {
        final Game game = game();
        final Player one = game.getPlayers().get(0);
        final Player two = game.getPlayers().get(1);
        one.setStartingLife(20);
        two.setStartingLife(20);
        final Card attacker = card(two, ZoneType.Battlefield, "Attacker");
        final Card blocker = card(one, ZoneType.Battlefield, "Blocker");
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, two, false, 4);
        game.getPhaseHandler().setPriority(one);
        final Combat combat = new Combat(two);
        game.getPhaseHandler().setCombat(combat);
        combat.addAttacker(attacker, one);
        combat.addBlocker(attacker, blocker);
        combat.setBlocked(attacker, true);
        try (ForgeMcpServer server = ForgeMcpServer.start(one); var client = connect(server)) {
            final var snapshot = read(client);
            Assert.assertEquals(snapshot.turn(), 4);
            Assert.assertEquals(snapshot.phase(), PhaseType.COMBAT_DECLARE_BLOCKERS.name());
            Assert.assertEquals(snapshot.turnPlayerId(), Integer.valueOf(two.getId()));
            Assert.assertEquals(snapshot.priorityPlayerId(), Integer.valueOf(one.getId()));
            Assert.assertEquals(snapshot.combat(), new GameStateSnapshot.CombatState(two.getId(), List.of(
                    new GameStateSnapshot.Attack(attacker.getId(), one.getId(), "player", true, List.of(blocker.getId())))));
            Assert.assertEquals(read(client), snapshot, "Reads must not consume a pending decision");
            Assert.assertTrue(client.listTools().tools().stream().filter(tool -> tool.name().equals(ObserveGameState.NAME))
                    .findFirst().orElseThrow().annotations().readOnlyHint());
            Assert.assertTrue(client.callTool(new CallToolRequest(ObserveGameState.NAME, Map.of("playerId", one.getId()))).isError());
            game.fireEvent(new GameEventGameFinished());
            Assert.assertTrue(client.callTool(new CallToolRequest(ObserveGameState.NAME, Map.of())).isError());
        }
        try (ForgeMcpServer server = ForgeMcpServer.start(); var client = connect(server)) {
            Assert.assertTrue(client.callTool(new CallToolRequest(ObserveGameState.NAME, Map.of())).isError());
        }
    }
}
