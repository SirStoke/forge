package forge.ai.llm;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentThoughtChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.ContentBlock;
import com.agentclientprotocol.sdk.spec.AcpSchema.Implementation;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpServerHttp;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigSelect;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionNotification;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import forge.util.BuildInfo;
import forge.game.player.Player;
import org.tinylog.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** A Codex session with an MCP endpoint for game decisions. */
final class CodexAcpAgent implements AutoCloseable {
    // Defaults; each session captures its own options when it starts.
    static final String MODEL = "gpt-6.1-terra";
    static final String REASONING_EFFORT = "low";
    private static final String ADAPTER = "@agentclientprotocol/codex-acp@2.4.0";

    record Options(String model, String reasoningEffort) {
        Options {
            if (model == null || model.isBlank()) throw new IllegalArgumentException("Model must not be blank");
            if (reasoningEffort == null || reasoningEffort.isBlank()) {
                throw new IllegalArgumentException("Reasoning effort must not be blank");
            }
        }

        static Options defaults() {
            return new Options(MODEL, REASONING_EFFORT);
        }

        static Options fromSystemProperties() {
            return new Options(System.getProperty("forge.llm.model", MODEL),
                    System.getProperty("forge.llm.reasoningEffort", REASONING_EFFORT));
        }
    }

    private final AcpSyncClient client;
    private final String sessionId;
    private final ForgeMcpServer mcpServer;
    private final ConversationLog conversation;
    private final Options options;
    private final LlmMatchProfile.Session profile;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread shutdownHook;

    private CodexAcpAgent(AcpSyncClient client, String sessionId, ForgeMcpServer mcpServer,
                          ConversationLog conversation, Options options, LlmMatchProfile.Session profile) {
        this.client = client;
        this.sessionId = sessionId;
        this.mcpServer = mcpServer;
        this.conversation = conversation;
        this.options = options;
        this.profile = profile;
        shutdownHook = new Thread(this::close, "forge-codex-acp-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    static CodexAcpAgent start(Path workingDirectory, Player player) {
        return start(workingDirectory, player, Options.fromSystemProperties());
    }

    static CodexAcpAgent start(Path workingDirectory, Player player, Options options) {
        Objects.requireNonNull(options, "options");
        final ConversationLog conversation = new ConversationLog();
        final var profile = LlmMatchProfile.session(player);
        profile.configure(options);
        conversation.profile = profile;
        try (var startup = profile.scope("startup", "Start Codex session")) {
            try {
                final AgentParameters parameters = AgentParameters.builder("pnpm")
                        .args("dlx", ADAPTER)
                        .addEnvVar("NO_BROWSER", "1")
                        .build();
                final StdioAcpClientTransport transport = new StdioAcpClientTransport(parameters);
                final AcpSyncClient client = AcpClient.sync(transport)
                        .clientInfo(new Implementation("mtg-forge", BuildInfo.getVersionString()))
                        .sessionUpdateHandler(conversation::onUpdate)
                        // First launch may also need to download the pinned adapter.
                        .requestTimeout(Duration.ofMinutes(2))
                        .promptTimeout(Duration.ofMinutes(2))
                        .build();
                return initialize(client, workingDirectory, conversation, player, options, profile);
            } catch (RuntimeException e) {
                startup.data("outcome", "error");
                throw e;
            }
        }
    }

    static CodexAcpAgent initialize(AcpSyncClient client, Path workingDirectory) {
        return initialize(client, workingDirectory, null);
    }

    static CodexAcpAgent initialize(AcpSyncClient client, Path workingDirectory, Player player) {
        return initialize(client, workingDirectory, player, Options.defaults());
    }

    static CodexAcpAgent initialize(AcpSyncClient client, Path workingDirectory, Player player, Options options) {
        return initialize(client, workingDirectory, new ConversationLog(), player, options);
    }

    private static CodexAcpAgent initialize(AcpSyncClient client, Path workingDirectory,
                                            ConversationLog conversation, Player player, Options options) {
        final var profile = LlmMatchProfile.session(player);
        profile.configure(options);
        conversation.profile = profile;
        try (var startup = profile.scope("startup", "Start Codex session")) {
            try {
                return initialize(client, workingDirectory, conversation, player, options, profile);
            } catch (RuntimeException e) {
                startup.data("outcome", "error");
                throw e;
            }
        }
    }

    private static CodexAcpAgent initialize(AcpSyncClient client, Path workingDirectory,
                                            ConversationLog conversation, Player player, Options options,
                                            LlmMatchProfile.Session profile) {
        ForgeMcpServer mcpServer = null;
        CodexAcpAgent agent = null;
        try {
            Objects.requireNonNull(options, "options");
            try (var setup = profile.scope("startup_step", "Start local MCP server")) {
                mcpServer = ForgeMcpServer.start(player, profile);
            }
            try (var setup = profile.scope("startup_step", "Initialize ACP adapter")) {
                client.initialize();
            }
            final String sessionId;
            try (var setup = profile.scope("startup_step", "Create ACP session")) {
                sessionId = client.newSession(new NewSessionRequest(
                        workingDirectory.toAbsolutePath().normalize().toString(),
                        List.of(new McpServerHttp("mtg-forge", mcpServer.getUrl(), List.of())))).sessionId();
            }
            profile.sessionId(sessionId);
            final boolean modelSelected = client.setSessionConfigOption(
                    SetSessionConfigOptionRequest.select(sessionId, "model", options.model()))
                    .configOptions().stream().anyMatch(option -> option instanceof SessionConfigSelect select
                            && "model".equals(select.id()) && options.model().equals(select.currentValue()));
            if (!modelSelected) {
                throw new IllegalStateException("Codex did not select the requested model: " + options.model());
            }
            final boolean reasoningSelected = client.setSessionConfigOption(
                    SetSessionConfigOptionRequest.select(sessionId, "reasoning_effort", options.reasoningEffort()))
                    .configOptions().stream().anyMatch(option -> option instanceof SessionConfigSelect select
                            && "reasoning_effort".equals(select.id()) && options.reasoningEffort().equals(select.currentValue()));
            if (!reasoningSelected) {
                throw new IllegalStateException("Codex did not select the requested reasoning effort: " + options.reasoningEffort());
            }
            agent = new CodexAcpAgent(client, sessionId, mcpServer, conversation, options, profile);
            agent.prompt(PromptTemplates.load("conversation-start.mustache").execute(Map.of()), "", "startup", "Session instructions");
            return agent;
        } catch (RuntimeException e) {
            if (agent != null) {
                agent.close();
            } else {
                try {
                    client.close();
                } finally {
                    if (mcpServer != null) {
                        mcpServer.close();
                    }
                }
            }
            throw e;
        }
    }

    LlmMatchProfile.Session profile() {
        return profile;
    }

    String getSessionId() {
        return sessionId;
    }

    Options options() {
        return options;
    }

    CallbackOnce<Boolean, String> mulliganDecision() {
        return mcpServer.mulliganDecision();
    }

    CallbackOnce<PriorityDecision, String> priorityDecision() {
        return mcpServer.priorityDecision();
    }

    CallbackOnce<ChoiceDecision, String> choiceDecision() {
        return mcpServer.choiceDecision();
    }

    void setPriorityActions(PriorityActionsSnapshot snapshot) {
        mcpServer.setPriorityActions(snapshot);
    }

    void clearPriorityActions(String requestId) {
        mcpServer.clearPriorityActions(requestId);
    }

    void prompt(String text) {
        prompt(text, "", "other", "Agent prompt");
    }

    void prompt(String text, String requestId, String decisionType, String title) {
        try (var request = profile.beginRequest(requestId, decisionType, title, text)) {
            try {
                try (var preparation = profile.scope("preparation", "Refresh game observations")) {
                    mcpServer.refreshGameState();
                    conversation.flush();
                    ConversationLog.log(sessionId, "USER", text);
                }
                try (var wait = profile.scope("agent_wait", "Agent / transport wait")) {
                    client.prompt(PromptRequest.text(sessionId, text));
                }
            } catch (RuntimeException e) {
                request.failed();
                throw e;
            } finally {
                // Preserve partial output even if the prompt fails or times out.
                conversation.flush();
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            try {
                client.close();
            } finally {
                try {
                    conversation.flush();
                } finally {
                    mcpServer.close();
                }
            }
        } finally {
            if (Thread.currentThread() != shutdownHook) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // The JVM is already shutting down.
                }
            }
        }
    }

