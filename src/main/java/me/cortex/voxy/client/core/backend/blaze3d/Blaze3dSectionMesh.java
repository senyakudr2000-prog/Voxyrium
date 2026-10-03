package me.cortex.voxy.client.core.backend.blaze3d;

import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.common.world.WorldEngine;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Packs one backend-neutral instance per Cortex quad; the vertex shader expands its corners. */
final class Blaze3dSectionMesh implements AutoCloseable {
    static final int QUAD_STRIDE = Blaze3dQuadEncoder.STRIDE;

    private final long position;
    private final @Nullable ByteBuffer instances;
    private final int opaqueQuadCount;
    private final int translucentQuadCount;
    private final long requiredTextureVersion;
    private final int aabb;

    private Blaze3dSectionMesh(long position, @Nullable ByteBuffer instances,
                              int opaqueQuadCount, int translucentQuadCount, long requiredTextureVersion, int aabb) {
        this.position = position;
        this.instances = instances;
        this.opaqueQuadCount = opaqueQuadCount;
        this.translucentQuadCount = translucentQuadCount;
        this.requiredTextureVersion = requiredTextureVersion;
        this.aabb = aabb;
    }

    static Blaze3dSectionMesh pack(BuiltSection section, Blaze3dModelStore models) {
        if (section.isEmpty() || section.geometryBuffer.size == 0) {
            return new Blaze3dSectionMesh(section.position, null, 0, 0, 0L, -1);
        }

        int totalQuads = (int) (section.geometryBuffer.size / Long.BYTES);
        int translucentQuads = section.offsets[1] - section.offsets[0];
        int opaqueQuads = totalQuads - translucentQuads;
        ByteBuffer instances = null;
        try {
            instances = allocateInstances(totalQuads);
            long source = section.geometryBuffer.address;
            long requiredTextureVersion = 0L;
            int level = WorldEngine.getLevel(section.position);
            float sectionSize = 32.0f * (1 << level);
            float originX = WorldEngine.getX(section.position) * sectionSize;
            float originY = WorldEngine.getY(section.position) * sectionSize;
            float originZ = WorldEngine.getZ(section.position) * sectionSize;
            Blaze3dQuadEncoder.section(instances, originX, originY, originZ, level);
            int previousModel = -1;
            // Keep Cortex's binary quad unchanged. The GPU reads shared model/biome tables.
            for (int index = 0; index < totalQuads; index++) {
                long quad = MemoryUtil.memGetLong(source + (long) index * Long.BYTES);
                int modelId = (int) ((quad >>> 26) & 0xFFFFL);
                if (modelId != previousModel) {
                    requiredTextureVersion = Math.max(requiredTextureVersion, models.textureVersion(modelId));
                    previousModel = modelId;
                }
            }
            if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                MemoryUtil.memCopy(source, MemoryUtil.memAddress(instances), section.geometryBuffer.size);
                instances.position(instances.position() + (int) section.geometryBuffer.size);
            } else {
                for (int index = 0; index < totalQuads; index++) {
                    Blaze3dQuadEncoder.put(instances, MemoryUtil.memGetLong(source + (long) index * Long.BYTES));
                }
            }
            requiredTextureVersion = Math.max(requiredTextureVersion, models.biomeVersion());
            instances.flip();
            return new Blaze3dSectionMesh(section.position, instances, opaqueQuads, translucentQuads,
                    requiredTextureVersion, section.aabb);
        } catch (RuntimeException | OutOfMemoryError exception) {
            free(instances);
            throw exception;
        }
    }

    private static @Nullable ByteBuffer allocateInstances(int quadCount) {
        if (quadCount == 0) {
            return null;
        }
        long byteCount = Blaze3dQuadEncoder.bytes(quadCount);
        if (byteCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Packed LoD section is too large: " + byteCount + " bytes");
        }
        return MemoryUtil.memAlloc((int) byteCount).order(ByteOrder.nativeOrder());
    }

    long position() {
        return this.position;
    }

    @Nullable ByteBuffer instances() {
        return this.instances;
    }

    int opaqueQuadCount() {
        return this.opaqueQuadCount;
    }

    int translucentQuadCount() {
        return this.translucentQuadCount;
    }

    long requiredTextureVersion() {
        return this.requiredTextureVersion;
    }

    int aabb() { return this.aabb; }

    long geometryBytes() {
        return Blaze3dQuadEncoder.bytes(this.opaqueQuadCount + this.translucentQuadCount);
    }

    boolean isEmpty() {
        return this.opaqueQuadCount == 0 && this.translucentQuadCount == 0;
    }

    @Override
    public void close() {
        free(this.instances);
    }

    private static void free(@Nullable ByteBuffer buffer) {
        if (buffer != null) {
            MemoryUtil.memFree(buffer);
        }
    }
}
