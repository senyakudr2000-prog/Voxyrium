package me.cortex.voxy.client.core.rendering.building;

/** Queue rank in the high word, FIFO/retry sequence in the low word. */
public final class RenderTaskPriority {
    private RenderTaskPriority() {}

    public static long nativePriority(int levelRank, int attempts, int addin, int sequence) {
        return encode((levelRank * 3L + Math.min(attempts, 3)) * 2 + addin, sequence);
    }

    public static long spatialPriority(int rank, int attempts, int addin, int sequence) {
        // Retries yield to fresh work within the same frontier without losing the distance ring.
        return encode(rank * 8L + Math.min(attempts, 3) * 2L + addin, sequence);
    }

    private static long encode(long rank, int sequence) { return (rank << 32) | Integer.toUnsignedLong(sequence); }
}