    /** Collect streamed text without logging the ACP protocol envelopes. */
    static final class ConversationLog {
        private final StringBuilder text = new StringBuilder();
        private volatile LlmMatchProfile.Session profile;
        private String sessionId;
        private String role;
        private String messageId;

        synchronized void onUpdate(SessionNotification notification) {
            if (profile != null && (notification.update() instanceof AgentMessageChunk
                    || notification.update() instanceof AgentThoughtChunk)) profile.firstActivity();
            if (notification.update() instanceof AgentMessageChunk chunk) {
                append(notification.sessionId(), "AGENT", chunk.messageId(), chunk.content());
            } else if (notification.update() instanceof AgentThoughtChunk chunk) {
                append(notification.sessionId(), "AGENT THOUGHT", chunk.messageId(), chunk.content());
            }
            // prompt() logs outgoing text as USER through this same log() method and
            // LLM_CONVERSATION tag. Ignore user-message echoes to avoid duplicate entries.
        }

        private void append(String sessionId, String role, String messageId, ContentBlock content) {
            if (!(content instanceof TextContent chunk)) {
                return;
            }
            if (!Objects.equals(this.sessionId, sessionId) || !Objects.equals(this.role, role)
                    || !Objects.equals(this.messageId, messageId)) {
                flush();
            }
            this.sessionId = sessionId;
            this.role = role;
            this.messageId = messageId;
            text.append(chunk.text());
        }

        synchronized void flush() {
            if (!text.isEmpty()) {
                log(sessionId, role, text.toString());
                text.setLength(0);
            }
        }

        private static void log(String sessionId, String role, String text) {
            Logger.tag("LLM_CONVERSATION").info("[ACP {}] {}:\n{}", sessionId, role, text);
        }
    }
}
