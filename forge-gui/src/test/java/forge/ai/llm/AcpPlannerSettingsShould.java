package forge.ai.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

public class AcpPlannerSettingsShould {
    private static final ObjectMapper JSON = new ObjectMapper();

    private AcpAgentRegistry.Entry entry(String id) {
        return new AcpAgentRegistry.Entry(id, id, "1", List.of("npx", "--yes",
                "@github/copilot-language-server@1.566.0", "--acp"), Map.of(), "");
    }

    @Test
    public void allowOpenCodeToDelegateOnlyToItsRestrictedPlannerUsingTheSelectedProviderAndModel() throws Exception {
        final var models = new AcpModels("openrouter/z-ai/glm-5.3#low", "openai/gpt-6.1-sol#high");
        try (var launch = AcpAgentSettings.prepareLaunch(entry("opencode"), models)) {
            final var config = JSON.readTree(launch.parameters().getEnv().get("OPENCODE_CONFIG_CONTENT"));
            final var parent = config.path("agent").path(AcpAgentSettings.OPENCODE_MODE).path("permission");
            final var planner = config.path("agent").path(AcpAgentSettings.OPENCODE_PLANNER);
            Assert.assertEquals(parent.path("task").path("*").asText(), "deny");
            Assert.assertEquals(parent.path("task").path(AcpAgentSettings.OPENCODE_PLANNER).asText(), "allow");
            Assert.assertEquals(parent.path("task").size(), 2);
            Assert.assertEquals(planner.path("model").asText(), "openai/gpt-6.1-sol");
            Assert.assertEquals(planner.path("variant").asText(), "high");
            Assert.assertEquals(planner.path("mode").asText(), "subagent");
            Assert.assertEquals(planner.path("permission").path("*").asText(), "deny");
            Assert.assertEquals(planner.path("permission").size(), ForgeMcpServer.READ_ONLY_TOOL_NAMES.size() + 1);
            Assert.assertEquals(parent.path("mtg-forge_save_notes").asText(), "allow");
            Assert.assertFalse(planner.path("permission").has("mtg-forge_save_notes"));
            ForgeMcpServer.READ_ONLY_TOOL_NAMES.forEach(name ->
                    Assert.assertEquals(planner.path("permission").path("mtg-forge_" + name).asText(), "allow"));
            ToolRepository.DECISIONS.forEach(name -> Assert.assertFalse(planner.path("permission").has("mtg-forge_" + name)));
            Assert.assertFalse(planner.path("permission").has("task"));
        }
    }

    @Test
    public void giveClaudeOnlyDelegationToolsAndAReadOnlyPlannerWithItsOwnModel() {
        final var metadata = JSON.valueToTree(AcpAgentSettings.sessionMetadata("claude-acp",
                new AcpModels("claude-fast", "claude-planner"))).path("claudeCode").path("options");
        Assert.assertEquals(metadata.path("tools").toString(), "[\"Agent\",\"TaskStop\"]");
        Assert.assertEquals(metadata.path("agents").size(), 1);
        final var planner = metadata.path("agents").path(AcpAgentSettings.CLAUDE_PLANNER);
        Assert.assertEquals(planner.path("model").asText(), "claude-planner");
        Assert.assertEquals(planner.path("tools").size(), ForgeMcpServer.READ_ONLY_TOOL_NAMES.size());
        ToolRepository.DECISIONS.forEach(name -> Assert.assertFalse(planner.path("tools").toString().contains("__" + name + "\"")));
        Assert.assertFalse(metadata.path("tools").toString().contains("Bash"));
        Assert.assertFalse(metadata.has("sandbox"));
        try (var launch = AcpAgentSettings.prepareLaunch(entry("claude-acp"))) {
            Assert.assertEquals(launch.parameters().getEnv().get("CLAUDE_AGENT_SDK_DISABLE_BUILTIN_AGENTS"), "1");
        }
    }

    @Test
    public void loadCopilotsPrivatePlannerThroughTheRegistryAdapterAndRetainTheToolFilter() throws Exception {
        try (var launch = AcpAgentSettings.prepareLaunch(entry("github-copilot"), new AcpModels("fast", "planning-model"))) {
            final var plugin = launch.directory().resolve("planner");
            Assert.assertEquals(JSON.readTree(Files.readString(plugin.resolve("plugin.json"))).path("name").asText(),
                    AcpAgentSettings.OPENCODE_MODE);
            final var definition = Files.readString(plugin.resolve("agents/" + AcpAgentSettings.COPILOT_PLANNER + ".agent.md"));
            Assert.assertTrue(definition.contains("model: \"planning-model\""));
            Assert.assertTrue(definition.contains("modelPolicy: required"));
            Assert.assertTrue(definition.contains("strict-tools-list: true"));
            final var tools = JSON.readTree(definition.lines().filter(line -> line.startsWith("tools: "))
                    .findFirst().orElseThrow().substring("tools: ".length()));
            Assert.assertEquals(tools.size(), ForgeMcpServer.READ_ONLY_TOOL_NAMES.size());
            ToolRepository.DECISIONS.forEach(name -> Assert.assertFalse(tools.toString().contains("/" + name + "\"")));
            final var script = Files.readString(java.nio.file.Path.of(launch.parameters().getEnv().get("COPILOT_CLI_PATH")));
            for (String tool : List.of("task", "read_agent", "list_agents", "write_agent")) Assert.assertTrue(script.contains(tool));
            Assert.assertTrue(script.contains("--plugin-dir="));
            Assert.assertFalse(script.contains("--no-sandbox"));
            Assert.assertFalse(script.contains("--allow-all"));
            Assert.assertEquals(launch.parameters().getArgs().get(launch.parameters().getArgs().size() - 1), "--acp");
        }
    }

