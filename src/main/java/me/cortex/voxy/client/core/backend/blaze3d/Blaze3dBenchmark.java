package me.cortex.voxy.client.core.backend.blaze3d;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/** Continuous diagnostics. Producers never perform file I/O or wait for the writer. */
final class Blaze3dBenchmark implements AutoCloseable {
    enum Stage { OPAQUE, MASK, TARGETS, REFRESH, MODEL_PUMP, TEXTURES, GRID_SCAN, SELECTION,
        VALIDATION, SCHEDULING, FINGERPRINT, WORLD_ACQUIRE, HIERARCHY, TRANSITION, UPLOAD_DRAIN, BUFFER_CREATE, CULL, DRAW, WATER, FOG, SNAPSHOT }
    enum Counter { BYPASSED, ALREADY_PENDING, PENDING_LIMIT, STAGING_LIMIT, MISSING, UNCHANGED,
        MODEL_RETRY, TEXTURE_WAIT, FINGERPRINT_WAIT, UPLOAD_LIMIT, INACTIVE, STALE, MEMORY_REJECTED, ALLOCATION_FAILED, PACK_FAILED, WORLD_ACQUIRES }

    // A single writer serializes dimension changes, including replacement of latest.txt.
    private static final Set<Blaze3dBenchmark> SESSIONS = ConcurrentHashMap.newKeySet();
    private static final AtomicLong IDS = new AtomicLong();
    private static final java.util.concurrent.ExecutorService WRITER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Voxy Blaze3D benchmark writer");
        thread.setDaemon(true);
        return thread;
    });
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            SESSIONS.forEach(Blaze3dBenchmark::close);
            WRITER.shutdown();
            try { WRITER.awaitTermination(2, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }, "Voxy benchmark shutdown"));
    }

    private record Line(long nanos, String type, String data) {}
    private record Frame(long nanos, long number, long interval, double x, double y, double z,
                         long[] stages, int uploads, long bytes, int opaqueDraws, int waterDraws) {}
    static final class Request {
        final long key, started;
        final int level, ring;
        final double distance;
        final boolean cached;
        volatile long firstAttempt, acquireNanos, buildNanos, packNanos, ready;
        volatile int attempts, modelRetries;
        Request(long key, int level, int ring, double distance, boolean cached) {
            this.key = key; this.level = level; this.ring = ring; this.distance = distance; this.cached = cached;
            this.started = System.nanoTime();
            if (cached) this.ready = this.started;
        }
    }

    private final Path latest, archive;
    private final String header;
    private final Consumer<String> warning;
    private final ArrayBlockingQueue<Object> records = new ArrayBlockingQueue<>(8192);
    private final Map<Long, Request> requests = new ConcurrentHashMap<>();
    private final LongAdder[] counters = Arrays.stream(Counter.values()).map(ignored -> new LongAdder()).toArray(LongAdder[]::new);
    private final AtomicLong dropped = new AtomicLong();
    private final long started = System.nanoTime();
    private volatile boolean closing, failed;
    private long nextSnapshot;
    // Frame state belongs exclusively to the client/render thread. Workers only touch requests/counters.
    private long frameStart, frameNumber;
    private long[] frameStages;
    private double frameX, frameY, frameZ;
    private int frameUploads, opaqueDraws, waterDraws;
    private long frameBytes;

    Blaze3dBenchmark(Path directory, String header, Consumer<String> warning) {
        this.latest = directory.resolve("latest.txt").toAbsolutePath();
        String stamp = DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm-ss-SSS").withZone(ZoneOffset.UTC).format(Instant.now());
        this.archive = directory.resolve(stamp + "_" + ProcessHandle.current().pid() + "_" + IDS.incrementAndGet() + ".txt").toAbsolutePath();
        this.header = header;
        this.warning = warning;
        SESSIONS.add(this);
        WRITER.execute(this::writeLog);
    }

    Path path() { return this.latest; }
    boolean active() { return !this.closing && !this.failed; }
    long droppedRecords() { return this.dropped.get(); }
    long elapsedNanos() { return System.nanoTime() - this.started; }
    boolean snapshotDue() {
        long now = System.nanoTime();
        if (now < this.nextSnapshot || this.closing || this.failed) return false;
        this.nextSnapshot = now + (now - this.started < 15_000_000_000L ? 250_000_000L : 1_000_000_000L);
        return true;
    }
    void event(String type, String data) {
        if (active()) offer(new Line(System.nanoTime(), type, data.replace('\n', ' ').replace('\r', ' ')));
    }
    void count(Counter counter) { if (active()) this.counters[counter.ordinal()].increment(); }
    String counterSummary() {
        StringBuilder result = new StringBuilder("droppedRecords=").append(this.dropped.get());
        for (Counter counter : Counter.values()) result.append(' ').append(counter).append('=').append(this.counters[counter.ordinal()].sum());
        return result.toString();
    }
    Iterable<Request> pending() { return this.requests.values(); }
    Request request(long key) { return active() ? this.requests.get(key) : null; }
    Request requested(long key, int level, int ring, double distance, boolean cached) {
        if (!active()) return null;
        Request request = new Request(key, level, ring, distance, cached);
        this.requests.put(key, request);
        return request;
    }
    void buildAttempt(long key, long begin, long acquireNanos, long nanos, boolean modelRetry) {
        Request request = request(key);
        if (request == null) return;
        if (request.firstAttempt == 0) request.firstAttempt = begin;
        request.acquireNanos += acquireNanos;
        request.buildNanos += nanos;
        request.attempts++;
        if (modelRetry) { request.modelRetries++; count(Counter.MODEL_RETRY); }
    }
    void prepared(Request request, long packNanos) {
        if (request != null && active() && this.requests.get(request.key) == request) {
            request.packNanos = packNanos;
            request.ready = System.nanoTime();
        }
    }
    void finished(Request request, String outcome, long bytes) {
        // An old worker may finish after another request for the same section has started.
        if (request == null || !active() || !this.requests.remove(request.key, request)) return;
        long now = System.nanoTime();
        event("MESH", "key=" + request.key + " level=" + request.level + " ring=" + request.ring
                + " distanceAtRequest=" + request.distance + " cached=" + request.cached + " outcome=" + outcome
                + " bytes=" + bytes + " totalNs=" + (now - request.started)
                + " firstQueueNs=" + (request.firstAttempt == 0 ? -1 : request.firstAttempt - request.started)
                + " readyNs=" + (request.ready == 0 ? -1 : request.ready - request.started)
                + " readyToFinishNs=" + (request.ready == 0 ? -1 : now - request.ready)
                + " acquireNs=" + request.acquireNanos + " generateNs=" + request.buildNanos + " packNs=" + request.packNanos
                + " attempts=" + request.attempts + " modelRetries=" + request.modelRetries);
    }

    void beginFrame(long number, double x, double y, double z) {
        if (!active()) { this.frameStages = null; return; }
        long now = System.nanoTime();
        finishFrame(now - this.frameStart);
        this.frameStart = now; this.frameNumber = number;
        this.frameX = x; this.frameY = y; this.frameZ = z;
        this.frameStages = new long[Stage.values().length];
        this.frameUploads = this.opaqueDraws = this.waterDraws = 0;
        this.frameBytes = 0;
    }
    void stage(Stage stage, long nanos) {
        if (active() && this.frameStages != null) this.frameStages[stage.ordinal()] += nanos;
    }
    void geometry(int uploads, long bytes, int draws) { this.frameUploads = uploads; this.frameBytes = bytes; this.opaqueDraws = draws; }
    void waterDraws(int draws) { this.waterDraws = draws; }
    private void finishFrame(long interval) {
        if (this.frameStages == null) return;
        if (active()) offer(new Frame(this.frameStart, this.frameNumber, interval, this.frameX, this.frameY, this.frameZ,
                this.frameStages, this.frameUploads, this.frameBytes, this.opaqueDraws, this.waterDraws));
        this.frameStages = null;
    }
    private void offer(Object record) {
        if (!this.closing && !this.failed && !this.records.offer(record)) this.dropped.incrementAndGet();
    }
    @Override public void close() {
        if (this.closing) return;
        // The caller owns frame state; the shutdown hook only signals/drains queued records.
        this.closing = true;
    }
    void endSession(String reason) {
        finishFrame(0); // The final frame has no following frame interval.
        if (active()) event("END", "reason=" + reason + " pending=" + this.requests.size() + " " + counterSummary());
        close();
    }

    private void writeLog() {
        try {
            Files.createDirectories(this.archive.getParent());
            try (BufferedWriter history = Files.newBufferedWriter(this.archive, StandardCharsets.UTF_8);
                 BufferedWriter live = Files.newBufferedWriter(this.latest, StandardCharsets.UTF_8)) {
                write(history, live, "VOXY_BLAZE3D_BENCHMARK version=1 utc=" + Instant.now() + " archive=" + this.archive);
                write(history, live, this.header);
                write(history, live, "Frame/stage values are wall-clock nanoseconds on the CPU; stages overlap. GPU execution and transfer completion are not timed. frameIntervalNs spans opaque entry points, including other game work, pauses and frame limiting. final interval=0 is incomplete. Sodium observations describe visible sections, not all loaded chunks or final pixel coverage.");
                history.flush(); live.flush();
                long nextFlush = System.nanoTime() + 250_000_000L, nextWindow = System.nanoTime() + 1_000_000_000L;
                ArrayList<Long> intervals = new ArrayList<>(512);
                long[] totals = new long[Stage.values().length], maxima = new long[Stage.values().length];
                int frames = 0;
                while (!this.closing || !this.records.isEmpty()) {
                    Object record = this.records.poll(100, TimeUnit.MILLISECONDS);
                    if (record instanceof Line line) {
                        write(history, live, "tNs=" + (line.nanos - this.started) + " " + line.type + " " + line.data);
                    } else if (record instanceof Frame frame) {
                        StringBuilder row = new StringBuilder("tNs=").append(frame.nanos - this.started)
                                .append(" FRAME n=").append(frame.number).append(" frameIntervalNs=").append(frame.interval)
                                .append(" camera=").append(frame.x).append(',').append(frame.y).append(',').append(frame.z)
                                .append(" uploads=").append(frame.uploads).append(" uploadBytes=").append(frame.bytes)
                                .append(" opaqueDraws=").append(frame.opaqueDraws).append(" waterDraws=").append(frame.waterDraws);
                        for (Stage stage : Stage.values()) {
                            long value = frame.stages[stage.ordinal()];
                            row.append(' ').append(stage).append("Ns=").append(value);
                            totals[stage.ordinal()] += value;
                            maxima[stage.ordinal()] = Math.max(maxima[stage.ordinal()], value);
                        }
                        write(history, live, row.toString());
                        frames++;
                        if (frame.interval > 0 && intervals.size() < 16384) intervals.add(frame.interval);
                    }
                    long now = System.nanoTime();
                    if (now >= nextWindow || (this.closing && this.records.isEmpty())) {
                        long[] sorted = intervals.stream().mapToLong(Long::longValue).sorted().toArray();
                        StringBuilder row = new StringBuilder("tNs=").append(now - this.started).append(" WINDOW frames=").append(frames)
                                .append(" intervalSamples=").append(sorted.length)
                                .append(" intervalP50Ns=").append(percentile(sorted, .50))
                                .append(" intervalP95Ns=").append(percentile(sorted, .95))
                                .append(" intervalP99Ns=").append(percentile(sorted, .99))
                                .append(" intervalMaxNs=").append(percentile(sorted, 1));
                        for (Stage stage : Stage.values()) row.append(' ').append(stage).append("AvgNs=")
                                .append(frames == 0 ? 0 : totals[stage.ordinal()] / frames).append(' ').append(stage)
                                .append("MaxNs=").append(maxima[stage.ordinal()]);
                        try { appendRuntime(row); }
                        catch (RuntimeException exception) { row.append(" runtimeMetricsError=").append(exception.getClass().getSimpleName()); }
                        write(history, live, row + " " + counterSummary());
                        intervals.clear(); Arrays.fill(totals, 0); Arrays.fill(maxima, 0); frames = 0;
                        nextWindow = now + 1_000_000_000L;
                    }
                    if (now >= nextFlush || this.closing) {
                        history.flush(); live.flush(); nextFlush = now + 250_000_000L;
                    }
                }
                write(history, live, "WRITER_END " + counterSummary());
            }
        } catch (IOException | InterruptedException | RuntimeException exception) {
            this.failed = true;
            this.warning.accept("Blaze3D benchmark could not write " + this.latest + ": " + exception);
        } finally {
            this.records.clear(); this.requests.clear(); SESSIONS.remove(this);
        }
    }

    static long percentile(long[] sorted, double fraction) {
        return sorted.length == 0 ? 0 : sorted[Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1)];
    }
    private static void appendRuntime(StringBuilder row) {
        Runtime runtime = Runtime.getRuntime();
        row.append(" heapUsedBytes=").append(runtime.totalMemory() - runtime.freeMemory())
                .append(" heapCommittedBytes=").append(runtime.totalMemory()).append(" heapMaxBytes=").append(runtime.maxMemory());
        long gcCount = 0, gcMillis = 0;
        for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, collector.getCollectionCount()); gcMillis += Math.max(0, collector.getCollectionTime());
        }
        row.append(" gcCount=").append(gcCount).append(" gcMillis=").append(gcMillis);
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
            row.append(" processCpuLoad=").append(extended.getProcessCpuLoad()).append(" processCpuNs=").append(extended.getProcessCpuTime());
        }
        for (var pool : ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)) {
            row.append(' ').append(pool.getName()).append("BufferBytes=").append(pool.getMemoryUsed());
        }
    }
    private static void write(BufferedWriter history, BufferedWriter live, String line) throws IOException {
        history.write(line); history.newLine(); live.write(line); live.newLine();
    }
}
