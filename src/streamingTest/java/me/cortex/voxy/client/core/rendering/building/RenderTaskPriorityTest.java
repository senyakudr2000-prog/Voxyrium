package me.cortex.voxy.client.core.rendering.building;

public final class RenderTaskPriorityTest {
    public static void run() {
        for (int level = 0; level <= 3; level++) {
            for (int attempts = 0; attempts < 10; attempts++) {
                for (int addin = 0; addin <= 1; addin++) {
                    long original = (((level * 3L + Math.min(attempts, 3)) * 2 + addin) << 32) + 0xFFFF_FFFFL;
                    check(RenderTaskPriority.nativePriority(level, attempts, addin, -1) == original,
                            "Native queue order must remain byte-for-byte identical");
                }
            }
        }
        check(Long.compareUnsigned(RenderTaskPriority.spatialPriority(4, 99, 1, -1),
                RenderTaskPriority.spatialPriority(5, 0, 0, 0)) < 0, "Near retries must retain priority over remote roots");
        check(Long.compareUnsigned(RenderTaskPriority.spatialPriority(4, 0, 0, 2),
                RenderTaskPriority.spatialPriority(4, 1, 0, 1)) < 0, "A model retry yields to fresh work in its frontier");
        check(Long.compareUnsigned(RenderTaskPriority.spatialPriority(4, 0, 0, 1),
                RenderTaskPriority.spatialPriority(4, 0, 0, 2)) < 0, "Equal ranks preserve FIFO order");
        System.out.println("Mesh worker priority: native compatibility, spatial order and retry fairness checks passed.");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
