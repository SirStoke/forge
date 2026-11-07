package forge.ai.llm;

import com.google.common.eventbus.Subscribe;
import forge.game.Game;
import forge.game.event.GameEventGameFinished;
import forge.game.event.GameEventTurnBegan;
import forge.game.event.GameEventTurnPhase;
import forge.game.player.Player;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.tinylog.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Opt-in wall-clock tracing. IO failures disable recording, never gameplay. */
final class LlmMatchProfile {
    private static final Map<Game, LlmMatchProfile> MATCHES = new WeakHashMap<>();
    private static final LlmMatchProfile DISABLED = new LlmMatchProfile();
    private static final Span NO_SPAN = new Span();
    private final long origin = System.nanoTime();
    private final AtomicLong ids = new AtomicLong();
    private BufferedWriter writer;
    private boolean closed;
    private Thread shutdownHook;
    private Path path;

    private LlmMatchProfile() { }

    /** Capture the flag once per real game, shared by every LLM seat. Weak keys also cover abandoned games. */
    static synchronized LlmMatchProfile forGame(Game game) {
        if (game == null || game.isSimulationCopy()) return DISABLED;
        return MATCHES.computeIfAbsent(game, current -> {
            if (!Boolean.getBoolean("forge.llm.profile")) return DISABLED;
            final var profile = new LlmMatchProfile();
            profile.open(current);
            return profile;
        });
    }

    Path path() { return path; }

    static Session session(Player player) {
        return new Session(player == null ? DISABLED : forGame(player.getGame()), player);
    }

