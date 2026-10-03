package me.cortex.voxy.client.core.backend.blaze3d;

import java.nio.ByteBuffer;

/** Two RGBA32_UINT texels per model in separate tables, each with at most 65536 texels. */
final class Blaze3dModelEncoder {
    static final int TABLE_STRIDE = 16;
    private Blaze3dModelEncoder() {}

    static void put(ByteBuffer faces, ByteBuffer info, int[] models, int offset, int colour) {
        for (int face = 0; face < 4; face++) faces.putInt(models[offset + face]);
        putInfo(info, models, offset, colour);
    }

    static void putInfo(ByteBuffer info, int[] models, int offset, int colour) {
        info.putInt(models[offset + 4]).putInt(models[offset + 5])
                .putInt(models[offset + 6]).putInt(colour);
    }
}
