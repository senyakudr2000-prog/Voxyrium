// Native avoids OpKill on non-Apple macOS Vulkan GPUs: MoltenVK's helper-thread
// store guards can drop covered fragments' colour while still writing depth.
// A zero sample mask rejects both colour and depth without that conversion path.
#ifdef VOXY_SAMPLE_MASK_DISCARD
#extension GL_ARB_sample_shading : require
#define VOXY_INIT_FRAGMENT() gl_SampleMask[0] = 1
#define VOXY_DISCARD_FRAGMENT() { gl_SampleMask[0] = 0; return; }
#else
#define VOXY_INIT_FRAGMENT()
#define VOXY_DISCARD_FRAGMENT() { discard; return; }
#endif
