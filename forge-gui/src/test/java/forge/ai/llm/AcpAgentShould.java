package forge.ai.llm;

import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionCancelled;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOption;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOptionKind;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionSelected;
import com.agentclientprotocol.sdk.spec.AcpSchema.RequestPermissionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallUpdate;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpServerHttp;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigSelect;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigSelectOption;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionMode;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionModeState;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModeRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModeResponse;
import forge.ai.LobbyPlayerAi;
import forge.ai.llm.tools.ObserveStack;
import forge.ai.llm.tools.ObserveHand;
import forge.ai.llm.tools.ObserveBattlefield;
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
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
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

public class AcpAgentShould {
    private static final Path WORKING_DIRECTORY = Path.of(".").toAbsolutePath().normalize();

    @BeforeClass
    public void initializeLanguage() {
        Localizer.getInstance().initialize("en-US", "res/languages/");
    }

    private AcpSyncClient clientSelecting(String model) {
        return clientSelecting(model, AcpAgent.REASONING_EFFORT);
    }

    private AcpSyncClient clientSelecting(String model, String reasoningEffort) {
        final AcpSyncClient client = mock(AcpSyncClient.class);
        final AcpAsyncClient async = mock(AcpAsyncClient.class);
        when(client.async()).thenReturn(async);
        when(async.prompt(any())).thenAnswer(invocation ->
                Mono.fromCallable(() -> client.prompt(invocation.getArgument(0))));
        when(client.initialize()).thenReturn(InitializeResponse.ok(new AgentCapabilities(false,
                    new McpCapabilities(true, false), null)));
        when(client.newSession(any())).thenReturn(new NewSessionResponse("test-session"));
        final SessionConfigSelect option = SessionConfigSelect.builder()
                .id("model").name("Model").currentValue(model)
                .options(List.of(new SessionConfigSelectOption(model, model))).build();
        final SessionConfigSelect reasoning = SessionConfigSelect.builder()
                .id("reasoning_effort").name("Reasoning effort").currentValue(reasoningEffort)
                .options(List.of(new SessionConfigSelectOption(reasoningEffort, reasoningEffort))).build();
        when(client.setSessionConfigOption(any())).thenReturn(new SetSessionConfigOptionResponse(List.of(option, reasoning)));
        return client;
    }

    @Test
    public void useOtherHarnessDefaultsAndStillProvideForgeTools() {
        final AcpSyncClient client = clientSelecting("unused");
        try (var agent = AcpAgent.initializeForAgent(client, WORKING_DIRECTORY, null, "opencode")) {
            verify(client, times(0)).setSessionConfigOption(any());
            final ArgumentCaptor<NewSessionRequest> request = ArgumentCaptor.forClass(NewSessionRequest.class);
            verify(client).newSession(request.capture());
            Assert.assertEquals(request.getValue().mcpServers().size(), 1);
            Assert.assertEquals(((McpServerHttp) request.getValue().mcpServers().get(0)).name(), "mtg-forge");
            verify(client).prompt(any());
        }
        verify(client).close();
    }

    @Test
    public void rejectAgentsWithoutHttpMcpBeforeCreatingASession() {
        final AcpSyncClient client = clientSelecting("unused");
        when(client.initialize()).thenReturn(InitializeResponse.ok());
        final var error = Assert.expectThrows(IllegalStateException.class,
                () -> AcpAgent.initializeForAgent(client, WORKING_DIRECTORY, null, "opencode"));
        Assert.assertTrue(error.getMessage().contains("HTTP MCP"));
        verify(client, times(0)).newSession(any());
        verify(client, times(0)).prompt(any());
        verify(client).close();
    }

    @Test
    public void approveUnattendedToolCallsUsingAnOfferedPermissionOption() {
        final var options = List.of(new PermissionOption("reject", "Reject", PermissionOptionKind.REJECT_ONCE),
                new PermissionOption("always", "Allow always", PermissionOptionKind.ALLOW_ALWAYS),
                new PermissionOption("once", "Allow once", PermissionOptionKind.ALLOW_ONCE));
        final var game = new ToolCallUpdate("call", "mcp__mtg-forge__submit_choice", null, null);
        Assert.assertEquals(AcpAgentSettings.approvePermission(new RequestPermissionRequest("session", game, options)).outcome(),
                new PermissionSelected("once"));
        final var shell = new ToolCallUpdate("call", "Run shell command", null, null);
        Assert.assertEquals(AcpAgentSettings.approvePermission(new RequestPermissionRequest("session", shell, options)).outcome(),
                new PermissionSelected("once"));
        Assert.assertEquals(AcpAgentSettings.approvePermission(new RequestPermissionRequest("session", shell,
                List.of(options.get(1)))).outcome(), new PermissionSelected("always"));
        Assert.assertTrue(AcpAgentSettings.approvePermission(new RequestPermissionRequest("session", shell,
                List.of(options.get(0)))).outcome() instanceof PermissionCancelled);
    }

