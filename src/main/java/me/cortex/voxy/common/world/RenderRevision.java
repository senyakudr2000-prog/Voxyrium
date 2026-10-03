package me.cortex.voxy.common.world;

import java.util.concurrent.atomic.AtomicLong;

/** Revision identity survives section eviction: a reloaded section cannot reuse zero. */
final class RenderRevision {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private final AtomicLong value = new AtomicLong(SEQUENCE.incrementAndGet());

    long get() { return this.value.get(); }
    void advance() {
        // A delayed writer must not restore an older revision after another writer advances it.
        this.value.accumulateAndGet(SEQUENCE.incrementAndGet(), Math::max);
    }
}
