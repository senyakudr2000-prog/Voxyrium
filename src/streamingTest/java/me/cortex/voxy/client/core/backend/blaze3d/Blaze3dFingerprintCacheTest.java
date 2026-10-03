package me.cortex.voxy.client.core.backend.blaze3d;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Blocks the loader to exercise invalidation, queue pressure and lifetime ownership. */
public final class Blaze3dFingerprintCacheTest {
    static void run() throws Exception {
        Thread caller = Thread.currentThread();
        CountDownLatch entered = new CountDownLatch(1), unblock = new CountDownLatch(1), released = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger(), warnings = new AtomicInteger();
        Blaze3dFingerprintCache cache = new Blaze3dFingerprintCache(8, 1, key -> {
            check(Thread.currentThread() != caller, "World reads must stay off the requesting thread");
            int attempt = loads.incrementAndGet();
            if (attempt == 1) {
                entered.countDown();
                awaitLatch(unblock);
                return 11;
            }
            return key * 100 + attempt;
        }, exception -> warnings.incrementAndGet(), released::countDown);
        try {
            check(cache.request(1) == null, "Cold reads return pending instead of blocking");
            check(entered.await(5, TimeUnit.SECONDS), "The worker must begin its world read");
            cache.invalidate(1);
            check(cache.request(1) == null, "An edit queues a new request while an old read is blocked");
            check(cache.request(2) == null, "Queue saturation must defer without blocking the renderer");
            unblock.countDown();
            await(() -> cache.request(1) != null);
            check(cache.request(1) == 102, "An obsolete in-flight result must not replace a newer revision");
            await(() -> cache.request(2) != null);
            check(cache.request(2) == 203, "Deferred lookups must make progress once queue pressure subsides");
            int completed = loads.get();
            for (int index = 0; index < 100; index++) check(cache.request(1) == 102, "Idle lookups reuse the confirmed fingerprint");
            check(loads.get() == completed, "Idle audits must not repeat world acquisition");
        } finally { unblock.countDown(); cache.close(); }
        check(released.await(5, TimeUnit.SECONDS), "Closing must release the worker's world ownership");
        check(cache.request(1) == null, "Closed worlds cannot supply cached results");
        check(warnings.get() == 0, "Ordinary invalidation must not report loader errors");

        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch failedReleased = new CountDownLatch(1);
        try (Blaze3dFingerprintCache retry = new Blaze3dFingerprintCache(1, 2, key -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("test loader failure");
            return key;
        }, exception -> warnings.incrementAndGet(), failedReleased::countDown)) {
            await(() -> retry.request(3) != null);
            check(retry.request(3) == 3, "Failed reads must be retryable");
            await(() -> retry.request(4) != null);
            await(() -> retry.request(3) != null);
            check(attempts.get() >= 4, "The bounded cache must evict and reload old keys");
        }
        check(failedReleased.await(5, TimeUnit.SECONDS), "Failure recovery must still release world ownership");
        check(warnings.get() == 1, "Each loader failure must be reported");
        System.out.println("Blaze3D revisions: nonblocking reads, stale invalidation, bounded queue/cache, retry and lifetime checks passed.");
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for the revision worker");
            Thread.sleep(5);
        }
    }
    private static void awaitLatch(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS), "Timed out waiting to unblock the loader"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