    @Test
    public void configureEachHarnessWithoutDroppingTheRegistryLaunchRecipe() {
        for (String id : List.of("codex-acp", "claude-acp", "opencode", "github-copilot")) {
            final var entry = new AcpAgentRegistry.Entry(id, id, "1.0", List.of("runner", "registry-package", "--registry-flag"),
                    Map.of("REGISTRY_ENV", "preserved"), "");
            final var parameters = AcpAgentSettings.launchParameters(entry);
            Assert.assertEquals(parameters.getCommand(), "runner");
            Assert.assertEquals(parameters.getArgs(), List.of("registry-package", "--registry-flag"));
            Assert.assertEquals(parameters.getEnv().get("REGISTRY_ENV"), "preserved");
            if (id.equals("codex-acp")) {
                Assert.assertEquals(parameters.getEnv().get("INITIAL_AGENT_MODE"), "agent-full-access");
            } else {
                Assert.assertFalse(parameters.getEnv().containsKey("INITIAL_AGENT_MODE"));
            }
            if (id.equals("opencode")) Assert.assertEquals(parameters.getEnv().get("OPENCODE_PERMISSION"), "{\"*\":\"allow\"}");
            if (id.equals("github-copilot")) {
                Assert.assertEquals(parameters.getEnv().get("COPILOT_ALLOW_ALL"), "true");
                Assert.assertEquals(parameters.getEnv().get("GITHUB_COPILOT_ACP_USE_CLI"), "1");
                Assert.assertFalse(parameters.getArgs().contains("--no-sandbox"), "The registry adapter does not forward CLI flags");
            }
        }
    }

    @Test
    public void disableClaudeSandboxAndSelectBypassBeforePrompting() {
        final var client = clientSelecting("unused");
        final var mode = SessionConfigSelect.builder().id("mode").name("Permissions").currentValue("default")
                .options(List.of(new SessionConfigSelectOption("default", "Default"),
                        new SessionConfigSelectOption("bypassPermissions", "Bypass"))).build();
        final var selected = SessionConfigSelect.builder().id("mode").name("Permissions").currentValue("bypassPermissions")
                .options(List.of(new SessionConfigSelectOption("bypassPermissions", "Bypass"))).build();
        when(client.newSession(any())).thenReturn(new NewSessionResponse("test-session", null, List.of(mode)));
        when(client.setSessionConfigOption(any())).thenReturn(new SetSessionConfigOptionResponse(List.of(selected)));
        try (var agent = AcpAgent.initializeForAgent(client, WORKING_DIRECTORY, null, "claude-acp")) {
            final var order = inOrder(client);
            order.verify(client).initialize();
            final var request = ArgumentCaptor.forClass(NewSessionRequest.class);
            order.verify(client).newSession(request.capture());
            Assert.assertEquals(request.getValue().meta(), Map.of("claudeCode", Map.of("options", Map.of(
                    "allowDangerouslySkipPermissions", true, "sandbox", Map.of("enabled", false)))));
            order.verify(client).setSessionConfigOption(SetSessionConfigOptionRequest.select("test-session", "mode", "bypassPermissions"));
            order.verify(client).prompt(any());
        }
    }

    @Test
    public void supportClaudeLegacyModesAndStopIfBypassIsUnavailable() {
        final var client = clientSelecting("unused");
        when(client.newSession(any())).thenReturn(new NewSessionResponse("test-session",
                new SessionModeState("default", List.of(new SessionMode("bypassPermissions", "Bypass", null)))));
        when(client.setSessionMode(any())).thenReturn(new SetSessionModeResponse());
        try (var agent = AcpAgent.initializeForAgent(client, WORKING_DIRECTORY, null, "claude-acp")) {
            verify(client).setSessionMode(new SetSessionModeRequest("test-session", "bypassPermissions"));
        }
        final var blocked = clientSelecting("unused");
        final var error = Assert.expectThrows(IllegalStateException.class,
                () -> AcpAgent.initializeForAgent(blocked, WORKING_DIRECTORY, null, "claude-acp"));
        Assert.assertTrue(error.getMessage().contains("bypassPermissions"));
        verify(blocked, times(0)).prompt(any());
        verify(blocked).close();
    }

