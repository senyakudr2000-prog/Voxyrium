package me.cortex.voxy.client.core.backend.blaze3d;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/** Validates the section header and unchanged Cortex quad ABI without a graphics device. */
final class Blaze3dQuadEncoderTest {
    private static int checks;

    static void run() {
        ByteBuffer out = ByteBuffer.allocate(48).order(ByteOrder.nativeOrder());
        out.position(8);
        Blaze3dQuadEncoder.section(out, -512, 1024, -16384, 4);
        check(out.position() == 24, "Section origin and scale occupy 16 bytes once per section");
        check(out.getFloat(8) == -512 && out.getFloat(12) == 1024 && out.getFloat(16) == -16384
                && out.getFloat(20) == 16, "The uniform header preserves negative coordinates and LOD scale");
        long quad = 5L | (15L << 3) | (7L << 7) | (31L << 11) | (19L << 16)
                | (17L << 21) | (0xBEEFL << 26) | (0xBL << 42) | (511L << 46) | (0xA7L << 55);
        Blaze3dQuadEncoder.put(out, quad);
        check(out.position() == 32, "Each appended quad occupies exactly eight bytes");
        int low = out.getInt(24), high = out.getInt(28);
        check((Integer.toUnsignedLong(low) | (Integer.toUnsignedLong(high) << 32)) == quad,
                "Both native quad words survive without metadata expansion");
        check((low >>> 26 | (high & 1023) << 6) == 0xBEEF, "Model IDs cross the word boundary unchanged");
        check(((high >>> 14) & 511) == 511, "The shader can recover all nine biome bits");
        check(((high >>> 23) & 255) == 0xA7, "Both light channels retain their original bits");
        check(((low >>> 3) & 255 | ((high >>> 10) & 15) << 8) == 0xB7F, "Fluid corner heights survive both words");
        check((low & 7) == 5 && ((low >>> 21) & 31) == 17
                        && ((low >>> 16) & 31) == 19 && ((low >>> 11) & 31) == 31,
                "Faces and local coordinates preserve native layout");
        check(out.getLong(0) == 0 && out.getLong(32) == 0, "Appending preserves neighbouring records");
        check(Blaze3dQuadEncoder.bytes(0) == 0 && Blaze3dQuadEncoder.bytes(1000) == 8016,
                "Staging and residency count the header once, including mixed opaque/water meshes");
        Random random = new Random(0xB1A2E3D);
        for (int i = 0; i < 10000; i++) {
            out.clear();
            long value = random.nextLong();
            Blaze3dQuadEncoder.put(out, value);
            int lo = out.getInt(0), hi = out.getInt(4);
            if ((Integer.toUnsignedLong(lo) | (Integer.toUnsignedLong(hi) << 32)) != value
                    || (lo >>> 26 | (hi & 1023) << 6) != (int) ((value >>> 26) & 65535)
                    || ((hi >>> 23) & 255) != (int) ((value >>> 55) & 255)
                    || ((hi >>> 14) & 511) != (int) ((value >>> 46) & 511)) {
                throw new AssertionError("Native GPU quad words differ at " + i);
            }
        }
        check(true, "Ten thousand lossless quad/model/biome/light round trips");
        System.out.println("Blaze3D eight-byte quad ABI: " + checks + " checks passed (including 10000 round trips).");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
