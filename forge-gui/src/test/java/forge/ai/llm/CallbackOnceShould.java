package forge.ai.llm;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class CallbackOnceShould {
    @Test
    public void preserveThePendingCallbackWhenRegistrationOrAnOldRequestIsRejected() {
        final var callback = new CallbackOnce<String, Integer>();
        final String first = callback.register(String::length);
        Assert.expectThrows(IllegalStateException.class, () -> callback.register(value -> 0));
        Assert.assertEquals(callback.call(first, "hello"), Integer.valueOf(5));
        Assert.expectThrows(IllegalStateException.class, () -> callback.call(first, "again"));

        final String second = callback.register(String::length);
        Assert.expectThrows(IllegalStateException.class, () -> callback.call(first, "stale"));
        Assert.assertFalse(callback.cancel(first));
        Assert.assertEquals(callback.call(second, "next"), Integer.valueOf(4));
    }

    @Test
    public void cancelWithoutInvokingTheCallback() {
        final var callback = new CallbackOnce<Void, String>();
        final String request = callback.register(input -> {
            throw new AssertionError("A cancelled callback must not run");
        });
        Assert.assertTrue(callback.cancel(request));
        Assert.assertFalse(callback.cancel(request));
        Assert.expectThrows(IllegalStateException.class, () -> callback.call(request, null));
    }

    @Test
    public void removeTheCallbackBeforeInvokingItAndLeaveItConsumedAfterFailure() {
        final var callback = new CallbackOnce<String, String>();
        final String request = callback.register(value -> {
            // Registration succeeds only if the old callback was already removed.
            final String next = callback.register(input -> "next: " + input);
            Assert.assertEquals(callback.call(next, value), "next: hello");
            throw new IllegalArgumentException("Callback failed");
        });
        Assert.expectThrows(IllegalArgumentException.class, () -> callback.call(request, "hello"));
        Assert.expectThrows(IllegalStateException.class, () -> callback.call(request, "again"));
    }

    @Test
    public void invokeTheCallbackOnlyOnceUnderConcurrentSubmissions() throws Exception {
        final var callback = new CallbackOnce<Integer, Integer>();
        final AtomicInteger invocations = new AtomicInteger();
        final String request = callback.register(value -> invocations.incrementAndGet());
        final var executor = Executors.newFixedThreadPool(8);
        final var start = new CountDownLatch(1);
        try {
            final var submissions = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 32; i++) {
                submissions.add(executor.submit(() -> {
                    start.await();
                    try {
                        callback.call(request, 42);
                        return true;
                    } catch (IllegalStateException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> submission : submissions) {
                if (submission.get(5, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            Assert.assertEquals(accepted, 1);
            Assert.assertEquals(invocations.get(), 1);
        } finally {
            executor.shutdownNow();
        }
    }
}