    @Test
    public void enableCopilotAllowAllAndRejectAnUnacknowledgedPermissionChange() {
        final var client = clientSelecting("unused");
        final var off = SessionConfigSelect.builder().id("allow_all").name("Allow all").currentValue("off")
                .options(List.of(new SessionConfigSelectOption("off", "Off"), new SessionConfigSelectOption("on", "On"))).build();
        final var on = SessionConfigSelect.builder().id("allow_all").name("Allow all").currentValue("on")
                .options(List.of(new SessionConfigSelectOption("on", "On"))).build();
        when(client.newSession(any())).thenReturn(new NewSessionResponse("test-session", null, List.of(off)));
        when(client.setSessionConfigOption(any())).thenReturn(new SetSessionConfigOptionResponse(List.of(on)));
        try (var agent = AcpAgent.initializeForAgent(client, WORKING_DIRECTORY, null, "github-copilot")) {
            verify(client).setSessionConfigOption(SetSessionConfigOptionRequest.select("test-session", "allow_all", "on"));
        }
        when(client.setSessionConfigOption(any())).thenReturn(new SetSessionConfigOptionResponse(List.of(off)));
        final var error = Assert.expectThrows(IllegalStateException.class,
                () -> AcpAgent.initializeForAgent(client, WORKING_DIRECTORY, null, "github-copilot"));
        Assert.assertTrue(error.getMessage().contains("did not select allow_all=on"));
    }