    private com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigSelect selectOption(String id, String current, String... values) {
        return com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigSelect.builder().id(id).name(id).currentValue(current)
                .options(java.util.Arrays.stream(values).map(value ->
                        new com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigSelectOption(value, value)).toList()).build();
    }

    @Test
    public void applyEffortWhenTheModelIsAlreadySelectedAndLeaveOtherHarnessIdsLiteral() {
        final var client = org.mockito.Mockito.mock(com.agentclientprotocol.sdk.client.AcpSyncClient.class);
        final var model = selectOption("model", "openai/model", "openai/model");
        final var effort = selectOption("effort", "default", "default", "high");
        final var request = com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionRequest.select("session", "effort", "high");
        org.mockito.Mockito.when(client.setSessionConfigOption(request)).thenReturn(
                new com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionResponse(
                        List.of(model, selectOption("effort", "high", "high"))));
        AcpAgentSettings.selectModel(client,
                new com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse("session", null, List.of(model, effort)),
                "opencode", "openai/model#high");
        org.mockito.Mockito.verify(client).setSessionConfigOption(request);
        org.mockito.Mockito.verifyNoMoreInteractions(client);

        final var other = org.mockito.Mockito.mock(com.agentclientprotocol.sdk.client.AcpSyncClient.class);
        final var literal = selectOption("model", "previous", "previous", "native-model#high");
        final var modelRequest = com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionRequest
                .select("session", "model", "native-model#high");
        org.mockito.Mockito.when(other.setSessionConfigOption(modelRequest)).thenReturn(
                new com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionResponse(
                        List.of(selectOption("model", "native-model#high", "native-model#high"))));
        AcpAgentSettings.selectModel(other,
                new com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse("session", null, List.of(literal)),
                "claude-acp", "native-model#high");
        org.mockito.Mockito.verify(other).setSessionConfigOption(modelRequest);
        org.mockito.Mockito.verifyNoMoreInteractions(other);
    }

    @Test
    public void rejectUnsupportedEffortAndNeverReuseThePreviousModelsEffortChoices() {
        final var model = selectOption("model", "previous/model", "previous/model", "openai/model");
        final var selected = selectOption("model", "openai/model", "openai/model");
        final var oldEffort = selectOption("effort", "high", "high");
        final var session = new com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse("session", null, List.of(model, oldEffort));
        for (var options : List.of(List.of(selected), List.of(selected, selectOption("effort", "low", "low")))) {
            final var client = org.mockito.Mockito.mock(com.agentclientprotocol.sdk.client.AcpSyncClient.class);
            final var request = com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionRequest
                    .select("session", "model", "openai/model");
            org.mockito.Mockito.when(client.setSessionConfigOption(request)).thenReturn(
                    new com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionResponse(new java.util.ArrayList<>(options)));
            final var error = Assert.expectThrows(IllegalStateException.class,
                    () -> AcpAgentSettings.selectModel(client, session, "opencode", "openai/model#high"));
            Assert.assertTrue(error.getMessage().contains("effort"));
            org.mockito.Mockito.verify(client).setSessionConfigOption(request);
            org.mockito.Mockito.verifyNoMoreInteractions(client);
        }
    }

    @Test
    public void retainCodexFullAccessAndExistingConfigWhileSelectingThePlanningModel() throws Exception {
        final var entry = new AcpAgentRegistry.Entry("codex-acp", "Codex", "2.5.0", List.of("runner"),
                Map.of("CODEX_CONFIG", "{\"features\":{\"web_search\":false},\"agents\":{\"max_threads\":2}}"), "");
        try (var launch = AcpAgentSettings.prepareLaunch(entry, new AcpModels("fast-model", "planner-model"))) {
            final var config = JSON.readTree(launch.parameters().getEnv().get("CODEX_CONFIG"));
            Assert.assertEquals(config.path("agents").path("default_subagent_model").asText(), "planner-model");
            Assert.assertEquals(config.path("agents").path("default_subagent_reasoning_effort").asText(), "high");
            Assert.assertEquals(config.path("agents").path("max_threads").asInt(), 2);
            Assert.assertFalse(config.path("features").path("web_search").asBoolean());
            Assert.assertEquals(launch.parameters().getEnv().get("INITIAL_AGENT_MODE"), "agent-full-access");
        }
        Assert.assertFalse(AcpModels.defaults().resolve(AcpAgentRegistry.CODEX).planningModel().isEmpty());
        Assert.assertEquals(AcpModels.defaults().resolve("opencode"), AcpModels.defaults());
    }
}
