package forge.ai.llm;

import com.google.common.eventbus.Subscribe;
import forge.game.event.GameEvent;
import forge.game.event.GameEventGameFinished;
import forge.game.player.Player;

import java.util.concurrent.atomic.AtomicReference;

/** Publishes snapshots from game events; MCP readers never access the live game. */
final class StackObserver implements AutoCloseable {
    private final Player viewer;
    private final AtomicReference<StackSnapshot> current = new AtomicReference<>();
    private volatile boolean closed;

    StackObserver(Player viewer) {
        this.viewer = viewer;
        if (viewer != null) {
            refresh();
            viewer.getGame().subscribeToEvents(this);
        }
    }

    void refresh() {
        if (!closed && viewer != null) {
            final StackSnapshot previous = current.get();
            if (previous == null || !previous.items().isEmpty() || !viewer.getGame().getStack().isEmpty()) {
                current.set(StackSnapshot.capture(viewer));
            }
        }
    }

    StackSnapshot read() {
        final StackSnapshot snapshot = current.get();
        if (closed || snapshot == null) {
            throw new IllegalStateException("No active game stack is available");
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
}
