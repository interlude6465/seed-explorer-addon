# Requested mod changes — 11 November 2026

Current master checklist: [TODO.md](TODO.md), including the latest performance,
background-search, enchantment-editor and texture reports. The checked items below
record earlier focused evidence and do not close the newly reported regressions.

Work efficiently: short updates, focused checks, no repeated broad audits without
a concrete regression reason. Preserve the existing prediction-audit requirements.
The user owns interactive clients; do not close or commandeer them. The latest
manual client exited normally at 16:54; recheck live processes before rebuilding.

## Follow-up from the live test

- [x] Reduce 2D map rendering work and measure the dense Nether case.
- [x] Group nearby Nether fossils into one counted icon; reveal individual fossils
  progressively as the user zooms in. Keep coordinates and picking consistent.
- [x] Prefetch terrain beyond the visible map and request new windows during a
  held drag, keeping overlap visible while the replacement loads.
- [x] Persist structure selections per dimension, selected dimension and other
  menu options through reopening the map and the 2D/3D round trip.
- [x] Fix Auto search's missing biome-registry error and recognize ordinary item
  names such as `golden apple`. Show clickable suggestions only for supported
  chest/barrel loot across the selected dimensions, including enchanted books.

The pre-follow-up isolated standalone build/check and GameTest compilation passed
in 4m51s (`build/final-review-20261111/final-build.log`). These new changes require
their own checks before that result can be treated as final.

The follow-up live test passed in 47 seconds. A seed-1 Nether window near
`(-7714,-234)` had 2,018 buffered predictions and 64 visible grouped icons;
map render-state extraction measured p50 0.8191 ms / p95 1.1285 ms over 30
samples (CPU preparation only, not total game/GPU frame time). The test held
the mouse button while moving and verified a new buffered raster arrived before
release. It also passed the 2D/3D round trip and map reopening with two retained
filters, plus Auto-mode golden-apple suggestions and dispatch to loot search.
Screenshots were visually checked: no blank strip after the tested drag, and
clickable Golden Apple / Enchanted Golden Apple suggestions are visible.
Archive: `build/reports/oracle-archives/20261111-map-followup/`.
Focused tests retain all 4,096 fossil predictions while drawing 64 groups at
8 blocks/pixel; zooming in reveals all individual markers. Settings tests cover
disk reload, dimension isolation, and world-profile isolation.

## 2D sustained performance follow-up

The map now coalesces worker publications to the latest request, prepares marker groups off the UI thread, and bounds the in-memory structure cache to 256 regions and 100,000 predictions. Wheel events apply their full fractional movement immediately; a new map request is coalesced to the next client tick while the existing raster is rescaled in place. The live follow-up exercised a dense Nether view for 123 seconds: 31,946 predictions across 1,296 regions remained available, cache usage stayed at 256 regions and 6,625 markers, and map extraction p95 was 5.95 ms in the first 30 seconds versus 5.30 ms in the last 30 seconds. Immediate touchpad, reverse movement and stopped-scroll checks passed. Archive: `build/reports/oracle-archives/20261111-map-sustained/`.

The test used a 3 GiB heap, 30 FPS and four-CPU affinity during its sustained
phase. Heap samples were 1,083 / 1,030 / 1,329 / 1,781 MiB; these are live heap
samples, not retained-heap measurements. One integrated-server warning reported
2.3 seconds behind; CPU map extraction timings do not measure total GPU frame
time and do not close the separate whole-PC/GPU stall investigation. The far
32-block/pixel screenshot remains crowded with 683 icons, while every prediction
is preserved. The held-drag screenshot showed complete raster coverage.

## Priority: 3D rendering defects

### Spectator visibility requirements (latest requested behavior)

Research first: vanilla Java 26.3 `Camera.extractRenderState` disables section
smart culling when a spectator camera occupies an `isSolidRender()` voxel.
`Block.shouldRenderFace` still removes covered neighbor faces; this does not
make solid blocks transparent or add fully enclosed faces. Sodium's block
renderer likewise performs face culling and groups quads by normal. See
`docs/SPECTATOR-RENDERING.md` for the researched algorithm and verification.

- [ ] Detect an opaque camera voxel and automatically select cached cave-boundary
  geometry; restore the user's normal view on exit, with stable boundary transitions.
- [ ] Test all six faces independently against neighbor occlusion shapes. Keep
  exposed air/transparent-neighbor faces and reject covered/enclosed faces.
- [ ] Render an exposed face only from its outward-facing side. No indiscriminate
  two-sided faces, blanket transparency or conventional all-world X-ray.
- [ ] Preserve actual cave walls, floors, ceilings and single-block ledges while
  hiding outer surfaces that block an inside-solid camera. Remove broad cutaways.
