# Spectator boundary rendering

Research inspected the installed Minecraft Java 26.3 client classes directly
(decompiled locally with Vineflower 1.11.1) and Sodium 0.9.3 beta 4 for 26.3.

- Vanilla `Camera.extractRenderState`: if player is spectator and the camera
  block `isSolidRender()`, set `cameraState.smartCull=false`.
- Vanilla `SectionOcclusionGraph.runUpdates`: smartCull controls section-to-section
  direction/visibility traversal. This is separate from block-face creation.
- Vanilla `Block.shouldRenderFace`: full neighbor occlusion shape hides a face;
  `skipRendering` hides equivalent transparent interfaces; partial shapes use
  `Shapes.joinIsNotEmpty(..., ONLY_FIRST)` with a bounded shape-pair cache.
- Vanilla `ModelBlockRenderer`: evaluates that rule independently per direction.
- Sodium `BlockRenderer.renderModel` calls `prepareCulling(true)` and feeds
  `isFaceCulled` into model emission; `bufferQuad` groups by `normalFace`.

Public source references for the independently inspectable implementations:
- https://github.com/CaffeineMC/sodium
- https://github.com/Vineflower/vineflower
- https://mappings.dev/1.21.1/net/minecraft/client/renderer/SectionOcclusionGraph.html

The map uses cached exposed voxel boundaries, outward-wound triangles and GPU
backface culling. Camera occupancy changes select the cave mesh automatically;
the stored user toggle is not mutated. No radius or focus tunnel deletes geometry.
There is no all-world transparency pass. Camera normals are evaluated by the GPU,
so rotating the camera does not rebuild chunk meshes. Occupancy has a 0.015-block
dead band and is cached by voxel/source revision. Predicted occupancy is a compact
bit mask populated off the render thread, rather than retained vanilla chunks.

## Evidence checkpoint

The pre-spectator Nether fix restored lava and the near wall in the real GPU
snapshot `build/reports/oracle-archives/20261111-nether-3d-visual/mesh-diagnostic/`.
All nine camera-neighbor chunks have their expected lava faces in baked arrays
and uploaded buffers. An independent CPU exposure/mesh diagnostic compared
292,608 source samples and found zero discrepancies, including all 1,869 expected
lava top quads. The old temple preview clipped procedural placement to its initial
height; using full-height writable bounds captured 1,222 blocks at Y116..129.

The eight CPU scenarios also pass: a high-Y hill, cave entrance, one-block ledge,
face front/back, solid-to-cave transition, Nether and connected caves. All six
emitted normals point outward. Boundary jitter of 0.005 blocks retains occupancy;
crossing by 0.03 blocks updates it. Logs are preserved in
`build/reports/oracle-archives/20261111-spectator-and-coast-visual/`.

The live fixed-camera repeat completed successfully and is archived in
`build/reports/oracle-archives/spectator-visual-20261111-184702/`. Occupancy,
cave-mesh selection and outside-air restoration assertions passed. Images show
correct hill backfaces, intact cave walls/floors/ceilings, ledge underside and
front/back behavior, stable solid-to-air transition and visible Nether lava.
The connected-caves image retains boundaries but looks upward; the CPU fixture
provides the explicit connected-air topology check. The test archives its own
screenshots/report before its process exits.

The same run still showed rectangular coast water patches after 30 seconds.
An added 25-chunk nearby-detail assertion caught surface bake starvation. Fixes
now prioritize missing camera-neighbor detail, preserve finer LODs on rebakes,
retry rejected surface admissions and interpolate water tint per vertex. The
25/25 assertion passed in `spectator-visual-20261111-185845/`. That image isolated
the remaining checker to opaque fluid placeholders, which have since been
replaced by the same translucent surface plus seabed as completed chunks.

`spectator-visual-20261111-190329/` passes the nearby-detail assertion and shows
the rectangular opacity grid removed. Constant water alpha still exposed pale
deep seabed and coarse edge gaps. The latest code adds depth attenuation shared
by both water paths and reserves enough fallback vertices for both layers.
It compiles, but its visual test was interrupted to hand the client to the user.
All-dimension selection parity and the two-minute moving-camera resource test
remain pending in `StandaloneTerrainVisualGameTest`; the interrupted run is
archived as `20261111-terrain-final-user-handoff-interrupted/`.

Resource bounds are two stable bake workers, twelve in-flight bakes, eight
generation jobs, 384 MiB of baked CPU meshes and 240,000 vertices per GPU batch.
Nearby exact work precedes broad coverage work. These limits and a short live
run do not prove that prolonged driver/fence stalls are resolved.
