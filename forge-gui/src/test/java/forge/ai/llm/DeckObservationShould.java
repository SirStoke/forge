package forge.ai.llm;

import forge.ai.LobbyPlayerAi;
import forge.ai.llm.tools.ObserveDeck;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.CardFactory;
import forge.game.event.GameEventGameFinished;
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

public class DeckObservationShould {
    @BeforeClass
    public void initializeLanguage() {
        Localizer.getInstance().initialize("en-US", "res/languages/");
        Lang.createInstance("en-US");
    }

    private PaperCard paper(String name, String edition) {
        return new PaperCard(CardRules.fromScript(List.of("Name:" + name, "ManaCost:0",
                "Types:Artifact", "Oracle:")), edition, CardRarity.Common);
    }

    private Game game(Deck first, Deck second) {
        return new Match(new GameRules(GameType.Constructed), List.of(
                new RegisteredPlayer(first).setPlayer(new LobbyPlayerAi("One", null)),
                new RegisteredPlayer(second).setPlayer(new LobbyPlayerAi("Two", null))), "Deck observation").createGame();
    }

    private McpSyncClient connect(ForgeMcpServer server) {
        final var transport = HttpClientStreamableHttpTransport
                .builder(URI.create(server.getUrl()).resolve("/").toString()).endpoint("/mcp").build();
        final var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build();
        client.initialize();
        return client;
    }

    private DeckSnapshot read(McpSyncClient client) throws Exception {
        final var result = client.callTool(new CallToolRequest(ObserveDeck.NAME, Map.of()));
        Assert.assertFalse(result.isError(), result.toString());
        return McpJsonDefaults.getMapper().readValue(((TextContent) result.content().get(0)).text(), DeckSnapshot.class);
    }

    @Test
    public void exposeOwnRegisteredSectionsWithSortedNamesAndCombinedCopyCounts() throws Exception {
        final Deck first = new Deck("One's deck");
        first.getMain().add(paper("Lightning Bolt", "ONE"), 2);
        first.getMain().add(paper("Island", "ONE"), 20);
        first.getMain().add(paper("Lightning Bolt", "TWO"), 1);
        first.getOrCreate(DeckSection.Sideboard).add(paper("Sideboard card", "ONE"), 2);
        first.getOrCreate(DeckSection.Commander).add(paper("Commander card", "ONE"));
        first.getOrCreate(DeckSection.Schemes).add(paper("Scheme card", "ONE"));
        final Deck second = new Deck("Two's deck");
        second.getMain().add(paper("Opponent secret", "ONE"), 4);
        final Game game = game(first, second);
        try (ForgeMcpServer server = ForgeMcpServer.start(game.getPlayers().get(0)); var client = connect(server);
             ForgeMcpServer other = ForgeMcpServer.start(game.getPlayers().get(1)); var otherClient = connect(other)) {
            final var snapshot = read(client);
            Assert.assertEquals(snapshot.name(), "One's deck");
            Assert.assertEquals(snapshot.sections(), List.of(
                    new DeckSnapshot.Section("Main", 23, List.of(new DeckSnapshot.CardCount("Island", 20),
                            new DeckSnapshot.CardCount("Lightning Bolt", 3))),
                    new DeckSnapshot.Section("Sideboard", 2, List.of(new DeckSnapshot.CardCount("Sideboard card", 2))),
                    new DeckSnapshot.Section("Commander", 1, List.of(new DeckSnapshot.CardCount("Commander card", 1))),
                    new DeckSnapshot.Section("Schemes", 1, List.of(new DeckSnapshot.CardCount("Scheme card", 1)))));
            Assert.assertEquals(read(client), snapshot, "Repeated reads do not consume a decision");
            Assert.assertEquals(read(otherClient), new DeckSnapshot("Two's deck", List.of(
                    new DeckSnapshot.Section("Main", 4, List.of(new DeckSnapshot.CardCount("Opponent secret", 4))))));
            Assert.expectThrows(UnsupportedOperationException.class, () -> snapshot.sections().clear());
            Assert.expectThrows(UnsupportedOperationException.class, () -> snapshot.sections().get(0).cards().clear());

            final var tool = client.listTools().tools().stream().filter(t -> t.name().equals(ObserveDeck.NAME))
                    .findFirst().orElseThrow();
            Assert.assertTrue(tool.annotations().readOnlyHint());
            Assert.assertTrue(tool.annotations().idempotentHint());
        }
    }

    @Test
    public void keepRegisteredCompositionIndependentOfLibraryContentsOrderAndDraws() throws Exception {
        final Deck deck = new Deck("Registered list");
        deck.getMain().add(paper("Registered card", "ONE"), 2);
        final Game game = game(deck, new Deck());
        final var player = game.getPlayers().get(0);
        final var first = CardFactory.getCard(paper("Live library secret", "ONE"), player, game);
        final var second = CardFactory.getCard(paper("Another library secret", "ONE"), player, game);
        player.getZone(ZoneType.Library).add(first);
        player.getZone(ZoneType.Library).add(second);
        try (ForgeMcpServer server = ForgeMcpServer.start(player); var client = connect(server)) {
            final var snapshot = read(client);
            Assert.assertEquals(snapshot.sections().get(0).cards(), List.of(new DeckSnapshot.CardCount("Registered card", 2)));
            Assert.assertEquals(player.getCardsIn(ZoneType.Library).get(0), first, "Reading must not reorder the library");
            player.getZone(ZoneType.Library).remove(first);
            player.getZone(ZoneType.Library).add(first);
            server.refreshGameState();
            Assert.assertEquals(read(client), snapshot, "Library order is never included");
            player.getZone(ZoneType.Library).remove(second);
            player.getZone(ZoneType.Hand).add(second);
            server.refreshGameState();
            Assert.assertEquals(read(client), snapshot, "Drawn cards remain in registered composition");
        }
    }

    @Test
    public void validateArgumentsAndRejectUnavailableOrFinishedGames() throws Exception {
        Assert.assertEquals(ObserveDeck.compile(new CallToolRequest(ObserveDeck.NAME, Map.of())), new ObserveDeck());
        Assert.assertEquals(ObserveDeck.compile(new CallToolRequest(ObserveDeck.NAME, (Map<String, Object>) null)),
                new ObserveDeck());
        Assert.expectThrows(IllegalArgumentException.class, () -> ObserveDeck.compile(
                new CallToolRequest(ObserveDeck.NAME, Map.of("playerId", 1))));
        try (ForgeMcpServer server = ForgeMcpServer.start(); var client = connect(server)) {
            Assert.assertTrue(client.callTool(new CallToolRequest(ObserveDeck.NAME, Map.of())).isError());
        }
        final Game game = game(new Deck(), new Deck());
        try (ForgeMcpServer server = ForgeMcpServer.start(game.getPlayers().get(0)); var client = connect(server)) {
            Assert.assertEquals(read(client).sections(), List.of(new DeckSnapshot.Section("Main", 0, List.of())));
            Assert.assertTrue(client.callTool(new CallToolRequest(ObserveDeck.NAME, Map.of("extra", true))).isError());
            game.fireEvent(new GameEventGameFinished());
            server.refreshGameState();
            Assert.assertTrue(client.callTool(new CallToolRequest(ObserveDeck.NAME, Map.of())).isError());
        }
    }
}
