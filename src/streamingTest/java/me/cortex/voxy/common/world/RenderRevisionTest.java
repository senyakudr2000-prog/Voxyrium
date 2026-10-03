package me.cortex.voxy.common.world;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class RenderRevisionTest {
    public static void run() throws Exception {
        RenderRevision previous = new RenderRevision();
        long original = previous.get();
        previous.advance();
        check(previous.get() > original, "An edit must change the revision");
        RenderRevision reloaded = new RenderRevision();
        check(reloaded.get() > previous.get(), "Reloaded data must not alias the previous lifetime's revision");
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int index = 0; index < 2; index++) new Thread(() -> {
            try {
                long last = reloaded.get();
                for (int edit = 0; edit < 10000; edit++) {
                    reloaded.advance();
                    long current = reloaded.get();
                    check(current > last, "Concurrent editors must observe strictly advancing revisions");
                    last = current;
                }
            } catch (Throwable exception) { failure.set(exception); }
            finally { done.countDown(); }
        }, "Revision regression").start();
        check(done.await(5, TimeUnit.SECONDS), "Concurrent revision test must complete");
        if (failure.get() != null) throw new AssertionError("Revision concurrency failed", failure.get());
        System.out.println("World revisions: edit visibility, reload identity and concurrent monotonicity checks passed.");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
