package forge.ai.llm;

import forge.ai.LobbyPlayerAi;
import forge.ai.llm.tools.ObservePreviousGameLog;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntryType;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.event.GameEventGameFinished;
import forge.game.player.GameLossReason;
import forge.game.player.RegisteredPlayer;
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

public class PreviousGameLogShould {
    @BeforeClass
    public void initializeLanguage() {
        Localizer.getInstance().initialize("en-US", "res/languages/");
        Lang.createInstance("en-US");
    }

    private Match match() {
        return new Match(new GameRules(GameType.Constructed), List.of(
                new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("One", null)),
                new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("Two", null))), "Previous game log");
    }

    private McpSyncClient connect(ForgeMcpServer server) {
        final var transport = HttpClientStreamableHttpTransport
                .builder(URI.create(server.getUrl()).resolve("/").toString()).endpoint("/mcp").build();
        final var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build();
        client.initialize();
        return client;
    }

    private PreviousGameLogSnapshot.Page read(McpSyncClient client, Map<String, Object> arguments) throws Exception {
        final var result = client.callTool(new CallToolRequest(ObservePreviousGameLog.NAME, arguments));
        Assert.assertFalse(result.isError(), result.toString());
        return McpJsonDefaults.getMapper().readValue(((TextContent) result.content().get(0)).text(),
                PreviousGameLogSnapshot.Page.class);
    }

    private void lose(Game game) {
        for (var player : game.getPlayers()) player.setStartingLife(20);
        game.getPlayers().get(0).loseConditionMet(GameLossReason.LifeReachedZero, null);
        game.setGameOver(GameEndReason.AllOpposingTeamsLost);
    }

    @Test
    public void preserveChronologicalLogsAndOutcomeAcrossGamesWithPagination() throws Exception {
        final var match = match();
        final var first = match.createGame();
        try (var server = ForgeMcpServer.start(first.getPlayers().get(0)); var client = connect(server)) {
            Assert.assertFalse(read(client, Map.of()).available());
        }
        first.getGameLog().add(GameLogEntryType.STACK_ADD, "Two cast Wurmcoil Engine.");
        first.getGameLog().add(GameLogEntryType.DAMAGE, "Wurmcoil Engine dealt 6 combat damage to One.");
        lose(first);
        final var frozen = match.getLastGameLog();
        Assert.assertTrue(frozen.stream().anyMatch(line -> line.startsWith("Game Outcome:")),
                "Capture terminal outcome events as well as gameplay");
        first.getGameLog().add(GameLogEntryType.INFORMATION, "Late mutation must not change the saved log.");
        Assert.assertFalse(frozen.stream().anyMatch(line -> line.contains("Late mutation")));
        Assert.expectThrows(UnsupportedOperationException.class, frozen::clear);

        final var next = match.createGame();
        next.getGameLog().add(GameLogEntryType.INFORMATION, "Current game's secret");
        try (var server = ForgeMcpServer.start(next.getPlayers().get(0)); var client = connect(server)) {
            final var firstPage = read(client, Map.of("limit", 1));
            Assert.assertTrue(firstPage.available());
            Assert.assertEquals(firstPage.winner(), "Two");
            Assert.assertEquals(firstPage.entries(), frozen.subList(0, 1));
            final var rest = read(client, Map.of("offset", 1, "limit", 200));
            Assert.assertEquals(rest.entries(), frozen.subList(1, frozen.size()));
            Assert.assertEquals(rest.totalEntries(), frozen.size());
            Assert.assertTrue(read(client, Map.of("offset", Integer.MAX_VALUE)).entries().isEmpty());
            Assert.assertEquals(read(client, Map.of()).entries(), frozen);
            Assert.assertFalse(read(client, Map.of()).entries().toString().contains("Current game's secret"));
            next.fireEvent(new GameEventGameFinished());
            Assert.assertTrue(client.callTool(new CallToolRequest(ObservePreviousGameLog.NAME, Map.of())).isError());
        }
        final var second = match.createGame();
        second.getGameLog().add(GameLogEntryType.STACK_ADD, "Two cast Steel Hellkite.");
        lose(second);
        Assert.assertTrue(match.getLastGameLog().get(0).contains("Steel Hellkite"));
        Assert.assertFalse(match.getLastGameLog().toString().contains("Wurmcoil Engine"));
        match.clearGamesPlayed();
        Assert.assertTrue(match.getLastGameLog().isEmpty());
        Assert.assertNull(match.getLastOutcome());
    }

    @Test
    public void validatePaginationAndRejectMissingGames() {
        Assert.assertEquals(ObservePreviousGameLog.compile(new CallToolRequest(ObservePreviousGameLog.NAME, Map.of())),
                new ObservePreviousGameLog(0, 100));
        for (var args : List.<Map<String, Object>>of(Map.of("offset", -1), Map.of("limit", 0),
                Map.of("limit", 201), Map.of("offset", 1.5), Map.of("limit", "10"), Map.of("opponent", 1))) {
            Assert.expectThrows(IllegalArgumentException.class,
                    () -> ObservePreviousGameLog.compile(new CallToolRequest(ObservePreviousGameLog.NAME, args)));
        }
        try (var server = ForgeMcpServer.start(); var client = connect(server)) {
            Assert.assertTrue(client.callTool(new CallToolRequest(ObservePreviousGameLog.NAME, Map.of())).isError());
        }
    }
}
