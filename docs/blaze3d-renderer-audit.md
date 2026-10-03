# Blaze3D renderer audit and live benchmark

This audit compares the diagnostic baseline (`b7c097d0`) against both Native OpenGL and Native Vulkan. The first optimization pass is described separately below. The four recently pulled commits do not change the renderer or shaders inspected here. Findings below are confirmed control-flow and data-layout differences. Their contribution to a particular machine's frame time requires a runtime capture. Compilation and device-free checks passed; in-game visual validation remains with the user.

## Findings, in priority order

Source locations for verification:

| Area | Blaze3D | Native/shared implementation |
| --- | --- | --- |
| Scheduling and visibility | `Blaze3dLodStreaming.buildPriority/skipIntermediate`; `VoxyBlaze3DProbeRenderer.prepareLodTransition/drainTransitionReveals/isCoveredByCoarserMesh` | `NodeManager.makeLeafChildRequest`; `lod/hierarchical/traversal_dev.comp` |
| Synchronous world work | `VoxyBlaze3DProbeRenderer.pollHierarchyAvailability/getNeighborhoodFingerprint/findStoredSections` | `WorldEngine.acquireIfExists`; `ActiveSectionTracker.acquire`; `AsyncNodeManager` |
| Geometry and uploads | `Blaze3dQuadEncoder.put`; `VoxyBlaze3DProbeRenderer.applyPreparedLodMesh` | `BasicAsyncGeometryManager`; `AsyncNodeManager.tick`; `VkNodeGpuOps.multiMemcpy`; `VkUploadStream` |
| Draw submission | `VoxyBlaze3DProbeRenderer.drawOpaqueLods/drawWaterLods`; `core/blaze3d_lod_composite.fsh` | `MDICSectionRenderer`; `VkTerrainRenderer`; `VkTraversal`; `HierarchicalOcclusionTraverser` |

### 1. World acquisition can block the render thread

Blaze3D runs hierarchy selection, availability polling, neighborhood fingerprinting, and storage index discovery inside `refreshLodMeshes`. `WorldEngine.acquireIfExists` is a synchronous acquisition, not a cache-only lookup. `ActiveSectionTracker.acquire` can call the storage loader on a cache miss or spin/yield while another thread loads the same section.

Each complete neighborhood fingerprint acquires 27 sections. Fingerprints run before enqueueing a build, before accepting an upload, and during idle/dirty validation. Even the eight idle checks can make 216 acquisitions per frame. The 1 ms validation and 4 ms traversal deadlines are checked between operations; an individual acquisition cannot be interrupted by those limits. `pollHierarchyAvailability` additionally checks up to 128 nodes without an elapsed-time deadline, including child acquisitions. Storage enumeration and publication/transition preparation are also outside the bounded traversal loop.

The separate nominal per-frame allowances can also accumulate while loading: 4 ms of traversal, 1 ms of dirty/idle validation, 1 ms of initial scheduling, 2 ms of geometry admission, and 2 ms of model texture uploads. If all phases consume their allowances, that already approaches 10 ms before drawing and other game work. This is a scheduling-budget sum, not a runtime measurement; blocking operations and unbounded phases can push it higher.

Native uses `AsyncNodeManager` to manage hierarchy and geometry allocation off the render thread. The render thread acquires published results and records batched GPU work. It still has CPU upload costs, but it does not repeat Blaze3D's synchronous neighborhood validation there.

**Recommended change:** move hierarchy discovery, revision validation, storage scans, and transition preparation to a worker that publishes immutable results. Use change notifications to schedule revision work and keep a strict render-thread admission limit. This is the highest-priority structural CPU issue; it is not yet a measured claim about the largest bottleneck.

**Capture:** `WORLD_ACQUIRE`, `FINGERPRINT`, `HIERARCHY`, `GRID_SCAN`, `SELECTION`, `TRANSITION`, `VALIDATION` and their window maxima.

### 2. Skipping intermediate meshes enlarges the visibility barrier

`skipIntermediate` makes nearby L3 and L2 meshes virtual while a resident coarse ancestor covers them. The transition graph still includes those nodes. A virtual node becomes coverage-ready only after all its required direct children are ready, propagating upward. `isCoveredByCoarserMesh` hides finer meshes as long as a resident transition ancestor exists.

