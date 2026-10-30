package forge.ai.llm;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** One pending callback, removed atomically before invocation on the caller's thread. */
public final class CallbackOnce<I, O> {
    private final AtomicReference<Pending<I, O>> pending = new AtomicReference<>();

    /** Register a callback without overwriting an unanswered request. */
    public String register(Callback<I, O> callback) {
        final var request = new Pending<I, O>(UUID.randomUUID().toString(), Objects.requireNonNull(callback));
        if (!pending.compareAndSet(null, request)) {
            throw new IllegalStateException("A callback is already pending");
        }
        return request.id();
    }

    /** Consume the matching request at most once, even if its callback throws. */
    public O call(String requestId, I input) {
        final var request = pending.get();
        if (request == null || !request.id().equals(requestId) || !pending.compareAndSet(request, null)) {
            throw new IllegalStateException("No pending callback for request: " + requestId);
        }
        return request.callback().call(input);
    }

    /** Cancel only the matching request; a callback already claimed by call() will still run. */
    public boolean cancel(String requestId) {
        final var request = pending.get();
        return request != null && request.id().equals(requestId) && pending.compareAndSet(request, null);
    }

    private record Pending<I, O>(String id, Callback<I, O> callback) {
    }
}