    private void open(Game game) {
        try {
            final Path directory = Path.of("logs", "profiles").toAbsolutePath();
            Files.createDirectories(directory);
            path = directory.resolve("match-" + Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID() + ".jsonl");
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            event("match_start", Map.of("format", "forge.llm.profile", "version", 1,
                    "gameId", game.getId(), "startedAt", Instant.now().toString(),
                    "players", game.getPlayers().stream().map(p -> Map.of("id", p.getId(), "name", p.getName())).toList()));
            if (!enabled()) return;
            writer.flush();
            shutdownHook = new Thread(() -> finish("application_exit"), "forge-llm-profile-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            game.subscribeToEvents(this);
            Logger.info("LLM match profile: {}", path);
        } catch (IOException | RuntimeException e) {
            disable(e);
        }
    }

    private synchronized boolean enabled() {
        return writer != null && !closed;
    }

    private double millis(long nanoTime) {
        return (nanoTime - origin) / 1_000_000.0;
    }

    private synchronized void event(String type, Map<String, ?> data) {
        if (!enabled()) return;
        final var record = new LinkedHashMap<String, Object>();
        record.put("event", type);
        record.put("atMs", millis(System.nanoTime()));
        record.putAll(data);
        try {
            writer.write(McpJsonDefaults.getMapper().writeValueAsString(record));
            writer.newLine();
        } catch (IOException | RuntimeException e) {
            disable(e);
        }
    }

    private synchronized void flush() {
        if (!enabled()) return;
        try { writer.flush(); }
        catch (IOException e) { disable(e); }
    }

    private synchronized void disable(Exception cause) {
        closed = true;
        if (writer != null) {
            try { writer.close(); } catch (IOException ignored) { }
            writer = null;
        }
        removeShutdownHook();
        Logger.warn(cause, "Could not record LLM match profile {}", path);
    }

    @Subscribe
    public void onTurn(GameEventTurnBegan event) {
        event("turn", Map.of("turn", event.turnNumber(), "turnPlayer", event.turnOwner().getName()));
    }

    @Subscribe
    public void onPhase(GameEventTurnPhase event) {
        event("phase", Map.of("phase", event.phase().name(), "turnPlayer", event.playerTurn().getName()));
    }

    @Subscribe
    public void onFinish(GameEventGameFinished event) {
        finish("game_finished");
    }

    private synchronized void finish(String reason) {
        if (!enabled()) return;
        event("match_end", Map.of("reason", reason));
        if (!enabled()) return;
        try { writer.close(); }
        catch (IOException e) { Logger.warn(e, "Could not close LLM match profile {}", path); }
        finally {
            closed = true;
            writer = null;
            removeShutdownHook();
        }
    }

    private void removeShutdownHook() {
        if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException ignored) { }
        }
    }

    /** Game-thread scopes; only the frozen active request is shared with ACP/MCP threads. */
    static final class Session {
        private final LlmMatchProfile profile;
        private final WeakReference<Player> player;
        private final ArrayDeque<Span> scopes = new ArrayDeque<>();
        private volatile Request request;
        private volatile String sessionId = "";
        private String model = "";
        private String reasoningEffort = "";
        private Span action;

        private Session(LlmMatchProfile profile, Player player) {
            this.profile = profile;
            this.player = new WeakReference<>(player);
        }

        void configure(AcpAgent.Options options) {
            model = options.model();
            reasoningEffort = options.reasoningEffort();
        }

        void sessionId(String id) {
            sessionId = id;
            for (Span span : scopes) span.data("sessionId", id);
        }

        boolean enabled() { return profile.enabled(); }

        Span scope(String kind, String name) {
            if (!profile.enabled()) return NO_SPAN;
            final var data = context();
            final Span parent = scopes.peek();
            data.put("parentId", parent == null ? "" : parent.id);
            final var span = new Span(profile, kind, name, data, scopes::remove);
            scopes.push(span);
            return span;
        }

        private Map<String, Object> context() {
            final var data = new LinkedHashMap<String, Object>();
            data.put("sessionId", sessionId);
            data.put("model", model);
            data.put("reasoningEffort", reasoningEffort);
            final Player current = player.get();
            if (current != null) {
                data.put("playerId", current.getId());
                data.put("player", current.getName());
                final var phase = current.getGame().getPhaseHandler();
                data.put("turn", phase.getTurn());
                data.put("phase", phase.getPhase() == null ? "" : phase.getPhase().name());
            }
            return data;
        }

        void beginAction(String name) {
            endAction("superseded");
            action = scope("action", name);
        }

        void actionName(String name) {
            if (action != null) action.data("name", name);
        }

        void endAction(String outcome) {
            if (action == null) return;
            action.data("outcome", outcome).close();
            action = null;
        }

        Request beginRequest(String id, String kind, String title, String text) {
            final var span = scope("request", title);
            span.data("requestId", id).data("decisionType", kind)
                    .data("outcome", id.isEmpty() ? "completed" : "unanswered");
            if (profile.enabled()) span.data("promptBytes", text.getBytes(StandardCharsets.UTF_8).length);
            final var active = new Request(this, span, id);
            request = active;
            return active;
        }

        void firstActivity() {
            final Request active = request;
            if (active != null) active.firstActivity();
        }

        Span tool(String name) {
            final Request active = request;
            if (active == null || !profile.enabled()) return NO_SPAN;
            active.tools.incrementAndGet();
            final var data = new LinkedHashMap<>(active.span.context);
            data.put("sessionId", sessionId);
            data.put("parentId", active.span.id);
            data.put("requestId", active.id);
            return new Span(profile, "tool", name, data, ignored -> { });
        }

        void accepted(String id, String tool) {
            final Request active = request;
            if (active != null && active.id.equals(id)) active.accept(tool);
        }

        /** Validated callbacks record acceptance before waking the game thread and closing its request. */
        void accepted(String tool) {
            final Request active = request;
            if (active != null) active.accept(tool);
        }

        void fallback(String title) {
            if (!profile.enabled()) return;
            final var data = context();
            data.put("name", title);
            data.put("parentId", scopes.isEmpty() ? "" : scopes.peek().id);
            profile.event("fallback", data);
        }
    }

    static final class Request implements AutoCloseable {
        private final Session session;
        private final Span span;
        private final String id;
        private final AtomicLong accepted = new AtomicLong();
        private final AtomicLong firstActivity = new AtomicLong();
        private final AtomicLong tools = new AtomicLong();

        private Request(Session session, Span span, String id) {
            this.session = session;
            this.span = span;
            this.id = id;
        }

        private void firstActivity() {
            if (span == NO_SPAN) return;
            firstActivity.compareAndSet(0, System.nanoTime());
        }

        private void accept(String tool) {
            if (span == NO_SPAN) return;
            final long now = System.nanoTime();
            if (!accepted.compareAndSet(0, now)) return;
            session.profile.event("decision_accepted", Map.of("spanId", span.id, "requestId", id, "tool", tool));
            span.data("outcome", tool);
        }

        void failed() { span.data("outcome", "error"); }

        void sessionId(String id) {
            session.sessionId = id;
            span.data("sessionId", id);
        }

        @Override
        public void close() {
            if (span == NO_SPAN) { session.request = null; return; }
            final long end = System.nanoTime();
            if (accepted.get() != 0) {
                span.data("decisionMs", (accepted.get() - span.start) / 1_000_000.0);
                span.data("afterDecisionMs", (end - accepted.get()) / 1_000_000.0);
            }
            if (firstActivity.get() != 0) span.data("firstActivityMs", (firstActivity.get() - span.start) / 1_000_000.0);
            span.data("toolCount", tools.get());
            session.request = null;
            span.close();
            session.profile.flush();
        }
    }

    static final class Span implements AutoCloseable {
        private final LlmMatchProfile profile;
        private final String id;
        private final long start;
        private final Map<String, Object> context;
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private final Consumer<Span> onClose;
        private boolean closed;

        private Span() {
            profile = DISABLED;
            id = "";
            start = 0;
            context = Map.of();
            onClose = ignored -> { };
        }

        private Span(LlmMatchProfile profile, String kind, String name, Map<String, Object> context, Consumer<Span> onClose) {
            this.profile = profile;
            this.id = Long.toString(profile.ids.incrementAndGet());
            this.start = System.nanoTime();
            this.context = context;
            this.onClose = onClose;
            final var data = new LinkedHashMap<>(context);
            data.put("id", id);
            data.put("kind", kind);
            data.put("name", name);
            data.put("startMs", profile.millis(start));
            profile.event("span_start", data);
        }

        synchronized Span data(String key, Object value) {
            if (this != NO_SPAN) attributes.put(key, value);
            return this;
        }

        @Override
        public synchronized void close() {
            if (this == NO_SPAN || closed) return;
            closed = true;
            final var data = new LinkedHashMap<>(attributes);
            data.put("id", id);
            data.put("durationMs", (System.nanoTime() - start) / 1_000_000.0);
            profile.event("span_end", data);
            onClose.accept(this);
        }
    }
}