- [ ] Cache geometry; camera movement must not rebuild full chunks every frame.
  Recheck occupancy only on camera-voxel or relevant terrain changes.
- [ ] Verify: solid hill looking up; solid toward cave entrance; under one-block
  ledge; exposed face front; same face opposite side; solid/cave transitions;
  Nether netherrack; multiple interconnected caves. Include live visuals.

### Implementation checkpoint

The item-search queue now accumulates quantities across distinct chest positions,
with per-result Pin and 3D actions. Live interface checks passed for adding,
editing and removing requests, preserving input on resize, saving a waypoint,
and returning from 3D to the original search screen. Focused probes cover the
Unbreaking III/enchanted golden apple candidate pools, village pools, quantities
across inventories, and avoiding reuse of one book for two requested items.
Nearest-first traversal across tile boundaries/dimensions and lazily replayed
dungeon chunks passed focused probes. A fresh-cache search matched an independent
saved shipwreck chest and its two suspicious stews. The real live search screen
also passed a quantity request across two independent shipwreck inventories,
including Pin and 3D/back actions (`ui-real-loot-search.log`, 57 seconds).
The independent dungeon search also passed, returning the recorded name-tag
inventory at `(6787,-16,-8420)`; all three fresh-cache search fixtures passed
`standaloneSearchOracleProbe` (`search-final-oracles.log`, 17-second Gradle run).

Chest highlighting is now connected to the world renderer, in addition to map
markers. A live screenshot shows the gold chest outline after the map is closed.
Activation checks actual chest distance in 3D, and each frame reapplies the
299-block limit. The active selection is cleared with the chest overlay on world
changes and is also guarded by client-level identity, seed and version. Focused
checks cover the distance boundary and wrong dimensions. The live lifecycle
retest also passed moving out of and back into range, a Nether/Overworld round
trip, and disconnect cleanup (`ui-highlight-lifecycle-retest.log`, 56 seconds).

The dropdown code now preserves scroll on multi-selection, fits visible rows to
the screen, requests dense mineshaft/treasure data for multi-selection, and resets
the version to 26.3. The live test passed multi-selection, scrolling and all three
reset paths; screenshots/log are in `build/reports/oracle-archives/20261111-ui-search-highlights-menus/`.
The selected/unselected structure and Nether screenshots still
require a direct visual comparison at the reported coordinates.

- [ ] Selecting **View in 3D** must preserve the structure and surrounding terrain.
  The same location currently renders correctly when viewed without selecting
  the structure, but focusing it removes most geometry and leaves floating chest
  markers. Reproduce the supplied seed-1 jungle temple near `(-5888,-1536)` and
  chest `(-5884,117,-1527)`. Check selected versus unselected views in all three
  dimensions. Evidence: `bug-reports/20261111-map/structure-selected.png` and
  `structure-unselected.png`.
  Initial code lead: `StandaloneTerrain3DScreen` passes its focused structure as
  an inspection target; `Terrain3DRenderer` then applies a camera-to-target
  cutaway even in surface mode. This is a lead, not a verified diagnosis.
- [ ] Fix the Nether 3D view to resemble the actual terrain at the same position:
  roof, lava, materials, walls and cave shapes. Compare seed 1 near
  `(-5884,32,-1430)`, accounting for camera position and view direction.
  Evidence: `bug-reports/20261111-map/nether-game.png` and `nether-map.png`.
  Initial code lead: `TerrainMaterialPolicy.includeCaveSample` deliberately
  removes the Nether roof band at Y >= 120; the existing policy probe asserts
  that removal. The screenshots also show broader geometry differences, so
  changing this threshold alone is not an established fix.

## Map labels and controls

- [x] 2D hover labels must include abandoned-camp and village variants, e.g.
  **Bamboo Abandoned Camp**, **Plains Village**, **Desert Village**. Cover every
  runtime variant; preserve coordinates and existing tooltip settings.
- [x] Replace left-click cycling on the 2D **Structures**, **Version**, and
  **Dimension** buttons with dropdown selection lists and a **Reset** button.
  Structures support selecting/highlighting multiple types individually.
  Version and dimension each select a single value. Keep choices valid for the
  active version/dimension; reset restores the applicable defaults. Ensure
  dropdown scrolling and dismissing work without accidental map interactions.

## Chest highlighting

- [x] Add **Highlight chests** to structure loot results. Highlight the selected
  structure's predicted containers in the world with a visible color/glow.
  Only activate highlights within 299 blocks of the player in the same dimension;
  recheck range as the player moves. Clear stale highlights on world/dimension
  changes. Keep prediction uncertainty visible rather than implying verification.

## Item search

### Background requests and enchantment editor follow-up

