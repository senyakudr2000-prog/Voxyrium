package me.cortex.voxy.client.core.backend.blaze3d;

import java.util.Locale;

/** Matches Native's workaround for unreliable discarded-fragment stores on non-Apple Mac GPUs. */
final class Blaze3dDiscardPolicy {
    static boolean needsSampleMask(String os, String backend, String vendor) {
        return os.toLowerCase(Locale.ROOT).contains("mac")
                && backend.equalsIgnoreCase("Vulkan")
                && !vendor.toLowerCase(Locale.ROOT).contains("apple");
    }

    private Blaze3dDiscardPolicy() {}
}
