# Release checklist

Updated 11 November 2026. This is the current task list. Detailed historical
evidence remains in [PRODUCT-BACKLOG.md](PRODUCT-BACKLOG.md) and
[prediction-evidence/README.md](prediction-evidence/README.md).
Only mark an item complete after its stated verification passes.

## Owners

- **Renderer agent:** 3D stalls, detailed-chunk priority, textures, Nether and
  spectator visuals.
- **Search agent:** background searches, request rows, enchantment editor and
  dungeon progress/work bounds.
- **2D performance agent:** accumulating map lag, prediction publication/cache
  bounds and immediate wheel zoom.
- **Main agent:** Bastion template race, disk-cache overhead, integration review,
  final validation, commit and push.

## Priority 1: freezes and performance

- [ ] **3D / whole-PC periodic stalls.** Investigate CPU, heap/native memory,
  GPU upload/fence waits and worker pressure. Bound resource use and measure a
  sustained session. Latest client shutdown watchdog was in `glClientWaitSync`;
  this is evidence to investigate, not a proven root cause.
- [ ] **Detailed terrain first.** Prioritize nearby detailed chunks before distant
  coarse chunks. Keep streaming responsive without exhausting the machine.
- [x] **2D gets slower over time.** Bound prediction/cache/marker growth, keep
  generation off the UI thread, and run a sustained dense-map test. Sustained
  123-second dense Nether map held 31,946 predictions across 1,296 regions;
  extraction p95 was 5.95 ms initially and 5.30 ms in the last 30 seconds,
  with a bounded 256-region / 6,625-marker memory cache. Evidence: `build/reports/oracle-archives/20261111-map-sustained/`.
- [x] **Delayed wheel zoom.** Apply wheel movement as it arrives, preserving
  smooth fractional touchpad input without a long queued zoom tail. Immediate
  fractional, reverse, burst and stopped-scroll checks passed in the map follow-up.

## Priority 2: prediction failures

- [x] **Bastion ConcurrentModificationException.** Reproduced with concurrent
  preview and loot generation for seed 1, start chunk `(-618,-103)`, marker
  `(-9888,-1648)`. Stack identifies shared `StructureTemplate.Palette` HashMap.
  Worker-local template managers fix the shared palette race. Concurrent previews
  and three complete ten-container outputs agree, including loot seeds, metadata
  and display names. Translation contents are copied before resolving names to
  avoid a second race yielding blank labels. Full loot probe passed in 2m17s;
  evidence: `build/final-review-20261111/loot-concurrency-final.log`.
- [ ] **Containers stuck predicting.** Non-dropping loot queues implemented;
  execute saturation regression and live Bastion/End City tests after integration.
  The latest source also keeps every queued result; the live queue regression is
  still required before release.
- [ ] **Final loot discrepancy review.** Preserve honest confidence for village,
  mesa mineshaft, pyramid and beached-wreck generation histories, overwritten camp
  barrels, unresolved Ancient City sculk interactions and unrepresented metadata.
  Do not describe multi-seed sample coverage as universal exactness.

## Priority 3: item search

- [ ] Verify the item finder uses the selected Minecraft version for catalogs,
  inventories, fallback loot, caches and background-session cancellation. Display
  the version in the search screen. Explicit-profile fallback, historical JSON
  modifiers and enchantment filtering fixed. Final-jar version and search probes
  passed (`standalone/build/latest-version-loot-probe.log`, `latest-search-probe.log`).
  Historical generation parity and live version switching still need verification.
- [ ] Verify candidate filtering routes every item/enchantment request only to
  eligible structure loot pools in eligible dimensions. The user's enchanted
  diamond helmet fast exhaustion was resolved by increasing radius; explain
  bounded exhaustion and include useful checked-count/radius information.
- [ ] Add **Infinite** radius: progressively expand outward until requirements
  are met or cancelled, with bounded memory/CPU and no repeated inner-chunk work.
  Continue in background and preserve dimension/world/session isolation. Source
  implementation and focused routing/iterator probes pass; live client workflow
  remains pending. Infinite searches now also remove the finite 32-dungeon cap.
- [ ] Show mutually incompatible enchantment selections in red with an explanation,
  and prevent starting an impossible combination; retain editable selections.

- [x] Search continues after leaving the menu; reopening shows the same active
  requests and results. Cancel explicitly; isolate world/seed/version changes.
- [x] Show each requested item as a visible list row, with a red **Searching**
  state while running and clear found/partial/exhausted/error/cancelled states.
- [x] Investigate apparent repeated dungeon replay for Enchanted Golden Apple.
  Prevent duplicate chunk work, expose meaningful progress, and prevent expensive
  dungeon exploration from hiding useful structure results indefinitely.
- [x] Add a **...** control for naturally enchantable items/books to select
  enchantments and levels. Validate against possible chest loot and search those
  properties, including multiple enchantments when requested.
- [x] Verify background lifecycle, item rows, enchantment UI, progress and
  cancellation in the real client, plus independent inventory matches.

