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
import forge.ai.llm.tools.SaveNotes;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.item.PaperCard;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameLogEntryType;
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
import java.nio.file.Files;
import java.util.Set;
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
        when(client.newSession(any())).thenReturn(new NewSessionResponse("test-session",
                new SessionModeState(AcpAgentSettings.OPENCODE_MODE,
                        List.of(new SessionMode(AcpAgentSettings.OPENCODE_MODE, "Forge", null)))));
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
    public void retainCodexWorkaroundButRejectResidualPermissionRequestsForRestrictedAgents() {
        final var options = List.of(new PermissionOption("reject", "Reject", PermissionOptionKind.REJECT_ONCE),
                new PermissionOption("always", "Allow always", PermissionOptionKind.ALLOW_ALWAYS),
                new PermissionOption("once", "Allow once", PermissionOptionKind.ALLOW_ONCE));
        final var game = new ToolCallUpdate("call", "mcp__mtg-forge__submit_choice", null, null);
        final var shell = new ToolCallUpdate("call", "Run shell command", null, null);
        Assert.assertEquals(AcpAgentSettings.approvePermission(AcpAgentRegistry.CODEX,
                new RequestPermissionRequest("session", shell, options)).outcome(), new PermissionSelected("once"));
        Assert.assertEquals(AcpAgentSettings.approvePermission(AcpAgentRegistry.CODEX,
                new RequestPermissionRequest("session", shell, List.of(options.get(1)))).outcome(), new PermissionSelected("always"));
        Assert.assertTrue(AcpAgentSettings.approvePermission(AcpAgentRegistry.CODEX,
                new RequestPermissionRequest("session", shell, List.of(options.get(0)))).outcome() instanceof PermissionCancelled);
        for (String id : List.of("claude-acp", "opencode", "github-copilot", "unknown")) {
            for (var tool : List.of(game, shell)) {
                Assert.assertTrue(AcpAgentSettings.approvePermission(id,
                        new RequestPermissionRequest("session", tool, options)).outcome() instanceof PermissionCancelled,
                        "Native scoped approval must not override a remaining safety/policy prompt: " + id);
            }
        }
    }

    @Test
    public void configureEachHarnessWithoutDroppingTheRegistryLaunchRecipe() throws Exception {
        for (String id : List.of("codex-acp", "claude-acp", "opencode", "github-copilot")) {
            final var command = List.of("runner", "dlx", "@github/copilot-language-server@1.566.0", "--registry-flag");
            final var entry = new AcpAgentRegistry.Entry(id, id, "1.0", command, Map.of("REGISTRY_ENV", "preserved"), "");
            Path launcherDirectory;
            try (var launch = AcpAgentSettings.prepareLaunch(entry)) {
                launcherDirectory = launch.directory();
                final var parameters = launch.parameters();
                Assert.assertEquals(parameters.getCommand(), "runner");
                Assert.assertEquals(parameters.getArgs(), command.subList(1, command.size()));
                Assert.assertEquals(parameters.getEnv().get("REGISTRY_ENV"), "preserved");
                Assert.assertEquals(parameters.getEnv().get("INITIAL_AGENT_MODE"),
                        id.equals("codex-acp") ? "agent-full-access" : null);
                if (id.equals("opencode")) {
                    final var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    final var permission = mapper.readTree(parameters.getEnv().get("OPENCODE_PERMISSION"));
                    Assert.assertEquals(permission.path("*").asText(), "deny");
                    Assert.assertEquals(permission.size(), ForgeMcpServer.TOOL_NAMES.size() + 2);
                    Assert.assertEquals(permission.path("task").path("*").asText(), "deny");
                    Assert.assertEquals(permission.path("task").path(AcpAgentSettings.OPENCODE_PLANNER).asText(), "allow");
                    ForgeMcpServer.TOOL_NAMES.forEach(name ->
                            Assert.assertEquals(permission.path("mtg-forge_" + name).asText(), "allow"));
                    final var config = mapper.readTree(parameters.getEnv().get("OPENCODE_CONFIG_CONTENT"));
                    Assert.assertEquals(config.path("default_agent").asText(), AcpAgentSettings.OPENCODE_MODE);
                    Assert.assertEquals(config.path("agent").path(AcpAgentSettings.OPENCODE_MODE).path("permission"), permission);
                }
                if (id.equals("github-copilot")) {
                    Assert.assertEquals(parameters.getEnv().get("COPILOT_ALLOW_ALL"), "false");
                    Assert.assertEquals(parameters.getEnv().get("COPILOT_ASSISTED_APPROVAL"), "false");
                    Assert.assertEquals(parameters.getEnv().get("GITHUB_COPILOT_ACP_USE_CLI"), "1");
                    final var script = Files.readString(Path.of(parameters.getEnv().get("COPILOT_CLI_PATH")));
                    Assert.assertTrue(script.contains("--available-tools=mtg-forge-submit_choice"));
                    Assert.assertTrue(script.contains("--allow-tool=mtg-forge"));
                    Assert.assertFalse(script.contains("--no-sandbox"));
                    Assert.assertFalse(script.contains("--allow-all"));
                }
            }
            if (launcherDirectory != null) Assert.assertFalse(Files.exists(launcherDirectory));
        }
    }

    @Test
    public void restrictClaudeToForgeToolsAndSelectDefaultPermissionsBeforePrompting() {
        final var client = clientSelecting("unused");
        final var unsafe = SessionConfigSelect.builder().id("mode").name("Permissions").currentValue("bypassPermissions")
                .options(List.of(new SessionConfigSelectOption("default", "Default"),
                        new SessionConfigSelectOption("bypassPermissions", "Bypass"))).build();
        final var selected = SessionConfigSelect.builder().id("mode").name("Permissions").currentValue("default")
                .options(List.of(new SessionConfigSelectOption("default", "Default"))).build();
        when(client.newSession(any())).thenReturn(new NewSessionResponse("test-session", null, List.of(unsafe)));
        when(client.setSessionConfigOption(any())).thenReturn(new SetSessionConfigOptionResponse(List.of(selected)));
        try (var agent = AcpAgent.initializeForAgent(client, WORKING_DIRECTORY, null, "claude-acp")) {
            final var order = inOrder(client);
            order.verify(client).initialize();
            final var request = ArgumentCaptor.forClass(NewSessionRequest.class);
            order.verify(client).newSession(request.capture());
            final var meta = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(request.getValue().meta());
            final var sdk = meta.path("claudeCode").path("options");
            Assert.assertEquals(sdk.path("tools").toString(), "[\"Agent\",\"TaskStop\"]");
            Assert.assertTrue(sdk.path("strictMcpConfig").asBoolean());
            Assert.assertFalse(sdk.path("allowDangerouslySkipPermissions").asBoolean());
            Assert.assertEquals(sdk.path("settingSources").size(), 0);
            final var planner = sdk.path("agents").path(AcpAgentSettings.CLAUDE_PLANNER);
            Assert.assertEquals(planner.path("model").asText(), "inherit");
            Assert.assertEquals(planner.path("tools").size(), ForgeMcpServer.READ_ONLY_TOOL_NAMES.size());
            Assert.assertFalse(planner.path("tools").toString().contains("submit_choice"));
            order.verify(client).setSessionConfigOption(SetSessionConfigOptionRequest.select("test-session", "mode", "default"));
            order.verify(client).prompt(any());
        }
    }

    @Test
    public void supportClaudeLegacyModesAndStopIfDefaultIsUnavailable() {
        final var client = clientSelecting("unused");
        when(client.newSession(any())).thenReturn(new NewSessionResponse("test-session",
                new SessionModeState("bypassPermissions", List.of(new SessionMode("default", "Default", null)))));
        when(client.setSessionMode(any())).thenReturn(new SetSessionModeResponse());
        try (var agent = AcpAgent.initializeForAgent(client, WORKING_DIRECTORY, null, "claude-acp")) {
            verify(client).setSessionMode(new SetSessionModeRequest("test-session", "default"));
        }
        for (String id : List.of("claude-acp", "opencode")) {
            final var blocked = clientSelecting("unused");
            final var error = Assert.expectThrows(IllegalStateException.class,
                    () -> AcpAgent.initializeForAgent(blocked, WORKING_DIRECTORY, null, id));
            Assert.assertTrue(error.getMessage().contains("does not offer"));
            verify(blocked, times(0)).prompt(any());
            verify(blocked).close();
        }
    }

    @Test
    public void disableCopilotAllowAllAndRejectAnUnacknowledgedPermissionChange() {
        final var client = clientSelecting("unused");
        final var off = SessionConfigSelect.builder().id("allow_all").name("Allow all").currentValue("off")
                .options(List.of(new SessionConfigSelectOption("off", "Off"))).build();
        final var on = SessionConfigSelect.builder().id("allow_all").name("Allow all").currentValue("on")
                .options(List.of(new SessionConfigSelectOption("off", "Off"), new SessionConfigSelectOption("on", "On"))).build();
        when(client.newSession(any())).thenReturn(new NewSessionResponse("test-session", null, List.of(on)));
        when(client.setSessionConfigOption(any())).thenReturn(new SetSessionConfigOptionResponse(List.of(off)));
        try (var agent = AcpAgent.initializeForAgent(client, WORKING_DIRECTORY, null, "github-copilot")) {
            verify(client).setSessionConfigOption(SetSessionConfigOptionRequest.select("test-session", "allow_all", "off"));
        }
        when(client.setSessionConfigOption(any())).thenReturn(new SetSessionConfigOptionResponse(List.of(on)));
        final var error = Assert.expectThrows(IllegalStateException.class,
                () -> AcpAgent.initializeForAgent(client, WORKING_DIRECTORY, null, "github-copilot"));
        Assert.assertTrue(error.getMessage().contains("did not select allow_all=off"));
    }

    @Test
    public void selectOpenCodeGameAgentBeforePromptingAndStopIfTheAgentIsRefused() {
        final var client = clientSelecting("unused");
        final var build = SessionConfigSelect.builder().id("mode").name("Agent").currentValue("build")
                .options(List.of(new SessionConfigSelectOption("build", "Build"),
                        new SessionConfigSelectOption(AcpAgentSettings.OPENCODE_MODE, "Forge"))).build();
        final var game = SessionConfigSelect.builder().id("mode").name("Agent").currentValue(AcpAgentSettings.OPENCODE_MODE)
                .options(List.of(new SessionConfigSelectOption(AcpAgentSettings.OPENCODE_MODE, "Forge"))).build();
        when(client.newSession(any())).thenReturn(new NewSessionResponse("test-session", null, List.of(build)));
        when(client.setSessionConfigOption(any())).thenReturn(new SetSessionConfigOptionResponse(List.of(game)));
        try (var agent = AcpAgent.initializeForAgent(client, WORKING_DIRECTORY, null, "opencode")) {
            final var order = inOrder(client);
            order.verify(client).setSessionConfigOption(SetSessionConfigOptionRequest.select(
                    "test-session", "mode", AcpAgentSettings.OPENCODE_MODE));
            order.verify(client).prompt(any());
        }
        when(client.setSessionConfigOption(any())).thenReturn(new SetSessionConfigOptionResponse(List.of(build)));
        final var error = Assert.expectThrows(IllegalStateException.class,
                () -> AcpAgent.initializeForAgent(client, WORKING_DIRECTORY, null, "opencode"));
        Assert.assertTrue(error.getMessage().contains("did not select mode="));
    }

    @Test
    public void forwardCopilotArgumentsThroughTheRegistryLauncherWithoutShellExpansion() throws Exception {
        if (java.io.File.separatorChar == '\\') return;
        final var directory = Files.createTempDirectory("forge-copilot-launcher-test-");
        try {
            final var runner = directory.resolve("fake runner ' $cash");
            Files.writeString(runner, "#!/bin/sh\nprintf '%s\\n' \"$@\"\n");
            Assert.assertTrue(runner.toFile().setExecutable(true));
            final var launcher = directory.resolve("copilot");
            Files.writeString(launcher, AcpAgentSettings.copilotLauncher(
                    List.of(runner.toString(), "exec", "--yes", "--", "@github/copilot-language-server@1.566.0", "--acp"), false));
            Assert.assertTrue(launcher.toFile().setExecutable(true));
            final var process = new ProcessBuilder(launcher.toString(), "--acp", "--stdio", "argument with spaces").start();
            final var arguments = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList();
            Assert.assertEquals(process.waitFor(), 0);
            Assert.assertEquals(arguments.subList(0, 4), List.of("exec", "--yes", "--", "@github/copilot@1.0.103"));
            Assert.assertEquals(arguments.subList(arguments.size() - 3, arguments.size()),
                    List.of("--acp", "--stdio", "argument with spaces"));
            Assert.assertTrue(arguments.contains("--available-tools=" + String.join(",",
                    java.util.stream.Stream.concat(ForgeMcpServer.TOOL_NAMES.stream().map(name -> "mtg-forge-" + name),
                            AcpAgentSettings.COPILOT_DELEGATION_TOOLS.stream()).toList())));
            Assert.assertTrue(arguments.contains("--allow-tool=mtg-forge"));
            Assert.assertFalse(arguments.contains("--no-sandbox"));
        } finally {
            forge.util.FileUtil.deleteDirectory(directory.toFile());
        }
        final var windows = AcpAgentSettings.copilotLauncher(
                List.of("C:\\Program Files\\100% npm\\npx.cmd", "--yes", "@github/copilot-language-server@1.566.0", "--acp"), true);
        Assert.assertTrue(windows.contains("100%% npm"));
        Assert.assertTrue(windows.endsWith(" %*\r\n"));
        Assert.expectThrows(IllegalStateException.class,
                () -> AcpAgentSettings.copilotLauncher(List.of("unrecognized", "--acp"), false));
    }

    @Test
    public void keepHarnessAllowListsInSyncWithTheMcpToolsActuallyRegistered() {
        try (var server = ForgeMcpServer.start();
             var mcp = McpClient.sync(HttpClientStreamableHttpTransport.builder(server.getUrl()).build()).build()) {
            mcp.initialize();
            Assert.assertEquals(mcp.listTools().tools().stream().map(io.modelcontextprotocol.spec.McpSchema.Tool::name)
                    .collect(java.util.stream.Collectors.toSet()), Set.copyOf(ForgeMcpServer.TOOL_NAMES));
        }
    }

    @Test
    public void selectTheFastModelAndDescribeTheChosenPlannerBeforeSendingTheFirstPrompt() {
        for (String id : List.of("opencode", "claude-acp", "github-copilot", AcpAgentRegistry.CODEX)) {
            final var models = new AcpModels(id.equals("opencode") ? "openrouter/z-ai/glm-5.3" : "fast-model",
                    id.equals("opencode") ? "openai/gpt-6.1-sol" : "planning-model");
            final var client = clientSelecting(models.fastModel());
            if (!id.equals(AcpAgentRegistry.CODEX)) {
                final String mode = id.equals("opencode") ? AcpAgentSettings.OPENCODE_MODE : "default";
                final var model = SessionConfigSelect.builder().id("model").name("Model").currentValue("previous")
                        .options(List.of(new SessionConfigSelectOption("previous", "Previous"),
                                new SessionConfigSelectOption(models.fastModel(), "Selected"))).build();
                final var selected = SessionConfigSelect.builder().id("model").name("Model").currentValue(models.fastModel())
                        .options(List.of(new SessionConfigSelectOption(models.fastModel(), "Selected"))).build();
                when(client.newSession(any())).thenReturn(new NewSessionResponse("test-session",
                        new SessionModeState(mode, List.of(new SessionMode(mode, mode, null))), List.of(model)));
                when(client.setSessionConfigOption(any())).thenReturn(new SetSessionConfigOptionResponse(List.of(selected)));
            }
            try (var agent = AcpAgent.initializeForAgent(client, WORKING_DIRECTORY, null, id, models)) {
                Assert.assertEquals(agent.options().model(), models.fastModel());
                final var order = inOrder(client);
                order.verify(client).setSessionConfigOption(SetSessionConfigOptionRequest.select("test-session", "model", models.fastModel()));
                final var prompt = ArgumentCaptor.forClass(PromptRequest.class);
                order.verify(client).prompt(prompt.capture());
                Assert.assertTrue(prompt.getValue().text().contains(models.planningModel()));
                Assert.assertFalse(prompt.getValue().text().contains("&#"), "Provider/model IDs must remain literal");
                agent.prompt("Next decision");
                verify(client, times(2)).newSession(any());
                verify(client, times(2)).prompt(prompt.capture());
                Assert.assertTrue(prompt.getValue().text().contains(models.planningModel()),
                        "Fresh conversations must retain the selected planning model and consultation instructions");
                Assert.assertTrue(prompt.getValue().text().contains("Strategic consultation:"));
            }
        }
    }

    @Test
    public void applyOpenCodeFragmentEffortAfterTheModelChangesAndBeforeTheFirstPrompt() {
        final var client = clientSelecting("unused");
        final String modelId = "openrouter/z-ai/glm-5.3";
        final var oldModel = SessionConfigSelect.builder().id("model").name("Model").currentValue("openai/previous")
                .options(List.of(new SessionConfigSelectOption("openai/previous", "Previous"),
                        new SessionConfigSelectOption(modelId, "Selected"))).build();
        final var model = SessionConfigSelect.builder().id("model").name("Model").currentValue(modelId)
                .options(List.of(new SessionConfigSelectOption(modelId, "Selected"))).build();
        final var oldEffort = SessionConfigSelect.builder().id("effort").name("Effort").currentValue("low")
                .options(List.of(new SessionConfigSelectOption("low", "Low"))).build();
        final var effort = SessionConfigSelect.builder().id("effort").name("Effort").currentValue("default")
                .options(List.of(new SessionConfigSelectOption("default", "Default"),
                        new SessionConfigSelectOption("high", "High"))).build();
        final var selectedEffort = SessionConfigSelect.builder().id("effort").name("Effort").currentValue("high")
                .options(List.of(new SessionConfigSelectOption("high", "High"))).build();
        when(client.newSession(any())).thenReturn(new NewSessionResponse("test-session",
                new SessionModeState(AcpAgentSettings.OPENCODE_MODE,
                        List.of(new SessionMode(AcpAgentSettings.OPENCODE_MODE, "Forge", null))), List.of(oldModel, oldEffort)));
        when(client.setSessionConfigOption(SetSessionConfigOptionRequest.select("test-session", "model", modelId)))
                .thenReturn(new SetSessionConfigOptionResponse(List.of(model, effort)));
        when(client.setSessionConfigOption(SetSessionConfigOptionRequest.select("test-session", "effort", "high")))
                .thenReturn(new SetSessionConfigOptionResponse(List.of(model, selectedEffort)));
        try (var agent = AcpAgent.initializeForAgent(client, WORKING_DIRECTORY, null, "opencode",
                new AcpModels(modelId + "#high", "openai/gpt-6.1-sol#low"))) {
            final var order = inOrder(client);
            order.verify(client).setSessionConfigOption(SetSessionConfigOptionRequest.select("test-session", "model", modelId));
            order.verify(client).setSessionConfigOption(SetSessionConfigOptionRequest.select("test-session", "effort", "high"));
            order.verify(client).prompt(any());
        }
        when(client.setSessionConfigOption(SetSessionConfigOptionRequest.select("test-session", "effort", "high")))
                .thenReturn(new SetSessionConfigOptionResponse(List.of(model, effort)));
        org.mockito.Mockito.clearInvocations(client);
        final var error = Assert.expectThrows(IllegalStateException.class, () -> AcpAgent.initializeForAgent(client,
                WORKING_DIRECTORY, null, "opencode", new AcpModels(modelId + "#high", "")));
        Assert.assertTrue(error.getMessage().contains("did not select effort=high"));
        verify(client, times(0)).prompt(any());
        verify(client).close();
    }

    @Test
    public void stopBeforePromptingIfAnExplicitFastModelCannotBeSelected() {
        final var client = clientSelecting("unused");
        final var previous = SessionConfigSelect.builder().id("model").name("Model").currentValue("openai/previous")
                .options(List.of(new SessionConfigSelectOption("openai/previous", "Previous"))).build();
        when(client.newSession(any())).thenReturn(new NewSessionResponse("test-session",
                new SessionModeState(AcpAgentSettings.OPENCODE_MODE,
                        List.of(new SessionMode(AcpAgentSettings.OPENCODE_MODE, "Forge", null))), List.of(previous)));
        final var error = Assert.expectThrows(IllegalStateException.class, () -> AcpAgent.initializeForAgent(client,
                WORKING_DIRECTORY, null, "opencode", new AcpModels("openrouter/not-configured", "")));
        Assert.assertTrue(error.getMessage().contains("does not offer model=openrouter/not-configured"));
        verify(client, times(0)).prompt(any());
        verify(client).close();
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
    public void startFreshConversationsWithTheFullLogGameContextAndSavedNotes() {
        final var game = newGame();
        final var player = game.getPlayers().get(0);
        final var opponent = game.getPlayers().get(1);
        player.setStartingLife(20);
        opponent.setStartingLife(17);
        player.getRegisteredPlayer().getDeck().getMain().add(new PaperCard(CardRules.fromScript(
                List.of("Name:Registered plan card", "ManaCost:0", "Types:Artifact", "Oracle:")), "", CardRarity.Common), 2);
        final var hand = new Card(game.nextCardId(), game);
        hand.setOwner(player);
        hand.setName("Current hand card");
        player.getZone(ZoneType.Hand).add(hand);
        final var battlefield = new Card(game.nextCardId(), game);
        battlefield.setOwner(opponent);
        battlefield.setName("Opponent public threat");
        opponent.getZone(ZoneType.Battlefield).add(battlefield);
        final var hidden = new Card(game.nextCardId(), game);
        hidden.setOwner(opponent);
        hidden.setName("Opponent private card");
        opponent.getZone(ZoneType.Hand).add(hidden);
        final var client = clientSelecting(AcpAgent.MODEL);
        final var sessions = new AtomicInteger();
        final var url = new AtomicReference<String>();
        when(client.newSession(any())).thenAnswer(invocation -> {
            final NewSessionRequest request = invocation.getArgument(0);
            url.set(((McpServerHttp) request.mcpServers().get(0)).url());
            return new NewSessionResponse("session-" + sessions.incrementAndGet());
        });
        game.getGameLog().add(GameLogEntryType.TURN, "First turn");
        game.getGameLog().add(GameLogEntryType.MANA, "One tapped Island");
        try (var agent = AcpAgent.initialize(client, WORKING_DIRECTORY, player);
             var mcp = McpClient.sync(HttpClientStreamableHttpTransport.builder(url.get()).build())
                     .requestTimeout(Duration.ofSeconds(5)).build()) {
            mcp.initialize();
            Assert.assertFalse(mcp.callTool(new CallToolRequest(SaveNotes.NAME,
                    Map.of("text", "Develop mana, then answer the threat."))).isError());
            agent.prompt("First decision");
            game.getGameLog().add(GameLogEntryType.DAMAGE, "Two took 3 damage");
            opponent.setLife(14, null);
            hand.setName("Updated hand card");
            battlefield.setTapped(true);
            Assert.assertFalse(mcp.callTool(new CallToolRequest(SaveNotes.NAME,
                    Map.of("text", "The threat is tapped; set up lethal next turn."))).isError());
            agent.prompt("Second decision");

            final var prompts = ArgumentCaptor.forClass(PromptRequest.class);
            verify(client, times(3)).prompt(prompts.capture());
            final var first = prompts.getAllValues().get(1);
            final var second = prompts.getAllValues().get(2);
            Assert.assertEquals(first.sessionId(), "session-2");
            Assert.assertEquals(second.sessionId(), "session-3");
            Assert.assertEquals(agent.getSessionId(), "session-3");
            final String log = String.join("\n", game.getGameLog().getAllEntries().stream()
                    .map(Object::toString).toList());
            Assert.assertTrue(second.text().contains(log), "Include every log type in chronological order");
            Assert.assertFalse(first.text().contains("Two took 3 damage"));
            Assert.assertTrue(second.text().startsWith("You are an AI player for Magic: The Gathering"));
            Assert.assertEquals(currentRequest(second), "Second decision");
            Assert.assertFalse(second.text().contains("First decision"));
            for (var prompt : List.of(first, second)) {
                Assert.assertTrue(prompt.text().contains("Your registered deck list:\n"));
                Assert.assertTrue(prompt.text().contains("\"name\":\"Registered plan card\",\"count\":2"));
                Assert.assertTrue(prompt.text().contains("Your current hand:\n"));
                Assert.assertTrue(prompt.text().contains("Life totals:\nOne (id=0): 20"));
                Assert.assertTrue(prompt.text().contains("Battlefield (all players):\n"));
                Assert.assertTrue(prompt.text().contains("Opponent public threat"));
                Assert.assertFalse(prompt.text().contains("Opponent private card"));
                Assert.assertTrue(prompt.text().contains("Develop mana, then answer the threat."));
                Assert.assertTrue(prompt.text().contains("Before EVERY gameplay decision, call save_notes"));
            }
            Assert.assertTrue(first.text().contains("Current hand card"));
            Assert.assertTrue(first.text().contains("Two (id=1): 17"));
            Assert.assertFalse(first.text().contains("The threat is tapped; set up lethal next turn."));
            Assert.assertFalse(second.text().contains("Current hand card"));
            Assert.assertTrue(second.text().contains("Updated hand card"));
            Assert.assertTrue(second.text().contains("Two (id=1): 14"));
            Assert.assertTrue(second.text().contains("\"tapped\":true"));
            Assert.assertTrue(second.text().contains("Develop mana, then answer the threat.\n\n"
                    + "The threat is tapped; set up lethal next turn."));
            for (int i = 1; i <= 3; i++) {
                verify(client).setSessionConfigOption(SetSessionConfigOptionRequest.select("session-" + i, "model", AcpAgent.MODEL));
                verify(client).setSessionConfigOption(SetSessionConfigOptionRequest.select("session-" + i, "reasoning_effort", AcpAgent.REASONING_EFFORT));
            }
            final var requests = ArgumentCaptor.forClass(NewSessionRequest.class);
            verify(client, times(3)).newSession(requests.capture());
            Assert.assertEquals(Set.copyOf(requests.getAllValues()).size(), 1,
                    "Reuse the working directory, game tool endpoint and harness metadata");
            verify(client).initialize();
        }
    }

    @Test
    public void bufferPromptsInOrderAndContinueAfterAResponseFailsWithoutCancellingAcceptedDecisions() throws Exception {
        final var client = clientSelecting(AcpAgent.MODEL);
        try (var agent = AcpAgent.initialize(client, WORKING_DIRECTORY)) {
            final var requests = new java.util.concurrent.LinkedBlockingQueue<String>();
            final var first = new CompletableFuture<PromptResponse>();
            final var second = new CompletableFuture<PromptResponse>();
            final var third = new CompletableFuture<PromptResponse>();
            final var turns = Map.of("First", first, "Second", second, "Third", third);
            when(client.async().prompt(any())).thenAnswer(invocation -> {
                final PromptRequest request = invocation.getArgument(0);
                requests.add(currentRequest(request));
                return Mono.fromFuture(turns.get(currentRequest(request)));
            });
            agent.prompt("First", "one", "choice", "First", CompletableFuture.completedFuture("accepted"));
            Assert.assertEquals(requests.poll(5, TimeUnit.SECONDS), "First");
            agent.prompt("Second", "two", "choice", "Second", CompletableFuture.completedFuture("accepted"));
            agent.prompt("Third", "three", "choice", "Third", CompletableFuture.completedFuture("accepted"));
            verify(client.async(), times(1)).prompt(any());
            Assert.assertFalse(first.isDone(), "Accepting decisions must not stop the active response");
            Assert.assertFalse(second.isDone());
            Assert.assertFalse(third.isDone());

            first.completeExceptionally(new IllegalStateException("First response failed after its decision was accepted"));
            Assert.assertEquals(requests.poll(5, TimeUnit.SECONDS), "Second");
            verify(client.async(), times(2)).prompt(any());
            second.complete(null);
            Assert.assertEquals(requests.poll(5, TimeUnit.SECONDS), "Third");
            third.complete(null);
            Assert.assertFalse(first.isCancelled());
            Assert.assertFalse(second.isCancelled());
            Assert.assertFalse(third.isCancelled());
        }
    }

    @Test
    public void releaseAnAcceptedMcpDecisionEvenWhileItsPromptIsBuffered() throws Exception {
        final var client = clientSelecting(AcpAgent.MODEL);
        try (var agent = AcpAgent.initialize(client, WORKING_DIRECTORY)) {
            final var session = ArgumentCaptor.forClass(NewSessionRequest.class);
            verify(client).newSession(session.capture());
            final String url = ((McpServerHttp) session.getValue().mcpServers().get(0)).url();
            final var transport = HttpClientStreamableHttpTransport
                    .builder(URI.create(url).resolve("/").toString()).endpoint("/mcp").build();
            try (var mcp = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build()) {
                mcp.initialize();
                final var requests = new java.util.concurrent.LinkedBlockingQueue<String>();
                final var active = new CompletableFuture<PromptResponse>();
                final var buffered = new CompletableFuture<PromptResponse>();
                when(client.async().prompt(any())).thenAnswer(invocation -> {
                    final PromptRequest request = invocation.getArgument(0);
                    requests.add(currentRequest(request));
                    return Mono.fromFuture("Active".equals(currentRequest(request)) ? active : buffered);
                });
                agent.prompt("Active", "active", "priority", "Active", CompletableFuture.completedFuture("accepted"));
                Assert.assertEquals(requests.poll(5, TimeUnit.SECONDS), "Active");
                final var decision = new CompletableFuture<Boolean>();
                final String requestId = agent.mulliganDecision().register(keep -> {
                    decision.complete(keep);
                    return "Accepted";
                });
                final var game = CompletableFuture.runAsync(() -> agent.prompt(
                        "Buffered", requestId, "mulligan", "Buffered", decision));
                Assert.assertFalse(mcp.callTool(new CallToolRequest("submit_mulligan_decision",
                        Map.of("requestId", requestId, "keep", true))).isError());
                game.get(5, TimeUnit.SECONDS);
                Assert.assertTrue(decision.join());
                Assert.assertFalse(active.isDone(), "An accepted tool decision must never wait for the previous response");
                Assert.assertFalse(buffered.isDone());
                verify(client.async(), times(1)).prompt(any());

                active.complete(null);
                Assert.assertEquals(requests.poll(5, TimeUnit.SECONDS), "Buffered",
                        "Even an already-accepted buffered prompt must be retained");
                buffered.complete(null);
            }
        }
    }

    @Test
    public void cancelActiveAndBufferedPromptsOnlyWhenTheSessionCloses() throws Exception {
        final var client = clientSelecting(AcpAgent.MODEL);
        final var active = new CompletableFuture<PromptResponse>();
        final var started = new CompletableFuture<Void>();
        try (var agent = AcpAgent.initialize(client, WORKING_DIRECTORY)) {
            when(client.async().prompt(any())).thenAnswer(invocation -> {
                started.complete(null);
                return Mono.fromFuture(active);
            });
            agent.prompt("Active", "active", "choice", "Active", CompletableFuture.completedFuture("accepted"));
            started.get(5, TimeUnit.SECONDS);
            agent.prompt("Buffered", "buffered", "choice", "Buffered", CompletableFuture.completedFuture("accepted"));
            Assert.assertFalse(active.isDone());
            agent.close();
            Assert.expectThrows(java.util.concurrent.CancellationException.class, () -> active.get(5, TimeUnit.SECONDS));
            Assert.assertTrue(active.isCancelled());
            verify(client.async(), times(1)).prompt(any());
            Assert.expectThrows(IllegalStateException.class, () -> agent.prompt("After close"));
        }
    }

    @Test
    public void bufferPromptsWithoutADecisionBehindTheActiveResponseToo() throws Exception {
        final var client = clientSelecting(AcpAgent.MODEL);
        final var active = new CompletableFuture<PromptResponse>();
        final var started = new CompletableFuture<Void>();
        try (var agent = AcpAgent.initialize(client, WORKING_DIRECTORY)) {
            when(client.async().prompt(any())).thenAnswer(invocation -> {
                started.complete(null);
                return Mono.fromFuture(active);
            });
            agent.prompt("Active", "active", "choice", "Active", CompletableFuture.completedFuture("accepted"));
            started.get(5, TimeUnit.SECONDS);
            final var waiting = CompletableFuture.runAsync(() -> agent.prompt("Wait for completion"));
            Assert.expectThrows(java.util.concurrent.TimeoutException.class, () -> waiting.get(100, TimeUnit.MILLISECONDS));
            verify(client, times(1)).prompt(any());
            active.complete(null);
            waiting.get(5, TimeUnit.SECONDS);
            final var prompts = ArgumentCaptor.forClass(PromptRequest.class);
            verify(client, times(2)).prompt(prompts.capture());
            Assert.assertEquals(currentRequest(prompts.getValue()), "Wait for completion");
            Assert.assertFalse(active.isCancelled());
        }
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

    private String currentRequest(PromptRequest request) {
        return request.text().split("\nCurrent request:\n", 2)[1];
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