Implemented session-owned requests/results independent of the search screen.
Closing the menu keeps work running; reopening restores its original origin,
requests and result state. Explicit cancel stops publication, and disconnect,
world profile, seed or version changes clear the session. Request rows show red
Searching, Found, Partial, Exhausted, Error or Cancelled; compact windows page the
request list. The **...** editor offers naturally enchanted chest items/books,
selected levels or any level, and multiple compatible properties on the same item.
Validation checks registry levels, supported items, incompatibilities and eligible
loot-table functions. A matching book cannot also satisfy another request.

The apparent dungeon repetition had two causes: nearest-first discovery explores
many distinct adjacent chunks, and found rooms previously invoked the entire same
chunk simulation again to predict inventory. Search now deduplicates tiles/starts
and reuses the captured dungeon containers. Progress counts unique replayed chunks.
An initial bounded structure pass publishes provisional results before dungeon work;
these are explicitly not a nearest guarantee. The ordered pass caps dungeon work at
32 unique chunks or 20 seconds checked between replays, then continues useful
structure search and reports incomplete nearest coverage. One chunk replay can
exceed the time allowance; cancellation suppresses later publication but does not
interrupt shared worldgen resources. Final ordering is retained when no cap is hit.

The live workflow passed in 33 seconds: physical editor clicks selected Mending I
and Unbreaking III, red request rows rendered, closing/reopening retained the same
job/origin/requests, explicit cancellation worked, and a second job completed while
the menu was closed with the independently archived two-stew chest and loot seed.
Seed changes cleared that session. Screenshots were inspected. Focused lifecycle,
multiple-property matching, invalid-item rejection, unique chunk and capped traversal
checks plus three fresh-cache independent inventory fixtures (one wreck, quantities
across two wrecks, and dungeon name tag) passed in 20 seconds.
Archive: `build/reports/oracle-archives/20261111-search-background-enchantments/`.
The later multi-property loot generation possibilities are not an exhaustive
certification of every enchantment combination or random history.

- [x] Add an item picker/search queue with target quantities and relevant item
  properties, including **Unbreaking III enchanted book** and **enchanted golden
  apple x1**. Let the user edit/remove queued requirements.
- [x] Search outward from the original search position. First restrict candidate
  structure families using their loot pools, then predict actual container loot.
  Accumulate matching quantities across chests until the request is satisfied;
  avoid double-counting containers. Match requested enchantment levels/properties.
- [x] Return all contributing chests to the original search screen with quantities,
  coordinates, structure names and confidence. Support waypoints and **View in
  3D** for each result. Provide progress, cancel and explicit bounded-search
  exhaustion/unsupported-result states; do not claim an item exists merely
  because it occurs in a loot pool.

Checked entries have implementation and focused/live evidence; unchecked entries
still have verification work. The Nether renderer follow-up is in progress.
Prediction evidence remains tracked separately
in `prediction-evidence/README.md` and `prediction-evidence/AUDIT-MATRIX.md`.

## Release gates

Release review found two additional issues: PowerShell splatted the module's
single test-file path into characters, and UI loot executors could silently drop
queued requests. The scripts now use explicit arrays and the module self-tests
pass directly on Java 25. Both loot executors retain queued work and skip obsolete
requests; the normal UI smoke now saturates each queue before checking Bastion
and End City publication. That live rerun remains pending renderer compilation.
Downloaded vanilla reference jars are excluded by `seed-cracking/.gitignore`.

- [x] Reconcile the independent loot inventory with the matrix: 52 registry IDs,
  48 in-scope chest-bearing variants, all 48 with nonempty evidence on at least
  two seeds. Dungeon evidence is separate. Trial Chamber loot is deferred;
  End Gateway is skipped. Retain historical counterexamples and discrepancy
  classifications; this does not certify all generation histories or properties.
- [x] Verify automatic cracking through live collection, blue outlines, bounded
  recovery, world/version isolation and explicit candidate application. The
  implementation remains non-exhaustive; emerald-ore recovery remains research.
- [x] Verify item search against independent saved inventories: stew properties,
  quantities across two wrecks, and dungeon loot. The latest catalog/filter
  regression passed all three again (`map-search-final.log`, 22 seconds).
- [ ] Complete the eight requested spectator-camera cases and selected/unselected
  structure comparisons in all three dimensions; inspect the resulting visuals.
- [ ] Retest Bastion and End City container publication after replacing the
  silent-drop UI prediction queues, including rapid selection changes.
- [ ] Run the final standalone `build compileGametestJava` and full UI smoke on
  the completed source, preserving screenshots/logs and artifact hash.
- [ ] Review the final tracked/untracked diff and stage project source/evidence;
  preserve the unrelated `$log` diagnostic outside the commit.
- [ ] Commit and push to `https://github.com/interlude6465/seed-explorer-addon`,
  then verify the remote commit. Push is already authorized after completion.