Search evidence: `build/reports/oracle-archives/20261111-search-background-enchantments/`.
Live controls/background result passed in 33 seconds; focused and three independent
inventory probes passed in 20 seconds. Dungeon discovery reuses captured containers
instead of replaying each room again. Provisional structure results appear before
dungeon work, which in finite mode stops after 32 unique chunks or 20 seconds between chunks;
capped results explicitly say nearest is unverified. A single chunk replay is
non-preemptible, so this is not a strict per-job wall-clock guarantee.

Infinite routing evidence: `build/reports/oracle-archives/20261111-search-infinite-routing/`.
End-only four-enchantment helmet routing, actual nonship End-city gear, traversal
beyond 8,192 blocks, negative coordinates, cancellation and retaining 65 contributing
chests pass focused probes. Review found and removed the inherited finite dungeon
budget. Independent chunk and region cursors now interleave dungeon decoration and
structure searches; the scheduler regression checks 65 unique dungeon chunks,
mixed-dimension progress and cancellation. Live Infinite controls remain pending.

## Priority 4: 3D appearance and spectator behavior

- [ ] **Direct underground focus.** Remove the initial surface-height camera target
  while a selected structure's preview is unresolved; keep the resolved orbit
  target stable when later terrain arrives. Packaged focus regression passed in
  `standalone/build/latest-prelaunch-build.log`; live verification pending.
- [ ] Fix noisy/speckled textures and rectangular water/grass color patches.
  Compare real game and map at seed 1, Overworld `(-9887,141,-967)`.
- [ ] Investigate sparse trees and black missing terrain at that same location;
  distinguish loading delays from missing geometry using counters and screenshots.
- [ ] Preserve selected/unselected structure geometry in all three dimensions.
- [ ] Verify Nether roof, lava, walls, shapes and materials against the real world.
- [x] Finish automatic camera-inside-solid detection and stable entry/exit.
  CPU occupancy/jitter probes and live solid/air/restoration checks pass; evidence:
  `build/reports/oracle-archives/spectator-visual-20261111-184702/`.
- [x] Preserve exposed cave boundaries using six-neighbor occlusion and outward
  face culling; no blanket transparency or broad cutaway. Cache mesh/voxel state.
- [x] Run all eight requested spectator scenarios: high hill looking up; cave
  entrance; one-block ledge; face front; face back; solid/cave transition; Nether;
  interconnected caves. CPU scenarios and the first live fixtures passed. Review
  found the Nether shot was in air with Underground enabled, and the connected
  cave shot started in air. New fixtures explicitly start inside netherrack and
  the wall adjoining connected caves; those live checks passed in
  `standalone/build/reports/spectator-archives/20261111-spectator-visual/`.

## Features with passing focused/live evidence

- [x] Two-seed sampled structure-location audit: zero sampled mismatches.
- [x] Inventory evidence for all 48 in-scope chest-bearing variants on >=2 seeds.
- [x] Original village oak RNG fix; seed-1 fixture matches all 13 inventories.
- [x] Observed shipwreck refinement, camp stale-barrel cleanup and session guards.
- [x] Camp/village variant hover names and dropdowns with reset/multiselect.
- [x] Persist map/menu choices through reopening and 2D/3D round trips.
- [x] Fossil clustering and held-drag buffered terrain loading. Latest map retest
  passed; this does not close the newly reported sustained-performance issue.
- [x] Plain-item search and loot-only suggestions; quantity accumulation across
  chests; waypoint and 3D navigation; independent saved-inventory search checks.
- [x] Gold chest highlights within 299 blocks, with dimension/disconnect cleanup.
- [x] Automatic seed cracking, loaded evidence capture, blue structure outlines,
  persistence and world isolation. Live seed-1 recovery passed. Low48 search is
  non-exhaustive; emerald ore remains research, not a supported recovery method.
  Chat feedback now reports distinct structures toward 6, bedrock toward 48,
  biome samples toward 8, total observations toward 512, search ownership and
  honest bounded/no-match outcomes. The live audit showed updates such as
  `0/6`, `upper-bit data threshold reached`, and `197/512 observations saved`.

## Release gates

Disk cache review also removed the unnecessary unlimited-cache startup tree scan,
bounded writes to 256 queued / 2 workers, and stopped overloaded persistence from
running on the submitting thread. Saturation/round-trip/cap probes passed in
`build/final-review-20261111/cache-and-loot-regression.log` (renderer task passed;
the later loot-name failure in that run was fixed and retested separately above).

- [ ] Update this list and detailed evidence after every completed fix.
- [x] Final standalone build, packaged probes and GameTest compilation.
- [x] Final UI smoke, sustained performance checks and visual inspection. The
  120-second moving-camera audit completed 241 samples with changing camera
  coordinates; evidence is archived in
  `standalone/build/reports/terrain-moving-archives/20261111-terrain-moving/`.
- [x] Review source/docs/artifacts; exclude unrelated `$log` and downloaded jars.
- [ ] Commit and push to `https://github.com/interlude6465/seed-explorer-addon`.
- [ ] Verify the pushed commit and record the final jar hash.

Trial Chamber loot remains deferred; End Gateway remains intentionally skipped.
No final commit or push has occurred.

Latest handoff and running-client/artifact details: `../ai agent continueing.md`.
Build + packaged probes + GameTest compilation passed at 20:01; the last historical
loot edits were packaged and covered by targeted final-jar probes afterward.
The newest interactive client is running for user testing; release gates remain open.
