# Blaze3D eight-byte quads

## Mesh layout

Blaze3D now uploads Cortex's original eight-byte quad instead of expanding it into
a 32-byte instance. The quad retains face, local position, size or fluid heights,
model ID, biome ID and light values. No smooth normals were present in either
format; face orientation already provides the directional shading information.

Each nonempty section has a 16-byte header containing its world origin and LOD
scale. The same GPU buffer serves as the section uniform and vertex storage:

```text
offset 0:                  section origin XYZ and scale (four floats)
offset 16:                 translucent quads (eight bytes each)
offset 16 + waterCount*8:  opaque quads (eight bytes each)
```

Opaque and translucent draws bind different vertex slices and the same header
slice. The header is at offset zero, satisfying uniform offset alignment without
padding or a second GPU allocation. Shared buffers still provide four corners and
six indices; the vertex shader expands geometry, UVs and fluid heights.

| Cost | Previous instancing | Current instancing |
| --- | ---: | ---: |
| Geometry bytes per quad | 32 | 8 |
| Header bytes per nonempty section | 0 | 16 |
| Geometry bytes for one million quads, excluding headers | 32 MB | 8 MB |
| GPU geometry allocations per nonempty section | 1 | 1 |
| Shared model and biome tables | None | 2.25 MiB |

The quad payload decreases by 75%. Total geometry bytes are `8 * quadCount +
16 * nonemptySectionCount`, so total mesh savings are slightly smaller. This is a
transfer/storage ratio, not a measured FPS improvement.

## Shared GPU metadata

The public Minecraft 26.2 Blaze3D API supports integer texel buffers through
`BindGroupLayout`, `UniformType.TEXEL_BUFFER` and `RenderPass.setUniform`. Minecraft's
cloud renderer uses the same API. No native graphics calls, SSBOs or compute
shaders are required by this change.

Two `RGBA32_UINT` buffers each contain one texel per model, up to 65536 models.
The first holds face metadata for faces 0–3. The second holds faces 4–5, material
flags and either a constant ARGB tint or a biome palette base. A separate
`R32_UINT` buffer contains up to 65536 biome colours. Splitting the model data
keeps each buffer within 65536 texels instead of requiring a 131072-texel buffer.

The vertex shader reads model info, reads the first table when the face is 0–3,
and reads the palette only for biome-dependent tint. These reads reuse shared
metadata across quads. Directional shades and the camera/visibility controls use a 32-byte per-pass uniform uploaded
through Blaze3D's reusable transient memory, preserving the previous eight-bit
shade quantization. Section uniforms add a bind per section. CPU submission remains per section;
GPU-generated indirect commands can now zero hidden sections before vertex processing.
See [the GPU culling pass](blaze3d-gpu-culling.md). Extra vertex-stage table reads may affect stationary performance and
must be measured on the target GPU.

## CPU work and publication

Packing scans model IDs for the required upload version, then copies the native
quad array in bulk on little-endian systems. A word-by-word fallback preserves
the two-word attribute layout on big-endian systems. Packing no longer expands
per-quad face metadata, tint, directional shade or repeated section origins.

The bakery worker prepares immutable native snapshots of model metadata, biome
colours and atlas texture data. One ordered queue publishes all parts of each
update before advancing its uploaded version. Prepared meshes wait for their
dependencies before admission. Biome repacks upload the palette and one coalesced
range of model-info records; unchanged face records are not resent. Existing
resident quads use the updated palette without rebuilding their geometry.

Queued native buffers are released after submission or world shutdown. Admission
retains the previous 64-update/2 ms limit, allowing an initial update to progress.
RAM cache, staging and VRAM geometry budgets remain unchanged. Header bytes are
counted once in actual staging/cache/residency totals; the fixed metadata tables
fit within the existing fixed-resource allowance. Configured distance and detail
are not increased automatically. At equal budgets, large meshes can retain almost
four times as many quads as the previous 32-byte representation.

## Coverage and diagnostics

The earlier progressive-coverage correction remains in place: cold L3/L2 meshes
provide fallback while finer coverage is incomplete. Intermediate meshes can be
skipped when completed child coverage can already replace them. Virtual parents
do not consume a visible reveal slot. The compact format does not change this
coverage policy.

`/voxyLodDebug` reports `quadBytes=8` and `sectionHeaderBytes=16`. The automatic live
benchmark records these values in its header, actual geometry byte totals, and
`modelTableBytes`/cumulative `uploadedTableBytes` in model state. The live file
remains `<game directory>/logs/voxy-benchmarks/latest.txt`.

Compare mesh pack time, buffer creation time, queue waits and frame intervals
during loading, movement and a stationary interval after residency settles. CPU
submission timings do not measure completed GPU transfer time. Shared metadata
upload bytes are additional to geometry bytes and atlas texture uploads.

## Validation

The device-free suite checks section headers, lossless eight-byte quads, 10000
randomized quad/model/biome/light round trips, all six face records, constant tint,
the final palette entry, immutable biome relocation snapshots, and existing
streaming/benchmark/revision policies. Run:

```sh
./gradlew build --offline
```

The full offline build, including backend isolation and the device-free suite,
passed with Java 25. The terrain vertex and fragment shaders were also compiled
to SPIR-V for Vulkan 1.1 and OpenGL 4.5 with the locally cached Shaderc library.
This validates compilation, not a device pipeline
or rendering performance. In-game visual checks remain with the maintainer:
partial and cutout faces, biome boundaries, water/lava slopes, negative
coordinates, LOD transitions and resource reload/world changes. Compare initial
loading and fully resident scenes to separate streaming and drawing costs.
