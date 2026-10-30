package forge.ai.llm;

import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpServerHttp;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigSelect;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigSelectOption;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionResponse;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.event.GameEventGameFinished;
import forge.game.event.GameEventGameStarted;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.util.Localizer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class CodexAcpAgentShould {
    private static final Path WORKING_DIRECTORY = Path.of(".").toAbsolutePath().normalize();

    @BeforeClass
    public void initializeLanguage() {
        Localizer.getInstance().initialize("en-US", "res/languages/");
    }

    private AcpSyncClient clientSelecting(String model) {
        final AcpSyncClient client = mock(AcpSyncClient.class);
        when(client.newSession(any())).thenReturn(new NewSessionResponse("test-session"));
        final SessionConfigSelect option = SessionConfigSelect.builder()
                .id("model").name("Model").currentValue(model)
                .options(List.of(new SessionConfigSelectOption(model, model))).build();
        when(client.setSessionConfigOption(any())).thenReturn(new SetSessionConfigOptionResponse(List.of(option)));
        return client;
    }

    @Test
    public void sendTheOpeningPromptAfterInitializingTheSessionAndModel() {
        final AcpSyncClient client = clientSelecting(CodexAcpAgent.MODEL);
        final CodexAcpAgent agent = CodexAcpAgent.initialize(client, WORKING_DIRECTORY);
        try {
            Assert.assertEquals(agent.getSessionId(), "test-session");
            final InOrder order = inOrder(client);
            order.verify(client).initialize();
            final ArgumentCaptor<NewSessionRequest> request = ArgumentCaptor.forClass(NewSessionRequest.class);
            order.verify(client).newSession(request.capture());
            Assert.assertEquals(request.getValue().cwd(), WORKING_DIRECTORY.toString());
            Assert.assertEquals(request.getValue().mcpServers().size(), 1);
            final McpServerHttp mcp = (McpServerHttp) request.getValue().mcpServers().get(0);
            Assert.assertEquals(mcp.name(), "mtg-forge");
            Assert.assertTrue(mcp.url().matches("http://127\\.0\\.0\\.1:[0-9]+/mcp"));
            order.verify(client).setSessionConfigOption(
                    SetSessionConfigOptionRequest.select("test-session", "model", "gpt-6.1-sol"));
            final ArgumentCaptor<PromptRequest> opening = ArgumentCaptor.forClass(PromptRequest.class);
            order.verify(client).prompt(opening.capture());
            Assert.assertEquals(opening.getValue().sessionId(), "test-session");
            Assert.assertTrue(opening.getValue().text().contains("AI player for Magic: The Gathering"));
            Assert.assertTrue(opening.getValue().text().contains("complete list of MCP tools exposed by mtg-forge"));
            Assert.assertTrue(opening.getValue().text().contains("resolve any deferred tools"));
            Assert.assertTrue(opening.getValue().text().contains("follow all pagination"));
            verifyNoMoreInteractions(client);
        } finally {
            agent.close();
        }
        agent.close();
        verify(client).close();
        verifyNoMoreInteractions(client);
    }

    @Test
    public void closeTheClientWhenTheRequestedModelIsNotSelected() {
        final AcpSyncClient client = clientSelecting("another-model");
        Assert.expectThrows(IllegalStateException.class, () -> CodexAcpAgent.initialize(client, WORKING_DIRECTORY));
        verify(client).close();
    }

    @Test
    public void closeTheClientWhenInitializationFails() {
        final AcpSyncClient client = mock(AcpSyncClient.class);
        doThrow(new IllegalStateException("Agent unavailable")).when(client).initialize();
        Assert.expectThrows(IllegalStateException.class, () -> CodexAcpAgent.initialize(client, WORKING_DIRECTORY));
        verify(client).initialize();
        verify(client).close();
        verifyNoMoreInteractions(client);
    }

    @Test
    public void closeTheAgentWhenTheOpeningPromptFails() {
        final AcpSyncClient client = clientSelecting(CodexAcpAgent.MODEL);
        doThrow(new IllegalStateException("Opening prompt failed")).when(client).prompt(any());
        Assert.expectThrows(IllegalStateException.class, () -> CodexAcpAgent.initialize(client, WORKING_DIRECTORY));
        verify(client).close();
    }

    private Game newGame() {
        final List<RegisteredPlayer> players = List.of(
                new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("One", null)),
                new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("Two", null)));
        return new Match(new GameRules(GameType.Constructed), players, "ACP lifecycle").createGame();
    }

    private void startGame(Game game) {
        game.fireEvent(new GameEventGameStarted(GameType.Constructed, game.getPlayers().get(0), game.getPlayers()));
    }

    @Test
    public void initializeOnceAtGameStartAndCloseAtGameFinish() {
        final Game game = newGame();
        final AcpSyncClient client = clientSelecting(CodexAcpAgent.MODEL);
        final AtomicInteger starts = new AtomicInteger();
        final PlayerControllerLlm controller = new PlayerControllerLlm(game, game.getPlayers().get(0),
                game.getPlayers().get(0).getLobbyPlayer(), () -> {
                    starts.incrementAndGet();
                    return CodexAcpAgent.initialize(client, WORKING_DIRECTORY);
                });
        verifyNoInteractions(client);
        try {
            startGame(game);
            startGame(game);
            Assert.assertEquals(starts.get(), 1);
            verify(client).prompt(any());
            Assert.assertNull(controller.chooseSpellAbilityToPlay());
        } finally {
            game.fireEvent(new GameEventGameFinished());
        }
        game.fireEvent(new GameEventGameFinished());
        startGame(game);
        Assert.assertEquals(starts.get(), 1);
        verify(client).close();
    }

    @Test
    public void sendTheCurrentHandAndUseTheFallbackWithoutAToolSubmission() {
        final Game game = newGame();
        final var player = game.getPlayers().get(0);
        final var opponent = game.getPlayers().get(1);
        final AcpSyncClient client = clientSelecting(CodexAcpAgent.MODEL);
        final PlayerControllerLlm controller = new PlayerControllerLlm(game, player, player.getLobbyPlayer(),
                () -> CodexAcpAgent.initialize(client, WORKING_DIRECTORY));
        for (String name : List.of("Island", "Island", "Fire & Ice <test>")) {
            final Card card = new Card(game.nextCardId(), game);
            card.setOwner(player);
            card.setName(name);
            player.getZone(ZoneType.Hand).add(card);
        }
        final Card hiddenCard = new Card(game.nextCardId(), game);
        hiddenCard.setOwner(opponent);
        hiddenCard.setName("Hidden opponent card");
        opponent.getZone(ZoneType.Hand).add(hiddenCard);

        startGame(game);
        try {
            Assert.assertTrue(controller.mulliganKeepHand(opponent, 1),
                    "The built-in AI still decides whether to keep");
            final ArgumentCaptor<PromptRequest> request = ArgumentCaptor.forClass(PromptRequest.class);
            verify(client, times(2)).prompt(request.capture());
            Assert.assertEquals(request.getValue().sessionId(), "test-session");
            final String text = request.getValue().text();
            Assert.assertTrue(text.contains("Starting player: Two"));
            Assert.assertTrue(text.contains("Cards to return for this mulligan: 1"));
            Assert.assertTrue(text.contains("- Island\n- Island\n- Fire & Ice <test>"));
            Assert.assertTrue(text.contains("- Keep:"));
            Assert.assertTrue(text.contains("- Mulligan:"));
            Assert.assertTrue(text.contains("submit_mulligan_decision"));
            Assert.assertFalse(text.contains("Hidden opponent card"));
        } finally {
            game.fireEvent(new GameEventGameFinished());
        }
    }

    @Test
    public void applyMulliganDecisionsSubmittedThroughMcpDuringTheAcpTurn() {
        final Game game = newGame();
        final var player = game.getPlayers().get(0);
        final AcpSyncClient client = clientSelecting(CodexAcpAgent.MODEL);
        final CodexAcpAgent agent = CodexAcpAgent.initialize(client, WORKING_DIRECTORY);
        final PlayerControllerLlm controller = new PlayerControllerLlm(game, player, player.getLobbyPlayer(),
                () -> agent);
        startGame(game);
        final ArgumentCaptor<NewSessionRequest> session = ArgumentCaptor.forClass(NewSessionRequest.class);
        verify(client).newSession(session.capture());
        final String url = ((McpServerHttp) session.getValue().mcpServers().get(0)).url();
        final var transport = HttpClientStreamableHttpTransport
                .builder(URI.create(url).resolve("/").toString()).endpoint("/mcp").build();
        try (var mcp = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build()) {
            mcp.initialize();
            final AtomicBoolean keep = new AtomicBoolean(false);
            final AtomicReference<String> previousRequest = new AtomicReference<>();
            when(client.prompt(any())).thenAnswer(invocation -> {
                final String requestId = mulliganRequestId(invocation.getArgument(0));
                final String oldRequest = previousRequest.getAndSet(requestId);
                if (oldRequest != null) {
                    Assert.assertNotEquals(requestId, oldRequest);
                    Assert.assertTrue(mcp.callTool(new CallToolRequest("submit_mulligan_decision",
                            Map.of("requestId", oldRequest, "keep", !keep.get()))).isError());
                }
                Assert.assertFalse(mcp.callTool(new CallToolRequest("submit_mulligan_decision",
                        Map.of("requestId", requestId, "keep", keep.get()))).isError());
                Assert.assertTrue(mcp.callTool(new CallToolRequest("submit_mulligan_decision",
                        Map.of("requestId", requestId, "keep", !keep.get()))).isError());
                if (!keep.get()) {
                    throw new IllegalStateException("ACP failed after the tool accepted the decision");
                }
                return null;
            });
            // The built-in AI keeps an empty hand; the tool must override that result.
            Assert.assertFalse(controller.mulliganKeepHand(player, 0));
            keep.set(true);
            Assert.assertTrue(controller.mulliganKeepHand(player, 1));
        } finally {
            game.fireEvent(new GameEventGameFinished());
        }
    }

    private String mulliganRequestId(PromptRequest request) {
        return request.text().lines().filter(line -> line.startsWith("Request ID: "))
                .findFirst().orElseThrow().substring("Request ID: ".length());
    }

    @Test
    public void useTheBuiltInMulliganDecisionWhenPromptingFails() {
        final Game game = newGame();
        final var player = game.getPlayers().get(0);
        final AcpSyncClient client = clientSelecting(CodexAcpAgent.MODEL);
        final CodexAcpAgent agent = CodexAcpAgent.initialize(client, WORKING_DIRECTORY);
        doThrow(new IllegalStateException("Agent unavailable")).when(client).prompt(any());
        final PlayerControllerLlm controller = new PlayerControllerLlm(game, player, player.getLobbyPlayer(),
                () -> agent);
        startGame(game);
        try {
            Assert.assertTrue(controller.mulliganKeepHand(player, 0));
            final ArgumentCaptor<PromptRequest> request = ArgumentCaptor.forClass(PromptRequest.class);
            verify(client, times(2)).prompt(request.capture());
            Assert.expectThrows(IllegalStateException.class,
                    () -> agent.mulliganDecision().call(mulliganRequestId(request.getValue()), false));
        } finally {
            game.fireEvent(new GameEventGameFinished());
        }
    }

    @Test
    public void skipAgentsInSimulationCopies() {
        final Game game = newGame();
        game.markAsSimulationCopy();
        final AtomicInteger starts = new AtomicInteger();
        new PlayerControllerLlm(game, game.getPlayers().get(0), game.getPlayers().get(0).getLobbyPlayer(), () -> {
            starts.incrementAndGet();
            throw new AssertionError("A simulation must not start an external agent");
        });
        startGame(game);
        game.fireEvent(new GameEventGameFinished());
        Assert.assertEquals(starts.get(), 0);
    }

    @Test
    public void keepPassingAndAvoidRepeatedStartupAfterAFailure() {
        final Game game = newGame();
        final AtomicInteger starts = new AtomicInteger();
        final PlayerControllerLlm controller = new PlayerControllerLlm(game, game.getPlayers().get(0),
                game.getPlayers().get(0).getLobbyPlayer(), () -> {
                    starts.incrementAndGet();
                    throw new IllegalStateException("Agent unavailable");
                });
        startGame(game);
        startGame(game);
        Assert.assertEquals(starts.get(), 1);
        Assert.assertNull(controller.chooseSpellAbilityToPlay());
        game.fireEvent(new GameEventGameFinished());
    }
}