Consequently an L4 root, spanning 512 blocks per axis, can remain visible until the L1 coverage beneath its virtual L3/L2 children is complete. In a fully populated octree this can mean up to 512 L1 nodes instead of eight actual L3 meshes. Real terrain often has fewer nonempty children, so the actual count is lower. Already-uploaded L0 meshes are hidden during this wait. By the time the L4 parent retires, much of the fine detail may already be ready, explaining a large, apparently simultaneous quality jump.

`drainTransitionReveals` also spends its per-frame reveal allowance on virtual parents that have no GPU mesh. Ancestors revealed in the same frame delay descendant reveals to a later frame. These rules were intended to preserve coverage, but virtual-node processing adds latency without displaying an intermediate quality level.

Native's `NodeManager.makeLeafChildRequest` requests the immediate nonempty children of a leaf. The GPU traversal can use those resident children while deeper requests continue. It waits for a sibling set at the next level, rather than deliberately removing two intermediate fallback levels.

**Recommended change:** preserve immediate-child progressive coverage, or introduce spatially masked parent fallback so finished subregions can reveal independently. Skip intermediate meshes only when ready fine coverage can replace their region promptly. Simply retiring an incomplete parent risks holes.

**Capture:** `CAMERA_BRANCH` pending children, resident/hidden/bypassed flags; `MESH` upload timestamps; `HANDOFF` timestamps and `resident=false` reveals.

### 3. Root-first scheduling competes with nearby detail

`Blaze3dLodStreaming.buildPriority` assigns priority zero to every L4 root, regardless of distance. All other levels receive `ring + 1`. This applies to build ordering and prepared upload ordering. A large stored world can therefore spend startup work establishing distant coarse coverage before nearby refinement.

The shared `RenderGenerationService` then applies its own level/retry priority, without the Blaze3D distance ring. Ring order is therefore not preserved end-to-end once tasks reach the worker queue. The global 32-entry pending limit includes work waiting for models and prepared meshes waiting for textures/upload admission. Those waits can fill all slots. Old-view work has no cancellation API.

Native also uses the same level-prioritized mesh generation service, so that part is a shared limitation. Its root tracker adds columns incrementally, while GPU traversal requests refinement from visible branches instead of materializing Blaze3D's full CPU-selected frontier and all its ancestors.

**Recommended change:** establish nearby safety coverage first, interleave remote roots with near detail, and preserve spatial priority through worker execution and uploads. Track/reprioritize old-view work and separate model waits from useful concurrency. Increasing worker or upload limits alone can worsen frame time.

**Capture:** root count, per-request distance/ring/level, first queue wait, model retries, pending ages by distance, and `PENDING_LIMIT`/`TEXTURE_WAIT` counters.

### 4. Sodium coverage is checked after duplicate geometry has rendered

Blaze3D records visible Sodium sections, but neither `selectLodSection`, mesh scheduling, nor `addVisibleLodMesh` uses that set to exclude overlapping geometry. The terrain is rendered into an independent offscreen depth/color target. The composite shader later tests the saved Sodium depth and discards covered pixels.

That test does not remove mesh generation, upload, vertex processing, terrain fragment shading, or the initial offscreen draw. A coarse Voxy surface closer than Sodium's actual surface can also fail the `sodiumCoversSurface` depth comparison and remain visible inside the nearby terrain. Visible-section membership is not proof that every pixel or every loaded chunk is covered.

Native has GPU hierarchical occlusion and section visibility/command generation, integrated with its depth/bound pipeline. This audit does not claim that Native universally avoids every overlapping mesh build.

**Recommended change:** use conservative complete coverage information to suppress fully covered near regions before scheduling/drawing; keep explicit edge and missing-data fallback. Investigate the coarse-surface depth mismatch separately from loading priority.

**Capture:** camera's Sodium section membership, visible Sodium descendant counts, drawn candidate nodes overlapping those sections, and selected/resident levels near the camera. These are observations of visible sections, not a measurement of final pixel overlap.

### 5. Geometry uploads allocate one GPU buffer per section

