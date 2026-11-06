package forge.ai.llm;

import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionCancelled;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOptionKind;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionSelected;
import com.agentclientprotocol.sdk.spec.AcpSchema.RequestPermissionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.RequestPermissionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigSelect;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigOption;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModeRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.util.FileUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Process/session overrides; never edit the player's saved harness configuration. */
final class AcpAgentSettings {
    // A fresh name prevents a user's existing agent-specific grants from merging into our game agent.
    static final String OPENCODE_MODE = "forge-game-" + UUID.randomUUID();
    static final String OPENCODE_PLANNER = OPENCODE_MODE + "-planner";
    static final String COPILOT_PLANNER = OPENCODE_PLANNER;
    static final String CLAUDE_PLANNER = "forge-planner";
    static final List<String> CLAUDE_DELEGATION_TOOLS = List.of("Agent", "TaskStop");
    static final List<String> COPILOT_DELEGATION_TOOLS = List.of("task", "read_agent", "list_agents", "write_agent");
    private static final String PLANNER_PROMPT = "Plan a Magic: The Gathering decision using the supplied context and "
            + "read-only Forge MCP tools. Return advice to the parent; never submit gameplay decisions, execute shell "
            + "commands, access files, or spawn another agent.";
    private static final ObjectMapper JSON = new ObjectMapper();
    // Minimum CLI shipped with the bundled registry language-server adapter. Updates fail closed if incompatible.
    private static final String COPILOT_PACKAGE = "@github/copilot@1.0.103";

    private AcpAgentSettings() { }

    record Launch(AgentParameters parameters, Path directory) implements AutoCloseable {
        @Override public void close() {
            if (directory != null) FileUtil.deleteDirectory(directory.toFile());
        }
    }

    static Launch prepareLaunch(AcpAgentRegistry.Entry entry) {
        return prepareLaunch(entry, AcpModels.defaults().resolve(entry.id()));
    }

