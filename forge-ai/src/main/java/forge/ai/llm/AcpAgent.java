package forge.ai.llm;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentThoughtChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.ContentBlock;
import com.agentclientprotocol.sdk.spec.AcpSchema.Implementation;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpServerHttp;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigSelect;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionNotification;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import forge.util.BuildInfo;
import forge.game.player.Player;
import forge.ai.llm.tools.SubmitSideboardPlan;
import org.tinylog.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** An ACP session with an MCP endpoint for game decisions. */
final class AcpAgent implements AutoCloseable {
    // Defaults; each session captures its own options when it starts.
    static final String MODEL = "gpt-6.1-terra";
    static final String REASONING_EFFORT = "low";

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
    private final AcpAgentSettings.Launch launch;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread shutdownHook;
    private final Set<CompletableFuture<?>> pendingTurns = ConcurrentHashMap.newKeySet();
    private final Object promptQueueLock = new Object();
    private CompletableFuture<Void> promptTail = CompletableFuture.completedFuture(null);

    private AcpAgent(AcpSyncClient client, String sessionId, ForgeMcpServer mcpServer,
                          ConversationLog conversation, Options options, LlmMatchProfile.Session profile,
                          AcpAgentSettings.Launch launch) {
        this.client = client;
        this.sessionId = sessionId;
        this.mcpServer = mcpServer;
        this.conversation = conversation;
        this.options = options;
        this.profile = profile;
        this.launch = launch;
        shutdownHook = new Thread(this::close, "forge-acp-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    static AcpAgent start(Path workingDirectory, Player player) {
        return start(workingDirectory, player, Options.fromSystemProperties());
    }

    static AcpAgent start(Path workingDirectory, Player player, Options options) {
        return start(workingDirectory, player, options, AcpAgentRegistry.get(AcpAgentRegistry.CODEX));
    }

    static AcpAgent start(Path workingDirectory, Player player, String agentId) {
        return start(workingDirectory, player, agentId, AcpModels.defaults());
    }

    static AcpAgent start(Path workingDirectory, Player player, String agentId, AcpModels selection) {
        final var models = selection.resolve(agentId);
        final var options = new Options(models.fastModel().isEmpty() ? "agent default" : models.fastModel(),
                AcpAgentRegistry.CODEX.equals(agentId) ? Options.fromSystemProperties().reasoningEffort() : "agent default");
        return start(workingDirectory, player, options, AcpAgentRegistry.get(agentId), models);
    }

    private static AcpAgent start(Path workingDirectory, Player player, Options options, AcpAgentRegistry.Entry entry) {
        return start(workingDirectory, player, options, entry,
                new AcpModels(options.model(), "").resolve(entry.id()));
    }

    private static AcpAgent start(Path workingDirectory, Player player, Options options, AcpAgentRegistry.Entry entry,
                                  AcpModels models) {
        Objects.requireNonNull(options, "options");
        final String invalid = models.validationError(entry.id());
        if (!invalid.isEmpty()) throw new IllegalArgumentException(invalid);
        if (!entry.available()) throw new IllegalStateException(entry.name() + ": " + entry.unavailableReason());
        final ConversationLog conversation = new ConversationLog();
        final var profile = LlmMatchProfile.session(player);
        profile.configure(options);
        conversation.profile = profile;
        try (var startup = profile.scope("startup", "Start " + entry.name() + " session")) {
            final var launch = AcpAgentSettings.prepareLaunch(entry, models);
            try {
                final StdioAcpClientTransport transport = new StdioAcpClientTransport(launch.parameters());
                final AcpSyncClient client = AcpClient.sync(transport)
                        .clientInfo(new Implementation("mtg-forge", BuildInfo.getVersionString()))
                        .sessionUpdateHandler(conversation::onUpdate)
                        .requestPermissionHandler(request -> AcpAgentSettings.approvePermission(entry.id(), request))
                        // First launch may also need to download the pinned adapter.
                        .requestTimeout(Duration.ofMinutes(2))
                        .promptTimeout(Duration.ofMinutes(2))
                        .build();
                return initialize(client, workingDirectory, conversation, player, options, profile, entry.id(), launch, models);
            } catch (RuntimeException e) {
                launch.close();
                startup.data("outcome", "error");
                throw e;
            }
        }
    }

    static AcpAgent initialize(AcpSyncClient client, Path workingDirectory) {
        return initialize(client, workingDirectory, null);
    }

    static AcpAgent initialize(AcpSyncClient client, Path workingDirectory, Player player) {
        return initialize(client, workingDirectory, player, Options.defaults());
    }

    static AcpAgent initialize(AcpSyncClient client, Path workingDirectory, Player player, Options options) {
        return initialize(client, workingDirectory, new ConversationLog(), player, options);
    }

    static AcpAgent initializeForAgent(AcpSyncClient client, Path workingDirectory, Player player, String agentId) {
        return initializeForAgent(client, workingDirectory, player, agentId, AcpModels.defaults());
    }

    static AcpAgent initializeForAgent(AcpSyncClient client, Path workingDirectory, Player player, String agentId,
                                       AcpModels selection) {
        final var models = selection.resolve(agentId);
        final var profile = LlmMatchProfile.session(player);
        final var options = new Options(models.fastModel().isEmpty() ? "agent default" : models.fastModel(),
                AcpAgentRegistry.CODEX.equals(agentId) ? Options.fromSystemProperties().reasoningEffort() : "agent default");
        profile.configure(options);
        final var conversation = new ConversationLog();
        conversation.profile = profile;
        return initialize(client, workingDirectory, conversation, player, options, profile, agentId, null, models);
    }

    private static AcpAgent initialize(AcpSyncClient client, Path workingDirectory,
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

    private static AcpAgent initialize(AcpSyncClient client, Path workingDirectory,
                                            ConversationLog conversation, Player player, Options options,
                                            LlmMatchProfile.Session profile) {
        return initialize(client, workingDirectory, conversation, player, options, profile, AcpAgentRegistry.CODEX);
    }

    private static AcpAgent initialize(AcpSyncClient client, Path workingDirectory,
                                            ConversationLog conversation, Player player, Options options,
                                            LlmMatchProfile.Session profile, String agentId) {
        return initialize(client, workingDirectory, conversation, player, options, profile, agentId, null,
                AcpAgentRegistry.CODEX.equals(agentId) ? new AcpModels(options.model(), "").resolve(agentId) : AcpModels.defaults());
    }

    private static AcpAgent initialize(AcpSyncClient client, Path workingDirectory,
                                            ConversationLog conversation, Player player, Options options,
                                            LlmMatchProfile.Session profile, String agentId, AcpAgentSettings.Launch launch,
                                            AcpModels models) {
        ForgeMcpServer mcpServer = null;
        AcpAgent agent = null;
        try {
            Objects.requireNonNull(options, "options");
            final String invalid = models.validationError(agentId);
            if (!invalid.isEmpty()) throw new IllegalArgumentException(invalid);
            try (var setup = profile.scope("startup_step", "Start local MCP server")) {
                mcpServer = ForgeMcpServer.start(player, profile);
            }
            try (var setup = profile.scope("startup_step", "Initialize ACP adapter")) {
                final var initialized = client.initialize();
                if (initialized == null || initialized.agentCapabilities() == null
                        || initialized.agentCapabilities().mcpCapabilities() == null
                        || !Boolean.TRUE.equals(initialized.agentCapabilities().mcpCapabilities().http())) {
                    throw new IllegalStateException("ACP agent does not support Forge's HTTP MCP game tools: " + agentId);
                }
            }
            final NewSessionResponse session;
            try (var setup = profile.scope("startup_step", "Create ACP session")) {
                session = client.newSession(new NewSessionRequest(
                        workingDirectory.toAbsolutePath().normalize().toString(),
                        List.of(new McpServerHttp("mtg-forge", mcpServer.getUrl(), List.of())), List.of(),
                        AcpAgentSettings.sessionMetadata(agentId, models)));
            }
            final String sessionId = session.sessionId();
            profile.sessionId(sessionId);
            try (var setup = profile.scope("startup_step", "Configure unattended agent permissions")) {
                AcpAgentSettings.configureSession(client, session, agentId);
            }
            if (AcpAgentRegistry.CODEX.equals(agentId)) {
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
            }
            if (!AcpAgentRegistry.CODEX.equals(agentId) && !models.fastModel().isEmpty()) {
                AcpAgentSettings.selectModel(client, session, agentId, models.fastModel());
            }
            agent = new AcpAgent(client, sessionId, mcpServer, conversation, options, profile, launch);
            agent.prompt(PromptTemplates.load("conversation-start.mustache").execute(Map.of(
                    "planningInstructions", AcpAgentSettings.planningInstructions(agentId, models))), "", "startup", "Session instructions");
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

    CallbackOnce<SubmitSideboardPlan, String> sideboardDecision() {
        return mcpServer.sideboardDecision();
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

    void refreshGameState() {
        mcpServer.refreshGameState();
    }

    void prompt(String text) {
        prompt(text, "", "other", "Agent prompt");
    }

    void prompt(String text, String requestId, String decisionType, String title) {
        prompt(text, requestId, decisionType, title, null);
    }

    /** Wait for a submitted decision or an unanswered/failed turn, whichever arrives first. */
    void prompt(String text, String requestId, String decisionType, String title, CompletableFuture<?> decision) {
        if (closed.get()) throw new IllegalStateException("ACP session is closed");
        try (var request = profile.beginRequest(requestId, decisionType, title, text)) {
            try {
                try (var preparation = profile.scope("preparation", "Refresh game observations")) {
                    mcpServer.refreshGameState();
                    conversation.flush();
                    ConversationLog.log(sessionId, "USER", text);
                }
                try (var wait = profile.scope("agent_wait", "Agent / transport wait")) {
                    if (decision == null) {
                        try {
                            queuePrompt(text, requestId, true).join();
                        } catch (CompletionException e) {
                            if (e.getCause() instanceof RuntimeException cause) throw cause;
                            throw e;
                        }
                    } else {
                        final var turn = queuePrompt(text, requestId, false);
                        try {
                            CompletableFuture.anyOf(decision, turn).join();
                        } catch (CompletionException e) {
                            // A transport failure must not discard a choice already accepted by the tool.
                            if (!decision.isDone()) throw e;
                        }
                    }
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

    /** Buffer transport requests, while callers can still return as soon as an MCP decision is accepted. */
    private CompletableFuture<PromptResponse> queuePrompt(String text, String requestId, boolean synchronous) {
        final var turn = new CompletableFuture<PromptResponse>();
        final var finished = new CompletableFuture<Void>();
        final var transport = new AtomicReference<CompletableFuture<PromptResponse>>();
        final CompletableFuture<Void> previous;
        synchronized (promptQueueLock) {
            if (closed.get()) throw new IllegalStateException("ACP session is closed");
            previous = promptTail;
            promptTail = finished;
            pendingTurns.add(turn);
        }
        turn.whenComplete((result, error) -> {
            try {
                final var active = transport.get();
                if (turn.isCancelled() && active != null) active.cancel(true);
                conversation.flush();
                if (error != null && !synchronous && !closed.get()) {
                    Logger.warn(error, "ACP turn failed for request {}", requestId);
                }
            } finally {
                pendingTurns.remove(turn);
                // A failed response releases the queue too; it must not discard later prompts.
                finished.complete(null);
            }
        });
        // Dispatch outside the response reader and queue lock, including when earlier turns completed inline.
        previous.whenCompleteAsync((ignored, previousError) -> {
            if (closed.get() || turn.isDone()) {
                turn.cancel(true);
                return;
            }
            try {
                final var request = PromptRequest.text(sessionId, text);
                final var active = synchronous ? CompletableFuture.completedFuture(client.prompt(request))
                        : client.async().prompt(request).toFuture();
                transport.set(active);
                active.whenComplete((result, error) -> {
                    if (error == null) turn.complete(result);
                    else turn.completeExceptionally(error);
                });
                // close() can cancel the buffered turn before its transport future has been assigned.
                if (turn.isCancelled()) active.cancel(true);
            } catch (Throwable e) {
                turn.completeExceptionally(e);
            }
        });
        return turn;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            try {
                pendingTurns.forEach(turn -> turn.cancel(true));
                client.close();
            } finally {
                try {
                    conversation.flush();
                } finally {
                    try {
                        mcpServer.close();
                    } finally {
                        if (launch != null) launch.close();
                    }
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
