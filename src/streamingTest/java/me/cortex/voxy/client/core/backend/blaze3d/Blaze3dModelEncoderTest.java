package me.cortex.voxy.client.core.backend.blaze3d;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Checks native metadata addressing and biome relocation without uploading to a device. */
final class Blaze3dModelEncoderTest {
    static void run() {
        int[] models = new int[32];
        for (int face = 0; face < 6; face++) models[16 + face] = 0x03ABCDEF - face;
        models[22] = 31;
        models[23] = -1;
        ByteBuffer faces = ByteBuffer.allocate(32).order(ByteOrder.nativeOrder());
        ByteBuffer info = ByteBuffer.allocate(32).order(ByteOrder.nativeOrder());
        faces.position(16); info.position(16);
        Blaze3dModelEncoder.put(faces, info, models, 16, 65536 - 512);
        check(faces.position() == 32 && info.position() == 32, "Each model has one 16-byte texel in each table");
        for (int face = 0; face < 6; face++) {
            int shaderFace = face < 4 ? faces.getInt(16 + face * 4) : info.getInt(16 + (face - 4) * 4);
            check(shaderFace == models[16 + face], "All partial-face/depth/cutout/tint bits survive the table split");
        }
        check(info.getInt(24) == 31 && info.getInt(28) == 65024, "Flags and the current biome base override CPU model data");
        check(info.getInt(28) + 511 == 65535, "The largest biome ID fits the last colour-table entry");
        ByteBuffer replacementFaces = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
        ByteBuffer replacementInfo = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
        Blaze3dModelEncoder.putInfo(replacementInfo, models, 16, 1234);
        check(replacementInfo.getInt(12) == 1234 && info.getInt(28) == 65024
                        && replacementInfo.getInt(0) == models[20] && replacementInfo.getInt(4) == models[21]
                        && replacementInfo.getInt(8) == 31 && replacementFaces.position() == 0,
                "Biome relocation creates an independent upload snapshot without rebuilding quads");
        int constantTint = 0xFF112233;
        models[22] = 8;
        replacementFaces.clear(); replacementInfo.clear();
        Blaze3dModelEncoder.put(replacementFaces, replacementInfo, models, 16, constantTint);
        check(replacementInfo.getInt(8) == 8 && replacementInfo.getInt(12) == constantTint,
                "Constant ARGB tint preserves signed values and material flags");
        check(faces.getLong(0) == 0 && info.getLong(0) == 0, "Encoding does not overwrite neighbouring models");
        System.out.println("Blaze3D model tables: face metadata, material flags, biome addressing and immutable snapshot checks passed.");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