    static Launch prepareLaunch(AcpAgentRegistry.Entry entry, AcpModels models) {
        Path directory = null;
        try {
            final var parameters = AgentParameters.builder(entry.command().get(0))
                    .args(entry.command().subList(1, entry.command().size()))
                    .env(entry.environment());
            switch (entry.id()) {
                case AcpAgentRegistry.CODEX -> {
                    parameters.addEnvVar("NO_BROWSER", "1").addEnvVar("INITIAL_AGENT_MODE", "agent-full-access");
                    final String existing = entry.environment().getOrDefault("CODEX_CONFIG", System.getenv("CODEX_CONFIG"));
                    final var config = existing == null || existing.isBlank() ? JSON.createObjectNode() : JSON.readTree(existing);
                    if (!(config instanceof com.fasterxml.jackson.databind.node.ObjectNode object)) {
                        throw new IllegalArgumentException("CODEX_CONFIG must be a JSON object");
                    }
                    final var agents = object.path("agents") instanceof com.fasterxml.jackson.databind.node.ObjectNode node
                            ? node : object.putObject("agents");
                    if (!models.planningModel().isEmpty()) agents.put("default_subagent_model", models.planningModel());
                    agents.put("default_subagent_reasoning_effort", "high");
                    parameters.addEnvVar("CODEX_CONFIG", object.toString());
                }
                case "claude-acp" -> parameters.addEnvVar("ENABLE_TOOL_SEARCH", "false")
                        .addEnvVar("CLAUDE_AGENT_SDK_DISABLE_BUILTIN_AGENTS", "1");
                case "opencode" -> {
                    final Map<String, Object> permissions = openCodePermissions(ForgeMcpServer.TOOL_NAMES);
                    // OpenCode V2 migrates V1's task permission to its subagent action.
                    final Map<String, String> delegates = new LinkedHashMap<>();
                    delegates.put("*", "deny");
                    delegates.put(OPENCODE_PLANNER, "allow");
                    permissions.put("task", delegates);
                    final Map<String, Object> planner = new LinkedHashMap<>();
                    planner.put("mode", "subagent");
                    planner.put("description", "Consult for strategic Magic planning");
                    planner.put("prompt", PLANNER_PROMPT);
                    planner.put("permission", openCodePermissions(ForgeMcpServer.READ_ONLY_TOOL_NAMES));
                    if (!models.planningModel().isEmpty()) {
                        final var selection = AcpModels.parseOpenCode(models.planningModel());
                        planner.put("model", selection.model());
                        if (!selection.effort().isEmpty()) planner.put("variant", selection.effort());
                    }
                    parameters.addEnvVar("OPENCODE_PERMISSION", json(permissions))
                            .addEnvVar("OPENCODE_CONFIG_CONTENT", json(Map.of(
                                    "default_agent", OPENCODE_MODE,
                                    "agent", Map.of(OPENCODE_MODE, Map.of("mode", "primary",
                                                    "description", "Play Magic using Forge's game tools", "permission", permissions),
                                            OPENCODE_PLANNER, planner), "permission", permissions)));
                }
                case "github-copilot" -> {
                    directory = Files.createTempDirectory("forge-copilot-");
                    final boolean windows = File.separatorChar == '\\';
                    final Path launcher = directory.resolve(windows ? "copilot.cmd" : "copilot");
                    final Path plugin = directory.resolve("planner");
                    Files.createDirectories(plugin.resolve("agents"));
                    Files.writeString(plugin.resolve("plugin.json"), json(Map.of("name", OPENCODE_MODE, "version", "1.0.0")));
                    Files.writeString(plugin.resolve("agents/" + COPILOT_PLANNER + ".agent.md"), copilotPlanner(models));
                    Files.writeString(launcher, copilotLauncher(entry.command(), windows, plugin));
                    if (!windows && !launcher.toFile().setExecutable(true, true)) {
                        throw new IOException("Could not make Copilot launcher executable");
                    }
                    // Keep the registry adapter; it discovers this launcher and forwards its ACP arguments to it.
                    parameters.addEnvVar("COPILOT_CLI_PATH", launcher.toString())
                            .addEnvVar("COPILOT_ALLOW_ALL", "false")
                            .addEnvVar("COPILOT_ASSISTED_APPROVAL", "false")
                            .addEnvVar("GITHUB_COPILOT_ACP_USE_CLI", "1");
                }
                default -> throw new IllegalArgumentException("Unknown ACP agent: " + entry.id());
            }
            return new Launch(parameters.build(), directory);
        } catch (IOException | RuntimeException e) {
            if (directory != null) FileUtil.deleteDirectory(directory.toFile());
            throw new IllegalStateException("Could not configure " + entry.name() + " for unattended play", e);
        }
    }

    private static Map<String, Object> openCodePermissions(List<String> tools) {
        final Map<String, Object> permissions = new LinkedHashMap<>();
        permissions.put("*", "deny");
        tools.forEach(name -> permissions.put("mtg-forge_" + name, "allow"));
        return permissions;
    }

    private static String copilotPlanner(AcpModels models) throws JsonProcessingException {
        return "---\nname: " + COPILOT_PLANNER + "\ndescription: Strategic Magic planning using read-only Forge game tools\n"
                + (models.planningModel().isEmpty() ? "" : "model: " + json(models.planningModel()) + "\nmodelPolicy: required\n")
                + "tools: " + json(ForgeMcpServer.READ_ONLY_TOOL_NAMES.stream().map(name -> "mtg-forge/" + name).toList())
                + "\nstrict-tools-list: true\ninclude-custom-instructions: false\n---\n" + PLANNER_PROMPT + "\n";
    }

    static String copilotLauncher(List<String> registryCommand, boolean windows) {
        return copilotLauncher(registryCommand, windows, null);
    }