Blaze3D expands Cortex's 8-byte quad into a 32-byte instance. The extra data includes the repeated section origin, face data, tint/shade, and material/level. `applyPreparedLodMesh` creates a new GPU buffer for every nonempty upload and closes the previous generation after replacement. The 2 ms admission budget cannot interrupt a single buffer creation.

Native stores the original 8-byte quad in a shared geometry arena with separate section/model metadata. `AsyncNodeManager.tick` groups geometry into `multiMemcpy`; Native Vulkan's `VkUploadStream` uses persistently mapped staging storage recycled after frame retirement. Native OpenGL has the corresponding upload-stream implementation.

For equal quad counts, Blaze3D transfers four times the geometry payload. This is a byte-volume ratio, not a measured fourfold slowdown. The present ABI already derives face orientation in the vertex shader and carries no smooth normal vector; removing normals has no further payoff here.

**Recommended change:** shared geometry allocation and staged batched copies first, then a compact quad ABI with section/model metadata fetched on the GPU where the available device API permits it. Retain useful RAM/VRAM caches; cache capacity is not the same problem as per-frame allocation and transfer overhead.

**Capture:** `BUFFER_CREATE`, `UPLOAD_DRAIN`, bytes, applied mesh count, cache hits, allocation failures, staging/geometry budgets and residency. CPU buffer creation time includes API submission/driver work and does not reveal transfer completion on the GPU.

### 6. Drawing scales with section count and has no hierarchical occlusion rejection

Blaze3D performs CPU frustum/ancestor checks and a vertex-buffer bind plus `drawIndexed` for every visible opaque/water section. Water is sorted by section distance each frame. It does not use a HiZ hierarchy to reject hidden terrain before drawing.

Native OpenGL builds GPU draw lists and uses multi-draw indirect. Native Vulkan uses `vkCmdDrawIndexedIndirectCount`, or the fixed-count indirect fallback on MoltenVK, against its shared geometry store. Both paths use GPU traversal/occlusion infrastructure. Their CPU command submission does not require one Java render-pass call per terrain section.

Blaze3D additionally clears full-resolution offscreen targets, copies depth, composites opaque and translucent terrain, and applies fullscreen fog. These passes are GPU bandwidth/shading candidates even when CPU upload timings are small.

**Recommended change:** group section draws and add conservative occlusion before terrain shading, then measure fullscreen-pass cost with GPU timestamps/capture support. A CPU-only log cannot rank GPU passes or prove that RAM-to-VRAM transfer is the dominant cost.

**Capture:** opaque/water draw counts, `CULL`, `DRAW`, `WATER`, `FOG`, frame interval percentiles, and viewport size.

## Automatic continuous benchmark

The implementation starts when a client world is available and Blaze3D rendering is enabled, and ends on world/dimension departure, renderer shutdown, or disabling Blaze3D. It requires no command. It records startup waits even when terrain has not rendered yet. Re-entering or changing dimensions creates a new archive.

Files are UTF-8 text in the active Minecraft instance's game directory:

```text
logs/voxy-benchmarks/latest.txt
logs/voxy-benchmarks/<UTC timestamp>_<process ID>_<session ID>.txt
```

`latest.txt` contains the current session's complete stream and is flushed approximately every 250 ms by a dedicated writer. The timestamped archive retains the same stream after departure. The full absolute live-file path is printed in Minecraft's normal log. It can be read while the game runs. Files are created by the running build, not by editing the source repository.

Optional JVM properties:

```text
-Dvoxy.blaze3d.benchmark=false
-Dvoxy.blaze3d.benchmarkDir=/absolute/path/to/logs
```

The producer queue is bounded to 8192 records and never waits for disk I/O. If the writer cannot keep up, `droppedRecords` explicitly marks incomplete diagnostics. I/O failure reports a warning and disables producers as well as log writing. Request identity follows each prepared mesh so stale worker completions cannot erase newer requests for the same position. A single writer serializes session changes so old-session records cannot overwrite the new `latest.txt`.

Record types:

