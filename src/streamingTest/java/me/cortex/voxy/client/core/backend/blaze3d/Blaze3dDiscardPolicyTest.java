package me.cortex.voxy.client.core.backend.blaze3d;

final class Blaze3dDiscardPolicyTest {
    static void run() {
        check(true, "Mac OS X", "Vulkan", "AMD");
        check(true, "macOS", "Vulkan", "Intel");
        check(true, "Mac OS X", "Vulkan", "Unknown");
        check(false, "Mac OS X", "Vulkan", "Apple");
        check(false, "Mac OS X", "OpenGL", "AMD");
        check(false, "Linux", "Vulkan", "AMD");
        check(false, "Windows 11", "Vulkan", "Intel");
        check(true, "MAC OS X", "vulkan", "amd");
        check(false, "macOS", "Vulkan", "APPLE INC.");
        System.out.println("Blaze3D fragment rejection: non-Apple Mac Vulkan workaround and unaffected device checks passed.");
    }

    private static void check(boolean expected, String os, String backend, String vendor) {
        if (Blaze3dDiscardPolicy.needsSampleMask(os, backend, vendor) != expected) {
            throw new AssertionError("Wrong fragment rejection policy for " + os + "/" + backend + "/" + vendor);
        }
    }
}
