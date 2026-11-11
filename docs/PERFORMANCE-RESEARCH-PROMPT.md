# Independent terrain performance research brief

You are reviewing an actively developed Minecraft map mod. Investigate and propose
an evidence-based performance architecture. Work read-only: do not edit production
code, launch game clients, change settings, commit or push. Other agents are
actively implementing fixes. Put any experiments in a separate scratch directory;
coordinate before resource-intensive profiling. Record the source revision and
dirty-file hashes you inspect so results are reproducible.

## Project and objective

Repository:
`C:\Users\Aubrey Martin\Desktop\meteor-client-master\meteor-client-master\seed-explorer-addon`

Seed Explorer predicts Minecraft terrain, structures and loot from a configured
seed, and displays native 2D/3D maps. It must also work for remote/unvisited areas;
rendering only chunks already supplied by the game is insufficient.

The user wants much faster detailed terrain streaming, substantially farther
visible detail, rapid response while flying into new terrain, and no repeated
game/whole-PC stalls. Nearby and forward-visible detailed terrain should precede
unimportant distant work. Maintain correct caves, structures, dimensions and
materials. Do not equate “all resources on detail” with saturating CPU/RAM/GPU.

## Current technology (verify against current source)

- Standalone Fabric mod for Minecraft Java 26.3; Java 25, Loom 1.18.4,
  Fabric Loader 0.19.5, Fabric API 0.162.0+26.3; Gradle build in `standalone/`.
- Java vanilla worldgen adapters with offline registries/templates, terrain and
  carver replay, natural-feature and structure previews, plus observed chunk data.
- Custom chunk-based meshes, multiple detail levels, persistent GPU batches,
  texture atlas/mipmaps, background mesh packing and budgeted uploads through
  Minecraft's render API. Current machine uses its OpenGL backend.
- Surface heightfields and full exposed-voxel cave boundaries are different paths.
  Spectator-style visibility uses neighbor occlusion and outward face culling.
- Current limits include 2 normal terrain workers, 2 stable bake workers,
  8 generation jobs, 12 in-flight bakes, 384 MiB CPU baked meshes,
  240,000 vertices/GPU batch and 4 MiB upload budget. These are recent safeguards,
  not proven optimal settings.
- Camera exact ring currently 12 chunks (192 blocks); caves 8 chunks; nearby
  vanilla surface ring 2 chunks. Trace actual meanings and scheduling: a radius
  constant does not establish that completed detail reaches that distance.
- Test machine: Windows 11, i9-12900K, 32 GB RAM, RTX 3060. Interactive launches
  currently cap heap at 4 GiB and active processors at 6 after resource stalls.

## Start here

- `docs/TODO.md`, `docs/PRODUCT-BACKLOG.md`, `docs/SPECTATOR-RENDERING.md`
- `src/main/java/me/seedexplorer/addon/render/Terrain3DRenderer.java`
- Adjacent `TerrainLodPolicy`, `TerrainPresentationFrontier`, streaming cursor,
  `TerrainDiskCache`, `TerrainStreamStateStore`, `BlockTextureAtlas` and pipelines.
- `src/main/java/me/seedexplorer/addon/worldgen/GeneratedTerrainHeightmap.java`,
  `WorldgenEngine.java`, `OfflineWorldgenResources.java`
- `src/standaloneFabric/java/me/seedexplorer/standalone/StandaloneTerrain3DScreen.java`
- Visual GameTests under `standalone/src/gametest/java/` and existing logs/screenshots
  in `build/reports/oracle-archives/`.

Recent proven problems: coarse coverage queued before nearby exact work; failed
surface bake admission lacked retry; coarse rebakes could replace finer meshes;
adaptive worker resizing churned threads with expensive thread-local caches;
placeholder and finished water used different geometry/opacity; default unlimited
disk cache scanned all stored files. Fixes are in progress or newly implemented.
One client-close watchdog was blocked in `glClientWaitSync`; this does not alone
prove a GPU driver defect. Do not repeat old diagnoses without checking new source.

## Research questions

1. Profile the pipeline by stage: density/noise, surface/carvers, decoration,
   structure work, mesh generation, allocation/GC, caching/I/O, packing, GPU upload,
   submission/fences and draw cost. Distinguish CPU extraction time from total
   frame latency. Identify duplicated computation and cache eviction/rebuild loops.
2. Propose camera/frustum/velocity-aware priorities, bounded work queues,
   backpressure, retained detail across motion, cancellation and prefetch.
3. Compare clipmaps/multiresolution terrain, greedy or indexed meshing, mesh reuse,
   compact voxel representations, screen-space error and seamless LOD transitions.
   Explain how caves, foliage, water and structures retain appropriate fidelity.
4. Investigate relevant projects such as Sodium, Distant Horizons, Voxy and
   C2ME, plus Rust-based Minecraft implementations. Identify the actual Rust
   project before citing its performance. Cite versions, source links, licenses
   and benchmark conditions. Distinguish loading/precomputed LODs from generating
   fresh seed-predicted vanilla terrain; these are not equivalent workloads.
5. Compare Java optimization against a native Rust/JNI/FFM or GPU-compute option.
   Quantify crossing/copying costs, determinism, vanilla compatibility, maintenance,
   Windows packaging and fallbacks. Recommend native code only for measured
   bottlenecks; do not assume changing language provides the speedup.

## Deliverables

Provide ranked bottlenecks with file/line evidence and confidence; an incremental
Java-first plan; optional architectural/native alternatives with tradeoffs; and a
reproducible benchmark plan. Cover cold/warm cache, static/moving/teleporting camera,
Overworld/Nether/End and at least 10 minutes of movement. Measure time to first
visible terrain, time to complete detailed coverage, distance/detail coverage,
chunks/sec, frame p50/p95/p99/max, retained heap/native/GPU memory, GC pauses,
queue depth and cache reuse. Set explicit target budgets for near detail and
far coverage on this hardware, treating unmeasured speedups as hypotheses.

Recommend the smallest sequence that produces large demonstrable improvements
without sacrificing prediction correctness or responsiveness. Include correctness
checks and a rollback point for each stage. Return research and a concrete design;
the implementation team will integrate it after review.
