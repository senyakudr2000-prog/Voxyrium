package me.cortex.voxy.client.core.backend.blaze3d;

import java.util.LinkedHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongUnaryOperator;

/** Bounded, nonblocking revision lookup. No loader ever runs on the requesting thread. */
final class Blaze3dFingerprintCache implements AutoCloseable {
    private static final class Entry {
        volatile Long value;
        long completed;
    }
    private record Job(long key, Entry entry) {}

    private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>(256, .75f, true);
    private final ArrayBlockingQueue<Job> jobs;
    private final int capacity;
    private final LongUnaryOperator loader;
    private final Consumer<RuntimeException> warning;
    private final Runnable release;
    private volatile boolean closed;
    private final Thread worker;

    Blaze3dFingerprintCache(int capacity, int maxQueued, LongUnaryOperator loader,
                           Consumer<RuntimeException> warning, Runnable release) {
        if (capacity < 1 || maxQueued < 1) throw new IllegalArgumentException("Invalid fingerprint cache capacity");
        this.capacity = capacity;
        this.jobs = new ArrayBlockingQueue<>(maxQueued);
        this.loader = loader;
        this.warning = warning;
        this.release = release;
        this.worker = new Thread(this::run, "Voxy Blaze3D revision worker");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    synchronized Long request(long key) {
        if (this.closed) return null;
        Entry entry = this.entries.get(key);
        // Missing data can appear without a currently watched mesh, so retry negative entries.
        if (entry != null && entry.value != null && entry.value == Long.MIN_VALUE
                && System.nanoTime() - entry.completed >= 1_000_000_000L) {
            this.entries.remove(key);
            entry = null;
        }
        if (entry != null) return entry.value;
        entry = new Entry();
        if (!this.jobs.offer(new Job(key, entry))) return null;
        this.entries.put(key, entry);
        while (this.entries.size() > this.capacity) this.entries.remove(this.entries.keySet().iterator().next());
        return null;
    }

    synchronized void invalidate(long key) { this.entries.remove(key); }
    synchronized String summary() { return "fingerprintCache=" + this.entries.size() + " fingerprintQueue=" + this.jobs.size(); }

    private void run() {
        try {
            while (!this.closed) {
                Job job = this.jobs.poll(100, TimeUnit.MILLISECONDS);
                if (job == null) continue;
                synchronized (this) {
                    if (this.closed || this.entries.get(job.key) != job.entry) continue;
                }
                try {
                    long value = this.loader.applyAsLong(job.key);
                    synchronized (this) {
                        // An edit, eviction or world departure must not publish an obsolete read.
                        if (!this.closed && this.entries.get(job.key) == job.entry) {
                            job.entry.completed = System.nanoTime();
                            job.entry.value = value;
                        }
                    }
                } catch (RuntimeException exception) {
                    synchronized (this) { this.entries.remove(job.key, job.entry); }
                    this.warning.accept(exception);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            close();
            this.release.run();
        }
    }

    @Override public synchronized void close() {
        this.closed = true;
        this.entries.clear();
        this.jobs.clear();
        // Allow an in-progress world acquisition to release its own references before exit.
        // Interrupting storage I/O can poison the shared database channel.
    }
}