| Record | Information |
| --- | --- |
| `FRAME` | Every captured terrain frame: camera, interval, nested CPU stage timings, applied mesh bytes/count and draw calls. Water/fog timings attach before the next opaque frame begins. |
| `WINDOW` | Roughly one-second writer windows: interval p50/p95/p99/max, stage averages/maxima, JVM heap, cumulative GC, process CPU and direct/mapped buffer pool gauges. |
| `STATE` | Startup/renderer state, viewport, effective settings, queue sizes, selection reasons, geometry/staging/cache budgets and texture publication progress. |
| `DISTANCE` | L0–L4 counts for selected/ready/resident/active/draw candidates, Sodium overlap and pending ages in 0–128, 128–512, 512–1024, 1024–2048 and 2048+ block horizontal bands. |
| `CAMERA_BRANCH` | Each level containing the camera: readiness, resident fallback, hidden-by-ancestor state, pending children, virtual status, Sodium observations and pending age. |
| `MESH` | Per-request queue wait, section acquisition, generation attempts, model retries, pack time, ready-to-finish delay, source cache, bytes and completion/rejection outcome. |
| Lifecycle/events | Atlas request/callback, mesher readiness, grid rebuild, selection publication, parent handoff, first sampled draw candidates, errors and final session/writer status. |
| `SNAPSHOT_COST` | Measured synchronous snapshot overhead and cumulative stall/retry counters. |

Spatial/state snapshots run every 250 ms during the first 15 seconds, then every second. First observed draw candidates are sampled milestones; they can lag the actual first draw by a snapshot interval. The camera branch is the cell containing the camera's actual Y coordinate, which may be empty above the ground; near-distance histograms provide the broader terrain picture.

All duration fields ending in `Ns` use nanoseconds. Stage timings are nested wall-clock CPU measurements and must not be added together. Worker `acquireNs` includes section acquisition and task-map bookkeeping. Model request/retry processing between attempts is included in total latency but not the generate-only duration. `readyToFinishNs` includes texture/admission waits and the finishing operation. A confirmed empty mesh can increment the existing applied-upload count without transferring geometry.

`readyIncludingEmpty` records prior confirmed fingerprints, including empty meshes; it does not perform fresh synchronous world validation during the snapshot. A dirty mesh can remain in this gauge while its update is pending. Counters measure observed guard/retry occurrences, not unique stalled meshes; several can increase for the same request.

Frame intervals span successive opaque entry points and include other game work, frame limiting and pauses. The final frame uses interval zero because no following frame exists. Writer windows aggregate received records and report their sample count. Process CPU load may be unavailable (`-1`). Native allocations made through LWJGL are not fully represented by JVM direct-buffer pool gauges; the explicit staging/cache counters are necessary.

The benchmark adds diagnostic overhead. `SNAPSHOT_COST` exposes the largest periodic producer cost; writer formatting and disk activity are additional process-wide work. GPU execution, true PCIe/unified-memory transfer bandwidth, VRAM residency reported by the driver, and final pixel visibility are not measured. Avoid assigning those meanings to CPU submission timings.

## Suggested capture and implementation order

Capture world entry, 15–30 seconds stationary, movement across several chunks, a camera turn, and another stationary interval. The same world, camera path, viewport, subdivision/distance settings, shaderpack and FPS limiter are required for a useful Native comparison. This automatic logger currently instruments Blaze3D; Native's control flow was audited from source, not timed by it.

First correlate near L0 upload readiness against L4 handoff time. Then inspect acquisition/hierarchy/transition maxima during low-FPS loading. If CPU work is small but intervals remain high, use a GPU capture before deciding between transfer, terrain draws, and full-screen passes. Implement local reveal and CPU hierarchy separation before relaxing global work budgets; then batch geometry storage/uploads and draw submission.

The device-free verification task now includes live-file visibility, session archive isolation, request-stage serialization, percentile behavior and I/O failure checks. Compilation, streaming/quad checks, live benchmark regression checks (including stale completions and failed producers), and backend isolation passed with Java 25 using `compileJava verifyBlaze3dLodStreaming verifyBlaze3dBackendIsolation --offline`. That baseline added diagnostics and the audit without changing scheduling, transition semantics or mesh layout.


## Implemented first optimization pass

Neighborhood revision reads now run on a dedicated CPU worker instead of inside scheduling, idle validation, or upload acceptance on the render thread. A bounded cache retains up to 32768 fingerprints and queues at most 64 reads plus the in-flight read. Lookup never waits for storage. Unchanged idle checks reuse cached results; change notifications invalidate all 27 affected positions, including diagonals. Missing centers retry after one second. Loader failures remain retryable. Uploads whose validation is pending retain their staging data until a confirmed fingerprint arrives.