    @Test
    public void sendTheOpeningPromptAfterInitializingTheSessionModelAndReasoningEffort() {
        final AcpSyncClient client = clientSelecting(AcpAgent.MODEL);
        final AcpAgent agent = AcpAgent.initialize(client, WORKING_DIRECTORY);
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
                    SetSessionConfigOptionRequest.select("test-session", "model", AcpAgent.MODEL));
            order.verify(client).setSessionConfigOption(
                    SetSessionConfigOptionRequest.select("test-session", "reasoning_effort", "low"));
            final ArgumentCaptor<PromptRequest> opening = ArgumentCaptor.forClass(PromptRequest.class);
            order.verify(client).prompt(opening.capture());
            Assert.assertEquals(opening.getValue().sessionId(), "test-session");
            Assert.assertTrue(opening.getValue().text().contains("AI player for Magic: The Gathering"));
            Assert.assertTrue(opening.getValue().text().contains("complete list of MCP tools exposed by mtg-forge"));
            Assert.assertTrue(opening.getValue().text().contains("resolve any deferred tools"));
            Assert.assertTrue(opening.getValue().text().contains("follow all pagination"));
            Assert.assertTrue(opening.getValue().text().contains("Priority protocol (Protocol: priority)"));
            Assert.assertTrue(opening.getValue().text().contains("Choice protocol (Protocol: choice)"));
            Assert.assertTrue(opening.getValue().text().contains("Payment: pay_mana"));
            Assert.assertTrue(opening.getValue().text().contains("affordability is not"));
            verifyNoMoreInteractions(client);
        } finally {
            agent.close();
        }
        agent.close();
        verify(client).close();
        verifyNoMoreInteractions(client);
    }

    @Test
    public void releaseAnUnansweredPromptWhenTheSessionCloses() throws Exception {
        final AcpSyncClient client = clientSelecting(AcpAgent.MODEL);
        try (var agent = AcpAgent.initialize(client, WORKING_DIRECTORY)) {
            final var requested = new CompletableFuture<Void>();
            when(client.async().prompt(any())).thenAnswer(invocation -> {
                requested.complete(null);
                return Mono.<PromptResponse>never();
            });
            final var prompt = CompletableFuture.runAsync(() -> agent.prompt(
                    "Choose", "pending-request", "choice", "Choose", new CompletableFuture<>()));
            requested.get(5, TimeUnit.SECONDS);
            agent.close();
            Assert.expectThrows(ExecutionException.class, () -> prompt.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void useConfiguredModelAndReasoningEffort() {
        final String previousModel = System.getProperty("forge.llm.model");
        final String previousReasoning = System.getProperty("forge.llm.reasoningEffort");
        try {
            System.setProperty("forge.llm.model", "eval-model");
            System.setProperty("forge.llm.reasoningEffort", "high");
            final var options = AcpAgent.Options.fromSystemProperties();
            final AcpSyncClient client = clientSelecting("eval-model", "high");
            try (var agent = AcpAgent.initialize(client, WORKING_DIRECTORY, null, options)) {
                Assert.assertEquals(agent.options(), new AcpAgent.Options("eval-model", "high"));
                verify(client).setSessionConfigOption(
                        SetSessionConfigOptionRequest.select("test-session", "model", "eval-model"));
                verify(client).setSessionConfigOption(
                        SetSessionConfigOptionRequest.select("test-session", "reasoning_effort", "high"));
            }
            verify(client).close();
        } finally {
            if (previousModel == null) System.clearProperty("forge.llm.model");
            else System.setProperty("forge.llm.model", previousModel);
            if (previousReasoning == null) System.clearProperty("forge.llm.reasoningEffort");
            else System.setProperty("forge.llm.reasoningEffort", previousReasoning);
        }
    }

    @Test
    public void closeTheClientWhenTheRequestedReasoningEffortIsNotSelected() {
        final AcpSyncClient client = clientSelecting("eval-model", "low");
        final var options = new AcpAgent.Options("eval-model", "high");
        final var error = Assert.expectThrows(IllegalStateException.class,
                () -> AcpAgent.initialize(client, WORKING_DIRECTORY, null, options));
        Assert.assertTrue(error.getMessage().contains("high"));
        verify(client).close();
        verify(client, times(0)).prompt(any());
    }

    @Test
    public void closeTheClientWhenTheRequestedModelIsNotSelected() {
        final AcpSyncClient client = clientSelecting("another-model");
        Assert.expectThrows(IllegalStateException.class, () -> AcpAgent.initialize(client, WORKING_DIRECTORY));
        verify(client).close();
    }

    @Test
    public void closeTheClientWhenInitializationFails() {
        final AcpSyncClient client = mock(AcpSyncClient.class);
        doThrow(new IllegalStateException("Agent unavailable")).when(client).initialize();
        Assert.expectThrows(IllegalStateException.class, () -> AcpAgent.initialize(client, WORKING_DIRECTORY));
        verify(client).initialize();
        verify(client).close();
        verifyNoMoreInteractions(client);
    }

    @Test
    public void closeTheAgentWhenTheOpeningPromptFails() {
        final AcpSyncClient client = clientSelecting(AcpAgent.MODEL);
        doThrow(new IllegalStateException("Opening prompt failed")).when(client).prompt(any());
        Assert.expectThrows(IllegalStateException.class, () -> AcpAgent.initialize(client, WORKING_DIRECTORY));
        verify(client).close();
    }

    @Test
    public void exposeGameObservationsWhileTheOpeningAcpPromptIsRunning() {
        final Game game = newGame();
        final AcpSyncClient client = clientSelecting(AcpAgent.MODEL);
        final AtomicReference<String> url = new AtomicReference<>();
        when(client.newSession(any())).thenAnswer(invocation -> {
            final NewSessionRequest request = invocation.getArgument(0);
            url.set(((McpServerHttp) request.mcpServers().get(0)).url());
            return new NewSessionResponse("test-session");
        });
        when(client.prompt(any())).thenAnswer(invocation -> {
            final var transport = HttpClientStreamableHttpTransport
                    .builder(URI.create(url.get()).resolve("/").toString()).endpoint("/mcp").build();
            try (var mcp = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build()) {
                mcp.initialize();
                final var result = mcp.callTool(new CallToolRequest(ObserveStack.NAME, Map.of()));
                Assert.assertFalse(result.isError());
                Assert.assertTrue(McpJsonDefaults.getMapper().readValue(
                        ((TextContent) result.content().get(0)).text(), StackSnapshot.class).items().isEmpty());
                for (String tool : List.of(ObserveHand.NAME, ObserveBattlefield.NAME)) {
                    final var cards = mcp.callTool(new CallToolRequest(tool, Map.of()));
                    Assert.assertFalse(cards.isError());
                    Assert.assertTrue(McpJsonDefaults.getMapper().readValue(
                            ((TextContent) cards.content().get(0)).text(), CardsSnapshot.class).cards().isEmpty());
                }
            }
            return null;
        });
        try (var agent = AcpAgent.initialize(client, WORKING_DIRECTORY, game.getPlayers().get(0))) {
            verify(client).prompt(any());
        }
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
        final AcpSyncClient client = clientSelecting(AcpAgent.MODEL);
        final AtomicInteger starts = new AtomicInteger();
        final PlayerControllerLlm controller = new PlayerControllerLlm(game, game.getPlayers().get(0),
                game.getPlayers().get(0).getLobbyPlayer(), () -> {
                    starts.incrementAndGet();
                    return AcpAgent.initialize(client, WORKING_DIRECTORY);
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
        final AcpSyncClient client = clientSelecting(AcpAgent.MODEL);
        final PlayerControllerLlm controller = new PlayerControllerLlm(game, player, player.getLobbyPlayer(),
                () -> AcpAgent.initialize(client, WORKING_DIRECTORY));
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
        final AcpSyncClient client = clientSelecting(AcpAgent.MODEL);
        final AcpAgent agent = AcpAgent.initialize(client, WORKING_DIRECTORY);
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
        final AcpSyncClient client = clientSelecting(AcpAgent.MODEL);
        final AcpAgent agent = AcpAgent.initialize(client, WORKING_DIRECTORY);
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
