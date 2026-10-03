package me.cortex.voxy.client.core.backend.blaze3d;

import java.util.Set;

/** Horizontal coverage of built, visible Sodium columns. Missing columns always keep Voxy fallback. */
final class Blaze3dSodiumCoverage {
    static final int PRESENT = 1, WEST = 2, EAST = 4, NORTH = 8, SOUTH = 16;
    private final int x, z, width, height;
    private final byte[] pixels;

    private Blaze3dSodiumCoverage(int x, int z, int width, int height, byte[] pixels) {
        this.x = x; this.z = z; this.width = width; this.height = height; this.pixels = pixels;
    }
    static long key(int x, int z) { return (long) x << 32 | Integer.toUnsignedLong(z); }
    static Blaze3dSodiumCoverage empty() { return new Blaze3dSodiumCoverage(0, 0, 1, 1, new byte[1]); }
    static Blaze3dSodiumCoverage of(Set<Long> columns) {
        if (columns.isEmpty()) return empty();
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long key : columns) {
            int x = (int) (key >> 32), z = (int) key;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        long spanX = (long) maxX - minX + 1, spanZ = (long) maxZ - minZ + 1;
        // During teleports two collector generations can temporarily be far apart. Keep Voxy
        // fallback instead of allocating a world-sized dense mask or guessing covered columns.
        if (spanX > 1024 || spanZ > 1024) return empty();
        int width = (int) spanX, height = (int) spanZ;
        byte[] pixels = new byte[Math.multiplyExact(width, height)];
        for (long key : columns) {
            int x = (int) (key >> 32), z = (int) key, flags = PRESENT;
            if (!columns.contains(key(x - 1, z))) flags |= WEST;
            if (!columns.contains(key(x + 1, z))) flags |= EAST;
            if (!columns.contains(key(x, z - 1))) flags |= NORTH;
            if (!columns.contains(key(x, z + 1))) flags |= SOUTH;
            pixels[(z - minZ) * width + x - minX] = (byte) flags;
        }
        return new Blaze3dSodiumCoverage(minX, minZ, width, height, pixels);
    }
    int flags(int columnX, int columnZ) {
        int dx = columnX - this.x, dz = columnZ - this.z;
        return dx < 0 || dz < 0 || dx >= this.width || dz >= this.height ? 0 : this.pixels[dz * this.width + dx];
    }
    boolean covers(double minX, double minZ, double maxX, double maxZ, boolean keepBorder) {
        // Expand slightly: quad baking expands faces beyond exact voxel boundaries.
        int firstX = (int) Math.floor((minX - .001) / 16), lastX = (int) Math.floor((maxX + .001) / 16);
        int firstZ = (int) Math.floor((minZ - .001) / 16), lastZ = (int) Math.floor((maxZ + .001) / 16);
        if (firstX < this.x || firstZ < this.z || lastX >= this.x + this.width || lastZ >= this.z + this.height) return false;
        for (int z = firstZ; z <= lastZ; z++) for (int x = firstX; x <= lastX; x++) {
            int flags = flags(x, z);
            if (keepBorder ? flags != PRESENT : (flags & PRESENT) == 0) return false;
        }
        return true;
    }
    int x() { return this.x; }
    int z() { return this.z; }
    int width() { return this.width; }
    int height() { return this.height; }
    byte[] pixels() { return this.pixels; }
}
