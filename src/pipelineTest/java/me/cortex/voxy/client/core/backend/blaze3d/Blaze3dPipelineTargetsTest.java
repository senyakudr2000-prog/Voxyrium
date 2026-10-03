package me.cortex.voxy.client.core.backend.blaze3d;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.resources.Identifier;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Exercises Minecraft's actual attachment validation without opening a device or the game. */
public final class Blaze3dPipelineTargetsTest {
    private static int accepted;
    public static void main(String[] args) {
        verify(Blaze3dAuxiliaryPipelines.SODIUM_DEPTH, GpuFormat.RGBA8_UNORM);
        verify(Blaze3dAuxiliaryPipelines.HIZ_COPY, GpuFormat.R32_FLOAT);
        verify(Blaze3dAuxiliaryPipelines.HIZ_REDUCE, GpuFormat.R32_FLOAT);
        verify(Blaze3dAuxiliaryPipelines.HIZ_VISIBILITY, GpuFormat.R8_UNORM);
        verify(Blaze3dAuxiliaryPipelines.HIZ_COMMANDS, GpuFormat.RGBA32_UINT);
        if (Blaze3dAuxiliaryPipelines.SODIUM_DEPTH.getColorTargetState().writeMask() != ColorTargetState.WRITE_NONE
                || !Blaze3dAuxiliaryPipelines.SODIUM_DEPTH.wantsDepthTexture()) {
            throw new AssertionError("Depth prefill must write depth without writing colour");
        }
        for (RenderPipeline pipeline : List.of(Blaze3dAuxiliaryPipelines.HIZ_COPY,
                Blaze3dAuxiliaryPipelines.HIZ_REDUCE, Blaze3dAuxiliaryPipelines.HIZ_VISIBILITY,
                Blaze3dAuxiliaryPipelines.HIZ_COMMANDS)) {
            if (pipeline.wantsDepthTexture()) throw new AssertionError("HiZ colour passes have no depth attachment");
        }

        // Negative control reproduces the exact runtime failure from the supplied log.
        RenderPipeline broken = RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("voxy", "invalid_depth_target"))
                .withVertexShader(Identifier.fromNamespaceAndPath("voxy", "core/blaze3d_lod_composite"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("voxy", "core/blaze3d_lod_depth_seed"))
                .withVertexBinding(0, DefaultVertexFormat.POSITION)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLE_STRIP)
                .withColorTargetState(new ColorTargetState(Optional.empty(), null, ColorTargetState.WRITE_NONE)).build();
        try {
            verify(broken, GpuFormat.RGBA8_UNORM);
            throw new AssertionError("Null format was incorrectly accepted as a wildcard");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("format doesn't match")) throw expected;
        }
        // Shader compilation alone cannot detect attachment-format mismatches.
        try {
            verify(Blaze3dAuxiliaryPipelines.HIZ_COMMANDS, GpuFormat.RGBA8_UNORM);
            throw new AssertionError("Integer commands were accepted with a normalized colour target");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("format doesn't match")) throw expected;
        }
        if (accepted != 5) throw new AssertionError("Invalid pipelines must fail before backend submission");
        System.out.println("Blaze3D pipeline targets: five actual pipelines accepted; logged failure and wrong integer target rejected without a GPU.");
    }

    private static void verify(RenderPipeline pipeline, GpuFormat format) {
        DeviceInfo info = new DeviceInfo("Headless test", "", "", true, "Test", 1,
                null, null, Set.of(), null, null);
        GpuDeviceBackend device = (GpuDeviceBackend) Proxy.newProxyInstance(GpuDeviceBackend.class.getClassLoader(),
                new Class<?>[]{GpuDeviceBackend.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getDeviceInfo")) return info;
                    throw new AssertionError("Unexpected device call: " + method);
                });
        RenderPassBackend backend = (RenderPassBackend) Proxy.newProxyInstance(RenderPassBackend.class.getClassLoader(),
                new Class<?>[]{RenderPassBackend.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setPipeline")) { accepted++; return null; }
                    if (method.getName().equals("close")) return null;
                    throw new AssertionError("Unexpected render backend call: " + method);
                });
        GpuTexture texture = new GpuTexture(GpuTexture.USAGE_RENDER_ATTACHMENT, "Headless target", format, 4, 4, 1, 1) {
            public void close() {}
            public boolean isClosed() { return false; }
        };
        GpuTextureView view = new GpuTextureView(texture, 0, 1) {
            public void close() {}
            public boolean isClosed() { return false; }
        };
        try (RenderPass pass = new RenderPass(backend, device,
                List.of(new RenderPassDescriptor.Attachment<>(view, Optional.empty())), () -> {},
                new RenderPass.RenderArea(0, 0, 4, 4))) {
            pass.setPipeline(pipeline);
        }
    }
}
