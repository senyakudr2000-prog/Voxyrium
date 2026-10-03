package me.cortex.voxy.client.core.backend.blaze3d;

import java.nio.ByteBuffer;

/** Binary contract shared by the instance vertex format and blaze3d_lod_terrain.vsh. */
final class Blaze3dQuadEncoder {
    static final int STRIDE = Long.BYTES;
    static final int SECTION_BYTES = 16;

    private Blaze3dQuadEncoder() {}

    static void put(ByteBuffer out, long quad) { out.putInt((int) quad).putInt((int) (quad >>> 32)); }

    static void section(ByteBuffer out, float x, float y, float z, int level) {
        out.putFloat(x).putFloat(y).putFloat(z).putFloat(1 << level);
    }

    static long bytes(int quadCount) { return quadCount == 0 ? 0 : SECTION_BYTES + (long) quadCount * STRIDE; }
}
