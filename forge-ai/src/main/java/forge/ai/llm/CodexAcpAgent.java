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
    static final String MODEL = "gpt-6.1-sol";
    private static final String ADAPTER = "@agentclientprotocol/codex-acp@2.4.0";

    private final AcpSyncClient client;
    private final String sessionId;
    private final ForgeMcpServer mcpServer;
    private final ConversationLog conversation;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread shutdownHook;

    private CodexAcpAgent(AcpSyncClient client, String sessionId, ForgeMcpServer mcpServer,
                          ConversationLog conversation) {
        this.client = client;
        this.sessionId = sessionId;
        this.mcpServer = mcpServer;
        this.conversation = conversation;
        shutdownHook = new Thread(this::close, "forge-codex-acp-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    static CodexAcpAgent start(Path workingDirectory, Player player) {
        final ConversationLog conversation = new ConversationLog();
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
        return initialize(client, workingDirectory, conversation, player);
    }

    static CodexAcpAgent initialize(AcpSyncClient client, Path workingDirectory) {
        return initialize(client, workingDirectory, null);
    }

    static CodexAcpAgent initialize(AcpSyncClient client, Path workingDirectory, Player player) {
        return initialize(client, workingDirectory, new ConversationLog(), player);
    }

    private static CodexAcpAgent initialize(AcpSyncClient client, Path workingDirectory,
                                            ConversationLog conversation, Player player) {
        ForgeMcpServer mcpServer = null;
        CodexAcpAgent agent = null;
        try {
            mcpServer = ForgeMcpServer.start(player);
            client.initialize();
            final String sessionId = client.newSession(new NewSessionRequest(
                    workingDirectory.toAbsolutePath().normalize().toString(),
                    List.of(new McpServerHttp("mtg-forge", mcpServer.getUrl(), List.of())))).sessionId();
            final boolean modelSelected = client.setSessionConfigOption(
                    SetSessionConfigOptionRequest.select(sessionId, "model", MODEL))
                    .configOptions().stream().anyMatch(option -> option instanceof SessionConfigSelect select
                            && "model".equals(select.id()) && MODEL.equals(select.currentValue()));
            if (!modelSelected) {
                throw new IllegalStateException("Codex did not select the requested model: " + MODEL);
            }
            agent = new CodexAcpAgent(client, sessionId, mcpServer, conversation);
            agent.prompt(PromptTemplates.load("conversation-start.mustache").execute(Map.of()));
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

    String getSessionId() {
        return sessionId;
    }

    CallbackOnce<Boolean, String> mulliganDecision() {
        return mcpServer.mulliganDecision();
    }

    void prompt(String text) {
        mcpServer.refreshStack();
        conversation.flush();
        ConversationLog.log(sessionId, "USER", text);
        try {
            client.prompt(PromptRequest.text(sessionId, text));
        } finally {
            // Preserve partial output even if the prompt fails or times out.
            conversation.flush();
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
        private String sessionId;
        private String role;
        private String messageId;

        synchronized void onUpdate(SessionNotification notification) {
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
