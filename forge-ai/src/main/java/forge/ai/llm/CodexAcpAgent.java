package forge.ai.llm;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema.Implementation;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpServerHttp;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigSelect;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionRequest;
import forge.util.BuildInfo;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** An initialized Codex session with an empty MCP endpoint; no prompts are sent yet. */
final class CodexAcpAgent implements AutoCloseable {
    static final String MODEL = "gpt-6.1-sol";
    private static final String ADAPTER = "@agentclientprotocol/codex-acp@2.4.0";

    private final AcpSyncClient client;
    private final String sessionId;
    private final ForgeMcpServer mcpServer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread shutdownHook;

    private CodexAcpAgent(AcpSyncClient client, String sessionId, ForgeMcpServer mcpServer) {
        this.client = client;
        this.sessionId = sessionId;
        this.mcpServer = mcpServer;
        shutdownHook = new Thread(this::close, "forge-codex-acp-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    static CodexAcpAgent start(Path workingDirectory) {
        final AgentParameters parameters = AgentParameters.builder("pnpm")
                .args("dlx", ADAPTER)
                .addEnvVar("NO_BROWSER", "1")
                .build();
        final StdioAcpClientTransport transport = new StdioAcpClientTransport(parameters);
        final AcpSyncClient client = AcpClient.sync(transport)
                .clientInfo(new Implementation("mtg-forge", BuildInfo.getVersionString()))
                // First launch may also need to download the pinned adapter.
                .requestTimeout(Duration.ofMinutes(2))
                .build();
        return initialize(client, workingDirectory);
    }

    static CodexAcpAgent initialize(AcpSyncClient client, Path workingDirectory) {
        ForgeMcpServer mcpServer = null;
        try {
            mcpServer = ForgeMcpServer.start();
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
            return new CodexAcpAgent(client, sessionId, mcpServer);
        } catch (RuntimeException e) {
            try {
                client.close();
            } finally {
                if (mcpServer != null) {
                    mcpServer.close();
                }
            }
            throw e;
        }
    }

    String getSessionId() {
        return sessionId;
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
                mcpServer.close();
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
}