The worker owns a separate world reference. Closing discards queued work and prevents stale publication while allowing an in-progress storage read to finish before releasing that reference. It does not interrupt shared database I/O. Cache entries have distinct identities so a read started before an edit cannot overwrite the replacement entry. Pending mesh cleanup likewise uses request object identity, even when two requests have equal fingerprints. Bootstrap failures now shut down the generation service before freeing its bakery.

World revisions are published before change callbacks, including changes that do not request a save. Revision identities also differ across section eviction/reload, preventing a changed section from accidentally comparing equal after its local counter would previously have reset to zero. Concurrent revision advancement remains monotonic.

Build and upload order now uses distance ring first, followed by progressive levels inside that ring. Nearby refinement can run before distant roots; immediate siblings still share the parent ring to complete safe handoffs. The mesh worker accepts this rank and preserves it across model retries. Native callers retain their original priority encoding.

Cold intermediate L3/L2 meshes are retained whenever fine coverage is still incomplete. Existing completed child coverage still avoids redundant intermediate work. Virtual parents no longer consume the visible reveal count or add an unnecessary frame of descendant delay. Coverage checks still retain a real parent until its required children are ready. Hierarchy availability polling now has a 0.5 ms admission deadline between nodes; a single synchronous world read can still exceed it.

The live log adds `ASYNC_FINGERPRINT` records with worker duration and acquisition count, `FINGERPRINT_WAIT` guard counts, and fingerprint cache/queue gauges in `STATE`. The baseline `FINGERPRINT` frame stage remains for log compatibility and no longer measures worker reads; worker durations must not be added to render-thread stages.

Validation: `build --offline` with Java 25, including quad/streaming/benchmark regression checks and backend isolation. Added device-free checks block the loader to verify nonblocking lookup, stale-result rejection, queue pressure, cache reuse/eviction, failure recovery, world-reference release, native priority compatibility, and concurrent revision identities. No in-game or GPU performance improvement is claimed before the user's visual tests and live capture.

Remaining structural work includes synchronous hierarchy selection and storage enumeration, expensive transition preparation/snapshots, per-section GPU allocation/draw submission, the 32-byte quad payload, and conservative Sodium coverage suppression. RAM/VRAM geometry cache budgets and GPU mesh ABI are unchanged by this pass. The next capture should distinguish CPU work, worker throughput and GPU submission costs before selecting the next upload/storage redesign.

## Implemented eight-byte quad pass

The subsequent compact-format change addresses the 32-byte payload described in the baseline audit. Blaze3D now retains the original eight-byte Cortex quad and adds one 16-byte origin/scale header per nonempty section, in the same GPU allocation. Model face records, material flags and biome tints move to three public Blaze3D texel buffers totaling 2.25 MiB. CPU packing uses a bulk native copy after model dependency checks, avoiding per-quad metadata expansion. Ordered bakery snapshots publish metadata, palette updates and atlas uploads together before mesh admission; palette relocations update resident geometry through the shared tables. Biome repacks coalesce model-info writes and do not resend unchanged face records.

Geometry cache and upload byte totals include section headers. The benchmark header reports eight bytes per quad and 16 bytes per section, and model state adds fixed table capacity and cumulative uploaded table bytes. Geometry budgets are preserved. Shader table reads and a section-uniform bind add GPU/CPU submission work, so reduced byte volume must not be read as a measured frame-rate improvement. Per-section GPU allocation and draw submission, hierarchy work and Sodium coverage remain separate optimization candidates. See [the current format and validation notes](blaze3d-quad-instancing.md).


## Implemented GPU visibility pass

The next pass fixes the uninitialized Iris configuration during renderer switching, preserves
Sodium's independently reused main render list, suppresses covered Voxy columns, pre-fills
Sodium depth, and generates conservative current-frame occlusion and indirect commands
through the public Blaze3D API. It also adds sampled GPU timestamps to the automatic live
log. Per-section CPU bindings/submission and CPU hierarchy selection still remain separate
from GPU visibility. See [implementation, costs and validation](blaze3d-gpu-culling.md).
