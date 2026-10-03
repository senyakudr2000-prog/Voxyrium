package me.cortex.voxy.client.core.backend.blaze3d;

import java.nio.ByteBuffer;

/** Matches the GLSL address; collisions are handled by comparing the complete origin and scale. */
final class Blaze3dOcclusionHash {
    static final int CAPACITY = 65536;
    static int index(float x, float y, float z, float scale) {
        int h = Float.floatToRawIntBits(x);
        h = (Integer.rotateLeft(h, 13) ^ Float.floatToRawIntBits(y)) * 0x9E3779B9;
        h = (Integer.rotateLeft(h, 17) ^ Float.floatToRawIntBits(z)) * 0x85EBCA6B;
        h = (Integer.rotateLeft(h, 15) ^ Float.floatToRawIntBits(scale)) * 0xC2B2AE35;
        // Integer-aligned world origins have many equal low bits; avalanche before truncation.
        h = (h ^ (h >>> 16)) * 0x7FEB352D;
        h = (h ^ (h >>> 15)) * 0x846CA68B;
        return (h ^ (h >>> 16)) & (CAPACITY - 1);
    }
    static boolean owns(ByteBuffer origins, int index, float x, float y, float z, float scale) {
        int offset = index * 16;
        return origins.getFloat(offset) == x && origins.getFloat(offset + 4) == y
                && origins.getFloat(offset + 8) == z && origins.getFloat(offset + 12) == scale;
    }
}
