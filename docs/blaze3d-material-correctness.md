# Blaze3D partial and cutout material corrections

The reported dirt paths, copper partial models and flowers showed incorrect
colours only in Blaze3D. The active profile used Minecraft's Vulkan backend on
an AMD Mac GPU. The audit found a missing Native shader workaround and atlas boundary issues
that affect partial and cutout faces; their visual effect still requires an
in-game comparison.

## Fragment rejection on Mac Vulkan

Native already avoids SPIR-V `OpKill` on non-Apple Mac GPUs. Older MoltenVK
conversions can misclassify covered fragments as helper invocations when guarding
stores after a discard, leaving depth without the matching colour store.
Blaze3D's terrain shader still used that operation for alpha and Sodium coverage
rejection. Its colour composite, depth prefill and global fog also used discard.

Those four fragment shaders now share a rejection include. The workaround
initializes the output sample mask to one for covered fragments and sets it to
zero before returning for rejected fragments. This suppresses both colour and
depth without `OpKill`. GLSL 330 explicitly requests `GL_ARB_sample_shading` for
that variant; it does not enable sample shading or multisampling on the pipeline.

Both pipeline descriptors are constructed without querying a graphics device.
At draw time, the public device information selects the workaround only for
Vulkan on macOS with a non-Apple vendor, matching Native's device policy.
Other devices use the normal discard shader. The benchmark's `DEVICE` event
records `sampleMaskDiscard=true/false` to confirm the selected policy.

## Baked atlas boundaries

The existing vertex shader expands face geometry by a small epsilon to close
seams. The associated negative texture coordinates could sample a different
face/model's neighbouring atlas tile. The fragment shader now clamps repeated
coordinates to the edge texel centres before sampling. Nearest texel filtering
and the four uploaded mip levels keep those coordinates inside the same face.

The vertex shader also passes the quad's repeated tile limits in the unused bits
of its existing flags. The fragment shader rejects tiles outside those limits,
matching Native's merged-quad boundary check. This prevents the seam expansion
from adding an extra repeated tile at the outside of a cropped quad. The binary
mesh remains eight bytes per quad; no vertex attributes or model tables are added.

Atlas derivatives are computed before any Sodium or alpha rejection, so the
remaining fragments can use gradients from the entire fragment group. Alpha
tests still sample mip zero, and colour still uses the filtered mip sample.

## Verification

- The full offline Gradle build checks backend isolation, streaming and the
  native eight-byte mesh layout. Device-free policy tests cover AMD, Intel,
  unknown and Apple vendors on Mac Vulkan, plus unaffected OpenGL/Linux/Windows.
- Minecraft's actual render-pass validation accepts both depth-prefill variants.
  Tests ensure that building the workaround does not mutate the normal shader's
  defines, attachment state or pipeline identity.
- `scripts/VerifyBlaze3dShaders.py` compiles the production shaders for Vulkan 1.2
  and OpenGL 4.5 using an installed shaderc C library. All 30 variants passed.
  It inspects the generated SPIR-V: every workaround fragment shader writes
  `SampleMask` and contains no kill, terminate or helper-demotion instruction;
  the normal variants retain discard and do not write the sample mask.

```sh
./gradlew build --offline
python3 scripts/VerifyBlaze3dShaders.py --library /path/to/libshaderc.dylib
```

These checks do not render a world or establish that every photographed colour
artifact is resolved. Visual validation of dirt paths, petals, flowers, grates,
glass and fluids remains with the user.
