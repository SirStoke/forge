package forge.ai.llm;

import com.google.common.eventbus.Subscribe;
import forge.game.event.GameEvent;
import forge.game.event.GameEventGameFinished;
import forge.game.player.Player;

import java.util.concurrent.atomic.AtomicReference;

/** Publishes snapshots from game events; MCP readers never access the live game. */
final class GameObserver implements AutoCloseable {
    private final Player viewer;
    private final AtomicReference<Snapshot> current = new AtomicReference<>();
    private volatile boolean closed;

    GameObserver(Player viewer) {
        this.viewer = viewer;
        if (viewer != null) {
            refresh();
            viewer.getGame().subscribeToEvents(this);
        }
    }

    void refresh() {
        if (!closed && viewer != null) {
            current.set(new Snapshot(StackSnapshot.capture(viewer), CardsSnapshot.hand(viewer),
                    CardsSnapshot.battlefield(viewer)));
        }
    }

    Snapshot read() {
        final Snapshot snapshot = current.get();
        if (closed || snapshot == null) {
            throw new IllegalStateException("No active game state is available");
        }
        return snapshot;
    }

    @Subscribe
    public void onGameEvent(GameEvent event) {
        if (event instanceof GameEventGameFinished) {
            close();
        } else {
            refresh();
        }
    }

    @Override
    public void close() {
        closed = true;
        current.set(null);
    }

    record Snapshot(StackSnapshot stack, CardsSnapshot hand, CardsSnapshot battlefield) {
    }
}
