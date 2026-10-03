# Blaze3D GPU visibility and renderer switching

## Evidence and scope

The fully populated 128-chunk capture submitted roughly 11.5 million opaque quads and
16,500 opaque/water draws per frame. Frame intervals were about 185–200 ms, while geometry
admission and buffer creation took a small fraction of a millisecond. These are CPU/log
observations, not measured GPU execution times. The user's FPS recovery when zooming
also points to visibility/shading work, without proving which GPU stage dominates.

Native generates visibility and indirect draw commands on the GPU, rejects hidden sections
with hierarchical depth, and filters directional face buckets. The previous Blaze3D path
submitted all directional buckets, lacked occlusion and rejected Sodium overlap only at
composition. This pass addresses those GPU differences while retaining eight-byte quads,
configured distance/detail and the useful resident geometry caches.

## Renderer switching

With Iris installed on Minecraft's Vulkan path, its public config wrapper can exist while
`Iris.getIrisConfig()` is null. Calling `areShadersEnabled()` then throws after the old
renderer has been shut down, interrupting creation of Native. `IrisUtil.reload` now skips
reload in that uninitialized state; initialized configurations still reload when enabled.
No Native/Blaze3D selection or capability policy is changed.

## Sodium coverage

The main Sodium render list can be reused across frames while out-of-graph sections are
collected separately. The two collections now have independent lifetimes and publish their
union. Chunk removal retires entries from both. Only built sections with block geometry
claim Blaze3D coverage; empty built sections do not prove terrain coverage. Native's existing
visible-section stream is unchanged.

A compact R8_UINT texture describes the horizontal columns containing visible Sodium
terrain. Covered Voxy geometry is removed at every height, including below Sodium.
Whole covered Voxy footprints are skipped on the CPU. Small interior quads are clipped in
the vertex shader; the fragment shader handles coarse quads spanning coverage boundaries
and holes before atlas sampling. Missing columns retain Voxy fallback. Bounds expand
slightly for bake rounding, and a teleport spanning disjoint collector generations falls
back to an empty mask rather than allocating an enormous texture or inventing coverage.

When transitions are enabled, only the outer edges of present columns retain a Bayer
transition, capped at two blocks. Interior columns remove Voxy completely. Setting the
transition width to zero also removes that edge overlap. The earlier depth-based composite
transition remains a secondary safeguard.

Sodium's captured reverse-Z depth is reprojected into Voxy's extended projection before
terrain submission, allowing hardware depth rejection of hidden fragments. Depth without
Voxy colour is not composited back. The water pass starts from completed opaque depth,
so terrain-hidden water is rejected before its atlas shading and final composition.

## GPU visibility and commands

Opaque candidates are ordered near to far. With more than 512 candidate sections,
the first 256 sections containing opaque geometry render once into the actual colour/depth
target. Those sections are not drawn again in the remaining opaque pass. Together with
Sodium depth, they seed a minimum reverse-Z depth hierarchy. This happens in the current
frame, including during movement; no stale depth, camera reprojection or temporal history
can hide new terrain.

The hierarchy starts with a conservative 4x4 minimum reduction. Power-of-two padding and
sky pixels have zero depth and cannot prove occlusion. Each subsequent level takes another
minimum. A fragment pass projects the eight corners of each baked section AABB, including
bake/float padding, expands screen bounds by two pixels and checks all intersected texels
at a mip spanning at most two texels per axis. Eye/near-plane crossings remain visible.
A section is hidden only when every sampled minimum is in front of its nearest bound,
with a depth margin. Conservative uncertainty retains geometry.

A fixed 65,536-slot origin table uses a rotated integer hash of origin/scale. Full origin
and scale comparisons establish ownership; hash collisions always keep the colliding
section's direct draw. No unrelated visibility result or instance count is consumed.

On devices reporting `drawIndirect`, another fragment pass writes two indexed draw
commands per slot into three RGBA32_UINT texels. The 48-byte slot contains an opaque
20-byte command, a water 20-byte command and eight padding bytes. Hidden sections have
instance count zero. A public texture-to-buffer copy moves those integer words directly
into an indirect buffer, without CPU mapping/readback/waits. Individual indirect draws
then skip all quad vertex invocations for hidden sections. CPU section binds and draw
submission still remain; this is not Native's shared-arena multi-draw implementation.

Without indirect support, the vertex shader rejects invisible sections before fetching
model/biome/light data. Directional rejection also clips rear-facing section buckets,
matching Native's conservative section rule while keeping translucent and double-sided
models. Material bit 32 exposes the existing bakery double-sided classification; smooth
normals are still unnecessary.

The fixed visibility tables use 1.25 MiB GPU/CPU; indirect support adds a 0.5 MiB count
table in each and 6 MiB for the command image/buffer. R8 visibility uses 64 KiB; the R32
hierarchy scales with viewport size. An active frame uploads 1.25 MiB of visibility
metadata plus 0.5 MiB of counts with indirect support, in a few bulk writes. It also makes
a 3 MiB GPU-to-GPU command copy. These deliberate costs trade a small working set for
avoiding large terrain workloads. Geometry stays eight bytes per quad with a 16-byte
section header. Existing geometry/staging/cache budgets are preserved.

## GPU diagnostics and validation

The live benchmark still writes `<game directory>/logs/voxy-benchmarks/latest.txt` and a
session archive. It now adds `GPU` records, sampling every 30 rendered frames with an
eight-slot timestamp-query ring. Results are polled nonblockingly and a slot is reused
only after all issued timestamps are available. Pending queries skip a sample instead
of stalling the renderer. Unsupported timestamp queries disable only GPU profiling.

`SODIUM_DEPTH`, `OCCLUDERS`, `HIZ`, `OPAQUE`, `OPAQUE_COMPOSITE`, `WATER`,
`WATER_COMPOSITE` and `FOG` fields ending in `Ns` measure timestamp intervals after
conversion using the device's timestamp period. HIZ includes metadata uploads and GPU
command generation/copy. These stages differ from nested CPU `FRAME` timings; missing
optional stages are omitted. Geometry admission and other game GPU work outside these
markers are not measured. Draw/quad gauges count submitted candidates, including indirect
commands that may execute zero instances; they are not post-occlusion GPU counters.

Run `./gradlew build --offline` for Java compilation, backend isolation, packaging and
the device-free suite. Coverage checks exercise reused main/extra lists, removals, missing
columns inside coarse footprints, signed coordinates, border expansion, teleport fallback,
hash distribution and real collision ownership. Shaderc compiles all eleven Blaze3D shader
stages, expanding Mojang imports, for Vulkan 1.2 and OpenGL 4.5.

The full build and shader compilation validate source/ABI integration, not live driver
pipeline creation or FPS. The maintainer performs in-game visual checks and A/B captures:
switch Native/Blaze3D both ways; verify Sodium boundaries, caves, coarse terrain, water,
negative/large coordinates, movement and zoom; then compare stationary and moving
128-chunk scenes at equal camera, viewport, detail and FPS limiter. Inspect GPU records
alongside CPU frame timings before attributing the remaining gap to GPU or CPU work.
