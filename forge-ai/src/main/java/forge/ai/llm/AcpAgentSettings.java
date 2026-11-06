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
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModeRequest;
import org.tinylog.Logger;

import java.util.Map;

/** Harness-specific controls for unattended game sessions, without changing the user's saved settings. */
final class AcpAgentSettings {
    private AcpAgentSettings() { }

    static AgentParameters launchParameters(AcpAgentRegistry.Entry entry) {
        final var parameters = AgentParameters.builder(entry.command().get(0))
                .args(entry.command().subList(1, entry.command().size()))
                .env(entry.environment());
        switch (entry.id()) {
            case AcpAgentRegistry.CODEX -> parameters.addEnvVar("NO_BROWSER", "1")
                    .addEnvVar("INITIAL_AGENT_MODE", "agent-full-access");
            case "opencode" -> parameters.addEnvVar("OPENCODE_PERMISSION", "{\"*\":\"allow\"}");
            // The language-server adapter inherits this environment into its Copilot CLI subprocess.
            case "github-copilot" -> parameters.addEnvVar("COPILOT_ALLOW_ALL", "true")
                    .addEnvVar("GITHUB_COPILOT_ACP_USE_CLI", "1");
            default -> { }
        }
        return parameters.build();
    }

    static Map<String, Object> sessionMetadata(String agentId) {
        if (!"claude-acp".equals(agentId)) return Map.of();
        // permissionMode is managed by ACP; setting it in SDK options would be ignored by the adapter.
        return Map.of("claudeCode", Map.of("options", Map.of(
                "allowDangerouslySkipPermissions", true, "sandbox", Map.of("enabled", false))));
    }

    static void configureSession(AcpSyncClient client, NewSessionResponse session, String agentId) {
        switch (agentId) {
            case "claude-acp" -> {
                final var mode = option(session, "mode");
                if (mode != null) {
                    select(client, session.sessionId(), mode, "bypassPermissions", agentId);
                } else if (session.modes() != null && session.modes().availableModes() != null
                        && session.modes().availableModes().stream().anyMatch(value -> "bypassPermissions".equals(value.id()))) {
                    if (!"bypassPermissions".equals(session.modes().currentModeId())
                            && client.setSessionMode(new SetSessionModeRequest(session.sessionId(), "bypassPermissions")) == null) {
                        throw new IllegalStateException("Claude did not acknowledge bypassPermissions mode");
                    }
                } else {
                    throw new IllegalStateException("Claude does not offer bypassPermissions mode; unattended play is unavailable");
                }
            }
            case "github-copilot" -> {
                final var allowAll = option(session, "allow_all");
                if (allowAll != null) select(client, session.sessionId(), allowAll, "on", agentId);
                else Logger.warn("Copilot did not advertise allow_all; relying on launch configuration and ACP permission approvals");
            }
            default -> { }
        }
    }

    private static SessionConfigSelect option(NewSessionResponse session, String id) {
        if (session.configOptions() == null) return null;
        return session.configOptions().stream()
                .filter(value -> value instanceof SessionConfigSelect select && id.equals(select.id()))
                .map(value -> (SessionConfigSelect) value).findFirst().orElse(null);
    }

    private static void select(AcpSyncClient client, String sessionId, SessionConfigSelect option, String value, String agentId) {
        if (value.equals(option.currentValue())) return;
        if (option.options() == null || option.options().allOptions().stream().noneMatch(item -> value.equals(item.value()))) {
            throw new IllegalStateException(agentId + " does not offer " + option.id() + "=" + value);
        }
        final var response = client.setSessionConfigOption(SetSessionConfigOptionRequest.select(sessionId, option.id(), value));
        if (response == null || response.configOptions() == null || response.configOptions().stream().noneMatch(
                updated -> updated instanceof SessionConfigSelect select
                        && option.id().equals(select.id()) && value.equals(select.currentValue()))) {
            throw new IllegalStateException(agentId + " did not select " + option.id() + "=" + value);
        }
    }

    static RequestPermissionResponse approvePermission(RequestPermissionRequest request) {
        if (request.options() != null) {
            // Prefer a single-call approval so the harness doesn't persist a new permission rule.
            for (var kind : new PermissionOptionKind[] {PermissionOptionKind.ALLOW_ONCE, PermissionOptionKind.ALLOW_ALWAYS}) {
                for (var option : request.options()) {
                    if (kind.equals(option.kind())) return new RequestPermissionResponse(new PermissionSelected(option.optionId()));
                }
            }
        }
        return new RequestPermissionResponse(new PermissionCancelled());
    }
}
