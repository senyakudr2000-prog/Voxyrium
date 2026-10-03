package me.cortex.voxy.client.core.backend.blaze3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Exercises live visibility, session isolation, mesh latency and I/O failure handling without Minecraft. */
public final class Blaze3dBenchmarkTest {
    static void run() throws Exception {
        Path directory = Files.createTempDirectory("voxy-benchmark-test-");
        List<String> warnings = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            Blaze3dBenchmark first = new Blaze3dBenchmark(directory, "test-session=first", warnings::add);
            try {
                first.beginFrame(1, 12, 64, -24);
                first.stage(Blaze3dBenchmark.Stage.SELECTION, 123);
                Blaze3dBenchmark.Request request = first.requested(42, 0, 1, 200, false);
                first.buildAttempt(42, System.nanoTime(), 12, 34, true);
                first.prepared(request, 56);
                first.finished(request, "uploaded", 32);
                first.beginFrame(2, 13, 64, -24);
                // The session stays open while an independent reader observes flushed records.
                await(() -> contains(first.path(), "outcome=uploaded") && contains(first.path(), "SELECTIONNs=123"));
                check(contains(first.path(), "acquireNs=12 generateNs=34 packNs=56 attempts=1 modelRetries=1"), "Mesh stage timing must survive serialization");
                check(first.request(42) == null, "Finished requests must not remain in the pending age gauges");
                Blaze3dBenchmark.Request successor = first.requested(42, 0, 0, 100, false);
                first.prepared(request, 99);
                first.finished(request, "generation-changed", 32);
                check(first.request(42) == successor, "A late completion must preserve a newer request at the same position");
                check(successor.ready == 0, "Old packing callbacks must not mark the successor ready");
                first.prepared(successor, 78);
                first.finished(successor, "replacement-uploaded", 64);
                await(() -> contains(first.path(), "outcome=replacement-uploaded"));
            } finally { first.endSession("first-world-left"); }

            Blaze3dBenchmark second = new Blaze3dBenchmark(directory, "test-session=second", warnings::add);
            try {
                second.event("MARK", "second-only");
                await(() -> contains(second.path(), "second-only"));
                check(!contains(second.path(), "test-session=first"), "latest.txt must contain only the current session");
            } finally { second.endSession("second-world-left"); }
            await(() -> contains(second.path(), "WRITER_END"));
            try (var paths = Files.list(directory)) {
                List<Path> histories = paths.filter(path -> !path.getFileName().toString().equals("latest.txt")).toList();
                check(histories.size() == 2, "Both world sessions must retain independent archives");
                check(histories.stream().anyMatch(path -> contains(path, "first-world-left")), "Changing worlds must preserve the earlier session footer");
            }
            check(warnings.isEmpty(), "Ordinary logging must not report I/O errors");

            Path file = directory.resolve("not-a-directory");
            Files.writeString(file, "occupied");
            Blaze3dBenchmark broken = new Blaze3dBenchmark(file, "failure-test", warnings::add);
            try {
                await(() -> !warnings.isEmpty());
                check(!broken.active(), "A failed writer must disable instrumentation");
                broken.beginFrame(1, 0, 0, 0);
                broken.count(Blaze3dBenchmark.Counter.WORLD_ACQUIRES);
                check(broken.requested(1, 0, 0, 0, false) == null, "A failed session must reject producer work");
                check(broken.counterSummary().contains("WORLD_ACQUIRES=0"), "Failed sessions must stop collecting metrics");
            }
            finally { broken.endSession("failed"); }
            check(Files.readString(file).equals("occupied"), "A log failure must preserve the conflicting file");
            check(Blaze3dBenchmark.percentile(new long[]{10, 20, 30, 40}, .50) == 20, "p50 uses nearest rank");
            check(Blaze3dBenchmark.percentile(new long[]{10, 20, 30, 40}, .99) == 40, "p99 preserves the upper tail");
            check(Blaze3dBenchmark.percentile(new long[0], .95) == 0, "Idle windows have no percentile samples");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("Blaze3D benchmark: live flush, session isolation, mesh timing and I/O failure checks passed.");
    }
    private static boolean contains(Path path, String text) {
        try { return Files.exists(path) && Files.readString(path).contains(text); }
        catch (java.io.IOException ignored) { return false; }
    }
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for the asynchronous writer");
            Thread.sleep(10);
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