    private static String copilotLauncher(List<String> registryCommand, boolean windows, Path plugin) {
        final int pkg = java.util.stream.IntStream.range(0, registryCommand.size())
                .filter(i -> registryCommand.get(i).startsWith("@github/copilot-language-server@"))
                .findFirst().orElseThrow(() -> new IllegalStateException("Unsupported Copilot registry launch recipe"));
        final List<String> command = new ArrayList<>(registryCommand.subList(0, pkg));
        command.add(COPILOT_PACKAGE);
        final List<String> tools = new ArrayList<>(ForgeMcpServer.TOOL_NAMES.stream().map(name -> "mtg-forge-" + name).toList());
        tools.addAll(COPILOT_DELEGATION_TOOLS);
        command.add("--available-tools=" + String.join(",", tools));
        if (plugin != null) command.add("--plugin-dir=" + plugin);
        command.add("--allow-tool=mtg-forge");
        command.add("--disable-builtin-mcps");
        command.add("--no-custom-instructions");
        final String quoted = String.join(" ", command.stream().map(arg -> quote(arg, windows)).toList());
        return windows ? "@echo off\r\ncall " + quoted + " %*\r\n" : "#!/bin/sh\nexec " + quoted + " \"$@\"\n";
    }

    private static String quote(String value, boolean windows) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) throw new IllegalArgumentException("Invalid launch argument");
        if (!windows) return "'" + value.replace("'", "'\"'\"'") + "'";
        if (value.indexOf('"') >= 0) throw new IllegalArgumentException("Invalid Windows launch argument");
        return "\"" + value.replace("%", "%%") + "\"";
    }

    private static String json(Object value) throws JsonProcessingException {
        return JSON.writeValueAsString(value);
    }

    static Map<String, Object> sessionMetadata(String agentId) {
        return sessionMetadata(agentId, AcpModels.defaults().resolve(agentId));
    }

    static Map<String, Object> sessionMetadata(String agentId, AcpModels models) {
        if (!"claude-acp".equals(agentId)) return Map.of();
        final List<String> allowed = new ArrayList<>(ForgeMcpServer.TOOL_NAMES.stream()
                .map(name -> "mcp__mtg-forge__" + name).toList());
        allowed.addAll(CLAUDE_DELEGATION_TOOLS);
        final Map<String, Object> planner = new LinkedHashMap<>();
        planner.put("description", "Consult for strategic Magic planning");
        planner.put("prompt", PLANNER_PROMPT);
        planner.put("tools", ForgeMcpServer.READ_ONLY_TOOL_NAMES.stream().map(name -> "mcp__mtg-forge__" + name).toList());
        planner.put("model", models.planningModel().isEmpty() ? "inherit" : models.planningModel());
        // allowedTools grants approval; tools and strictMcpConfig restrict which tools are exposed.
        return Map.of("claudeCode", Map.of("options", Map.of(
                "tools", CLAUDE_DELEGATION_TOOLS, "strictMcpConfig", true, "settingSources", List.of(), "plugins", List.of(),
                "agents", Map.of(CLAUDE_PLANNER, planner), "disallowedTools", List.of("Agent(fork)"),
                "allowedTools", allowed, "allowDangerouslySkipPermissions", false)));
    }

    static String planningInstructions(String agentId, AcpModels models) {
        final String model = models.planningModel().isEmpty() ? "the parent's model" : models.planningModel();
        return switch (agentId) {
            case AcpAgentRegistry.CODEX -> "Spawn a subagent with fresh context and explicitly select model " + model
                    + " with reasoning effort high. Run it to completion before submitting a decision.";
            case "opencode" -> "Consult the configured " + OPENCODE_PLANNER + " subagent using the task/subagent tool. "
                    + "Its planning model is " + model + ". Run it in the foreground with fresh context and wait for its advice.";
            case "claude-acp" -> "Consult the configured " + CLAUDE_PLANNER + " subagent using Agent (called Task in older versions). "
                    + "Its planning model is " + model + ". Request a foreground run with fresh context and wait for its advice.";
            case "github-copilot" -> "Use task to consult the " + COPILOT_PLANNER + " custom agent from the " + OPENCODE_MODE + " plugin "
                    + "(use its advertised agent_type). "
                    + (models.planningModel().isEmpty() ? "Inherit the parent model. " : "Explicitly select model " + model + ". ")
                    + "Use fresh context and wait for its advice with read_agent.";
            default -> throw new IllegalArgumentException("Unknown ACP agent: " + agentId);
        };
    }

    static void selectModel(AcpSyncClient client, NewSessionResponse session, String agentId, String model) {
        final var selection = "opencode".equals(agentId) ? AcpModels.parseOpenCode(model)
                : new AcpModels.OpenCodeModel(model, "");
        final var option = option(session, "model");
        if (option == null) throw new IllegalStateException(agentId + " does not advertise model selection");
        final var updated = select(client, session.sessionId(), option, selection.model(), agentId);
        if (!selection.effort().isEmpty()) {
            // Model changes can replace the effort choices. Never reuse a previous model's effort options.
            final var effort = option(updated == null ? session.configOptions() : updated.configOptions(), "effort");
            if (effort == null) throw new IllegalStateException(agentId + " does not advertise effort selection for " + selection.model());
            select(client, session.sessionId(), effort, selection.effort(), agentId);
        }
    }

    static void configureSession(AcpSyncClient client, NewSessionResponse session, String agentId) {
        switch (agentId) {
            case "claude-acp" -> selectMode(client, session, "default", agentId);
            case "opencode" -> selectMode(client, session, OPENCODE_MODE, agentId);
            case "github-copilot" -> {
                final var allowAll = option(session, "allow_all");
                if (allowAll != null) select(client, session.sessionId(), allowAll, "off", agentId);
            }
            default -> { }
        }
    }

    private static void selectMode(AcpSyncClient client, NewSessionResponse session, String modeId, String agentId) {
        final var mode = option(session, "mode");
        if (mode != null) {
            select(client, session.sessionId(), mode, modeId, agentId);
        } else if (session.modes() != null && session.modes().availableModes() != null
                && session.modes().availableModes().stream().anyMatch(value -> modeId.equals(value.id()))) {
            if (!modeId.equals(session.modes().currentModeId())
                    && client.setSessionMode(new SetSessionModeRequest(session.sessionId(), modeId)) == null) {
                throw new IllegalStateException(agentId + " did not acknowledge " + modeId + " mode");
            }
        } else {
            throw new IllegalStateException(agentId + " does not offer " + modeId + " mode; unattended play is unavailable");
        }
    }

    private static SessionConfigSelect option(NewSessionResponse session, String id) {
        return option(session.configOptions(), id);
    }

    private static SessionConfigSelect option(List<SessionConfigOption> options, String id) {
        if (options == null) return null;
        return options.stream()
                .filter(value -> value instanceof SessionConfigSelect select && id.equals(select.id()))
                .map(value -> (SessionConfigSelect) value).findFirst().orElse(null);
    }

    private static SetSessionConfigOptionResponse select(AcpSyncClient client, String sessionId, SessionConfigSelect option,
                                                          String value, String agentId) {
        if (value.equals(option.currentValue())) return null;
        if (option.options() == null || option.options().allOptions().stream().noneMatch(item -> value.equals(item.value()))) {
            throw new IllegalStateException(agentId + " does not offer " + option.id() + "=" + value);
        }
        final var response = client.setSessionConfigOption(SetSessionConfigOptionRequest.select(sessionId, option.id(), value));
        if (response == null || response.configOptions() == null || response.configOptions().stream().noneMatch(
                updated -> updated instanceof SessionConfigSelect select
                        && option.id().equals(select.id()) && value.equals(select.currentValue()))) {
            throw new IllegalStateException(agentId + " did not select " + option.id() + "=" + value);
        }
        return response;
    }

    static RequestPermissionResponse approvePermission(String agentId, RequestPermissionRequest request) {
        // Scoped native rules already approve Forge's tools. A remaining ask can be a safety/policy check;
        // do not override it or infer authority from a tool call's human-readable title.
        if (AcpAgentRegistry.CODEX.equals(agentId) && request.options() != null) {
            for (var kind : new PermissionOptionKind[] {PermissionOptionKind.ALLOW_ONCE, PermissionOptionKind.ALLOW_ALWAYS}) {
                for (var option : request.options()) {
                    if (kind.equals(option.kind())) return new RequestPermissionResponse(new PermissionSelected(option.optionId()));
                }
            }
        }
        return new RequestPermissionResponse(new PermissionCancelled());
    }
}
