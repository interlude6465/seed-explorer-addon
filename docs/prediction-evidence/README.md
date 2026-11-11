# Minecraft 26.3 prediction evidence

## Latest 2D/search follow-up

The user's Auto-search screenshot identified two real issues: ordinary item names
were sent to natural structure/biome search, and that path requested dynamic biomes
from the builtin-only registry. Auto mode now recognizes supported chest item names;
biome queries use the offline dynamic registries. Suggestions are drawn only from
the supported chest/barrel tables across the chosen dimensions, and invalid
enchantments are rejected. Search mode, radius and dimension scope persist.

The map now keeps a drawable margin around the viewport, reuses aligned biome
samples, and requests replacements during held dragging. Equal neighboring biome
cells share one draw call. Nether fossils form counted world-space groups that
split with zoom; the original predictions remain available. Structure filters are
saved per world and dimension, including the 2D/3D round trip.

`runClientGameTest -PmapFollowupAudit=true` passed in 47 seconds, including held-drag
prefetch, grouped markers, menu persistence, and Auto-item suggestions/dispatch.
At seed 1 near Nether `(-7714,-234)`, 2,018 buffered predictions produced 64 visible
icons. Map CPU render-state preparation measured p50 0.8191 ms and p95 1.1285 ms;
these are not total frame-time or GPU benchmarks. Screenshots were visually
inspected. Evidence is in `20261111-map-followup` under the local oracle archives.
The independent search inventory fixtures remain a separate verification gate.

The earlier isolated full standalone build/check and GameTest compilation passed
in 4m51s (`build/final-review-20261111/final-build.log`). New follow-up source and
the spectator-style renderer work still require final build/review before push.

## Latest bounded coverage and lifecycle checks

The snowy-village/outpost run completed with zero sampled position, loot-seed,
or compared-item mismatches across seeds `123456789` and `Long.MIN_VALUE`:
14 containers, ten matching modeled inventories, four unverified goat-horn
instrument comparisons, and ten matching initial layouts. All eight snowy-village
inventories matched. Report: [snowy/outpost coverage](26.3-snowy-outpost-coverage.txt).
The matrix now has nonempty inventory runs on at least two seeds for all 48
in-scope chest-bearing registry variants, plus the separately audited dungeon
feature. This is bounded coverage, not a claim of zero mismatches in every
generation history. [Discrepancy classifications](DISCREPANCY-CLASSIFICATION.md)
retain unresolved interactions, independently proven history dependencies, old
corrected bugs, and unsupported properties.

The item-search oracle also passed a fresh-cache quantity request across two
independently recorded shipwrecks on `Long.MIN_VALUE`: three poisonous potatoes
at `(-5356,41,6389)` plus six at `(-5285,53,6543)`. Both positions and loot seeds
match the broad live report. Log: `multi-wreck-search-oracle.log` in
`20261111-ui-search-highlights-menus`. The live screen then passed the same real
two-wreck search, showing both contributing inventories and allowing Pin and
3D/back navigation. That run completed in 57 seconds, exit 0, with no interrupted
or closed resource-channel errors. Log: `ui-real-loot-search.log`; visually reviewed
screenshot: `live-two-wreck-search.png` in the same archive. The screenshot's
compact window displays one row per page and reports two contributing chests.
The dungeon search then independently returned the live-recorded name-tag chest
`(6787,-16,-8420)`, seed `1`, loot seed `3660284170677919530`. The same production
traversal includes structure and dungeon candidates; ordering/cancellation is
covered by the focused nearby-dungeon/farther-shipwreck regression. All three
fresh-cache independent searches passed `standaloneSearchOracleProbe` and
GameTest compilation in 17 seconds (`search-final-oracles.log` in the UI archive).

The live highlight/menu retest passed in 56 seconds: outlines disappear beyond
299 blocks, return on approaching, and clear on dimension changes and disconnect.
It also checks actual client chunk-cache residency. In 26.3, `hasChunkAt` alone
accepts unloaded chunks; chest observation and predicted mining now use the
actual cache. A separate live cracking test exposed interrupted minimap template
reads closing the shared resource archive. Minimap cancellation now uses the
request predicate rather than interrupting I/O; ore-target cancellation also
rejects stale results without interrupting shared-resource reads. The following
snowy/outpost audit contained no closed-channel or interrupted-channel errors.

## UI/search and world chest highlights verified (latest continuation)

The subsequent nearest-candidate traversal probe passed across an adjacent
negative-coordinate tile and a second dimension. Structure replay now waits for
every tile that can contain a nearer structure before consuming a candidate.
An end-to-end search using fresh caches also passed against the independently
recorded shipwreck at seed `-7046029254386353131`, start `(752,967)`: it returned
chest `(12037,42,15480)`, original loot seed `8220817990207311449`, and the two
recorded suspicious stews. Run `standaloneSearchOracleProbe`; the final direct JVM
probe output is archived as `frontier-search-probes.log` in the UI archive below.
Dungeon rooms subsequently joined this traversal with lazy chunk subdivision;
the focused ordering/cancellation probe and independent dungeon inventory search
passed, as described in the latest checkpoint above.

The production search now accumulates quantities across distinct predicted chest
positions, preserves individual chest results, and filters replay candidates by
loot pools. Enchanted books produced from ordinary-book entries are included;
village and archaeology table IDs are covered. Specific enchantment requests are
allocated before generic requests so one book is not counted twice. The later
live two-structure search and independent dungeon search passed; see the newest
checkpoint above for their exact evidence and scope.

The live smoke test now exercises item queue add/edit/remove, input preservation,
per-result Pin and 3D navigation back to the same search screen; it also exercises
multi-select structure dropdown scrolling, version reset to 26.3 and dimension
reset. A bug that excluded mineshafts/treasure when selecting multiple types was
fixed. The menus now fit the available height. Search input/suggestion code
preserves the requested enchantment and quantity.

`StandaloneChestHighlightRenderer` now renders gold outlines in the actual world,
using the native gizmo hook. Both the 3D sidebar and structure details can activate
it. Selection is scoped to the client-level object and prediction context; the
existing chest-overlay reset clears it. Activation and rendering enforce 299
blocks from each chest in three dimensions, in the same dimension. Focused probe
checks cover the exact distance boundary and wrong dimensions. The live test
toggles a nearby highlight, closes the map, captures its visible outline, restores
the map and turns it off. The later lifecycle retest passed live movement out
of and back into range, dimension changes in both directions, and disconnect
cleanup. It also passed the loaded/unloaded client-chunk guard and the ordinary
map context menu after scrolling a dropdown. Log: `ui-highlight-lifecycle-retest.log`
in the UI archive (Gradle success, 56 seconds, exit 0). The initial run's menu
failure was a test which did not dismiss the previous overlay before clicking
the toolbar; that failed run is retained separately.

`standaloneSearchProbe runClientGameTest` completed successfully (Gradle 56s).
The PowerShell redirected wrapper returned code 1 because native stderr warnings
were formatted as RemoteException records, but the Gradle log explicitly ends
`BUILD SUCCESSFUL`; earlier nonredirected runs also exited 0. Evidence is in
`build/reports/oracle-archives/20261111-ui-search-highlights-menus/` (test log and
three screenshots). Screenshots were inspected: the gold world outline is
visible, the search queue summary fits, and both dense structure types remain
checked in the scrolled menu.

The previous plain smoke runs did not test these new interactions. No broad
prediction-oracle claims are changed by these UI tests. No final build, commit or
push has occurred. The rendering agent has been resumed for direct Nether/3D
comparisons; coordinate builds with it before launching another shared client.

## Trace-order replay matched all four mineshaft inventories

`standalonePlacementHistoryProbe -PhistoryTrace=<path>` now filters the live
reference trace's 100 enclosing-box chunks down to the 59 chunks intersecting
actual pieces, preserving their observed order. Missing/duplicate placement
chunks still fail validation. The near-south history reproduces all four live
positions, tables and loot seeds, including `8389095278590296504` at
`(-10185,80,956)`. All four generated inventory signatures match the independent
completed oracle, including stack sizes and recorded enchantment properties.

`compare_trace_replay.py` performs that comparison and passed against
`26.3-mineshaft-mesa-south-near-history.txt`. Archive:
`20261111-mineshaft-trace-order-replay` (isolated compilation arguments/log,
replay arguments, output and comparison). The production canonical prediction
is unchanged and still returns three carts. Observed server generation order
is not available from an ordinary seed-only request; this validates the cause,
not a production correction or general exactness claim.

New user requests and screenshot references are in `../PRODUCT-BACKLOG.md`.

## Interactive client handoff

The user requested the current mod again. A normal interactive 26.3 client was
launched into `standalone/run/manualTest/saves/Seed Explorer Test`, with the
current seed-cracking implementation and the profile still set to prediction
version 26.3. Launch log: `standalone/build/manual-current-mod-client.log`.
This is the user's session; do not close or take control of it for audit work.
Avoid resource-processing/build changes while it reads the shared development
classpath. The Nether visual issue remains investigated but unfixed.

The first trace replay failed complete-chunk-order validation before filtering
inert reference chunks. Its failed diagnostic log remains preserved at
`standalone/build/mineshaft-mesa-trace-order-replay.log`; the successful isolated
replay above supersedes that diagnostic failure. No shared client class/resource
outputs were rebuilt for the successful check.

## Mesa-mineshaft fourth cart reproduced: generation-history dependency

The missing cart from the broad baseline has now been independently reproduced
at the same position. Seed `-9223372036854775808`, mesa start `(-636,59)`,
with first requested chunk `(-637,61)` generates **four** carts. The earlier
western, northern and farther-southern approaches each generated **three**.
Every run matched the same initial 176-piece layout. The unchanged predictor
returns three carts and matches their positions, tables, seeds and inventories
in all four runs. The fourth live cart is at `(-10185,80,956)` with table
`minecraft:chests/abandoned_mineshaft`, loot seed `8389095278590296504`, and a
fully recorded inventory with no unrepresented components.

The opt-in trace records `createChest` at that exact position, `inside=true`,
cave air above stone, and `placed=true`. The surrounding `postProcess` is the
selected start's corridor in placement chunk `(-637,59)`. This reproduced
case is actual generation, not cart movement or a neighboring cart being
misattributed by the inventory scan.

The earlier corridor `(-10188,72,955)..(-10186,74,969)` already has its cave
spider spawner placed when live generation reaches `(-637,59)`, because
`(-637,60)` was generated first. The offline canonical order reaches it with
`hasPlacedSpider=false`. The two executions enter that corridor at the same
relative RNG count (144), but consume 150 draws live versus 155 offline.
Later in the same chunk live placement creates the fourth cart. The trace
also records other history-dependent corridor differences; reproducing the
entire live order offline remains a separate, uncompleted check.

| First requested chunk | Actual/predicted carts | Oracle failures | Report |
|---|---|---|---|
| `(-638,60)` | 3 / 3 | 0 | `26.3-mineshaft-mesa-west-history.txt` |
| `(-637,57)` | 3 / 3 | 0 | `26.3-mineshaft-mesa-north-history.txt` |
| `(-637,62)` | 3 / 3 | 0 | `26.3-mineshaft-mesa-south-history.txt` |
| `(-637,61)` | 4 / 3 | 1 missed cart | `26.3-mineshaft-mesa-south-near-history.txt` |

Each report has a corresponding `20261111-mineshaft-mesa-*-history` archive
containing the raw trace, comparison and logs. The `south-near` archive also
preserves the same partial world-region extract described below. The original
baseline and the reproduced miss remain in the matrix. This is a diagnosed
limitation of seed-only canonical cart prediction, **not a production fix or
an exactness guarantee**. Changing the canonical order would exchange which
history matches; it cannot make these different vanilla outputs identical.

## Broad rerun and mineshaft spider-spawner history trace

The original two-seed Overworld filter was rerun on the current source with
mineshaft placement tracing. It completed in 9m32s: 44 structure/seed groups,
52 matching initial layouts, no missed/extra structure starts, 412 actual and
412 predicted containers, 370 modeled inventory matches and nine inventories
with unmodeled metadata. The 64 failures were 22 missing plus 22 extra desert
pyramid container positions, nine missing plus nine extra beached-shipwreck
positions, and two Ancient City loot mismatches. Equal total container counts
do not imply matching positions. Trial Chamber loot remained deferred.

Report: [broad mineshaft retrace](history-retests/26.3-overworld-broad-mineshaft-retrace.txt).
The archive `20261111-overworld-broad-mineshaft-retrace` contains the full
report, logs, trace, RNG comparison and summary JSON, plus `level.dat` and
the extreme-seed region/entity/POI files at region X -21/-20 and Z 1/2.
These are a partial saved-world extract, not a complete playable world.

The mesa mineshaft at seed `-9223372036854775808`, start `(-636,59)`, had
three actual carts in this rerun, all matching. The baseline's fourth cart
at `(-10185,80,956)` was **not reproduced in this broad rerun**; the later
near-south approach above reproduces it. The broad rerun report
is deliberately retained under `history-retests`. The matrix now includes those
reports with separate retained-history scopes, so this intermittent counterexample
cannot be silently erased by another passing run of the same scope.

`compare_mineshaft_trace.py` now compares corridor events with RNG counts
relative to each structure's placement entry. It preserves unpaired entries
and reports the first difference; equal draw counts alone do not establish
equal random values, terrain or loot. The earlier isolated regional trace
has three differing placement chunks despite all three carts matching.

A new opt-in trace logs `spiderCorridor` and `hasPlacedSpider`, without drawing
random numbers or changing placement. A fresh western-approach run requesting
chunk `(-638,60)` first confirms the cause of two of those differences:
the corridor bounding box `(-10177,77,958)..(-10173,79,960)` has
`hasPlacedSpider=false` live but `true` offline in chunks `(-637,59)` and
`(-637,60)`. Live/offline corridor consumption is 49/48 draws in both chunks.
Vanilla draws the spawner-position `nextInt(3)` only while the spawner has not
yet been placed. The offline canonical order places it in `(-636,59)` first;
live western placement reaches that chunk later. The initial layout and all
three cart positions/seeds/inventories still match, so this confirms an RNG
history dependency, **not the origin of the missing fourth cart**.

Western-approach report: `26.3-mineshaft-mesa-west-history.txt` (zero failures,
1m22s). Full trace, comparison and logs: `20261111-mineshaft-mesa-west-history`.
The original mesa-mineshaft counterexample and broader completion gates remain open.

## Restored-source desert and taiga retests

After removing the blanket vegetation prefill, both starts that failed during
the experimental all-variant run completed isolated live checks with the same
1200-tick case timeout. Seed 1 desert start `(442,-935)` matched its initial
layout and all three chest positions, loot seeds and modeled inventories in
53s, without the `wall_corals` exception. Seed `123456789` taiga start
`(110,-929)` matched its initial layout and all nine chest positions, loot
seeds and modeled inventories in 1m18s, without timing out. Both reports have
zero failures and zero metadata-unverified inventories. The 12 conservative
unverified labels are the family exactness flag, not failed comparisons.

Reports are `26.3-village-desert-restored-source.txt` and
`26.3-village-taiga-restored-source.txt`; logs and report copies are archived
in `20261111-village-restored-source-retests`. These targeted checks do not
replace the broader histories or certify villages generally. The prior
experimental failures remain preserved with the rejected implementation.

## Suspicious-stew property validation completed for recorded fixtures

The standalone loot probe now checks four independently archived vanilla
`shipwreck_supply` inventories from `26.3-map-targets-two-seeds.txt`. All seven
stews retain their recorded effect IDs and durations: blindness 100/120/140,
jump boost 140/180/200, and saturation 8. Both compact `ItemLoot` output and
typed vanilla inventory-fill components match. Separate effects/durations are
not merged. Opening-time comparison accepts the matching approximate prediction,
rejects changed effects and durations with unchanged item counts, and leaves
counts-only comparisons unverified. This is loot replay at independently
observed seeds/origins, not validation of beached-wreck placement histories.

`standaloneLootProbe compileGametestJava` passed in 2m08s, including existing
village/mineshaft/dungeon/map/cache regressions. A fresh independent live world
also matched the normal shipwreck at seed `-7046029254386353131`, start
`(752,967)`: initial layout, the single chest's position and loot seed, all
filled stacks/enchantments, and both jump-boost stew durations (180/200 ticks).
The live oracle completed in 33s with zero failures and no unmodeled metadata;
its sole unverified label is the conservative family exactness flag. Report:
`26.3-stew-properties-live-shipwreck.txt`. Both logs and report are archived in
`20261111-stew-properties-verified`. Older unverified rows remain historical
evidence; this bounded check does not certify all item properties or families.

The matrix now reports loot-simulation exceptions separately from inventory-fill
comparison errors. A failed simulation is not an empty-container scan; partial
counts are marked as partial. Six evidence-retention tests pass, including
exceptions occurring alongside otherwise matching inventories and exceptions
with all chest counters zero.

## Savanna generation-history experiment: independently confirmed

On the restored production simulator, seed 1 village start `(-16,-435)`
matches all six positions and five inventories in the default approach. The
chest `(-271,91,-6956)` has live loot seed `-5170260692392348826` versus
predicted `-5738277090587463668`. The new pile-input trace shows the first
divergence at attempt 13, `(-273,91,-6951)`, count 114 relative to the feature:
both see air, but the support is grass block live versus dirt path offline.
Vanilla `BlockPileFeature.mayPlaceOn` draws `nextBoolean()` for dirt paths and
does not do so for grass blocks. This shifts the subsequent RNG, with the
melon feature consuming 198 draws live versus 200 offline. The support is in
the adjacent chunk, west of the placement chunk `(-17,-435)`.

An independent fresh-world run requesting `-PpredictionOracleFirstChunk=-19:-435`
before the same explicit start produces live chest seed
`-5738277090587463668`. All six positions, loot seeds and modeled stack
inventories match, with the initial layout matching and zero oracle failures
(1m08s). The corresponding melon trace inputs and draw counts now agree. A
different pile at `(-221,79,-6958)` still differs in RNG without affecting the
sampled chest inventories; that is not asserted to be fixed.

This is direct evidence of different vanilla chest loot for the same seed,
start/layout and chest coordinate under two chunk-generation histories.
The default-history mismatch is retained; the western-approach pass does not
erase it or establish exact seed-only village loot. Current reports are
`26.3-savanna-pile-input-trace.txt` and `26.3-savanna-west-approach.txt`, with
full client logs, traces and comparisons archived under the corresponding
`20261111-savanna-pile-input-trace` and `20261111-savanna-west-approach`
directories. Both scopes appear separately in the regenerated matrix.

## Latest: vegetation-prefill experiment rejected

Preparing natural vegetation with the village start supplied to Beardifier
reproduces the short grass at `(-4243,115,10517)` that the live flower reads.
Without the village start, the same natural-decoration probe finds no natural
block there. `naturalDecorationProbe` now accepts `-PinspectPosition=x,y,z`
and `-PvillageStart=chunkX,chunkZ` (plains) and saves an inspection file under
`build/` because the headless runtime logger can suppress console output.

An experimental blanket vegetation prefill matched all three inventories in
each focused plains fixture (seed 1 and 123456789), including the previously
failing weaponsmith. However, its trace introduced additional feature RNG
differences: vegetation from later generation steps was visible too early.
The all-five-variant/two-seed retest completed in 10m39s with 11 initial layouts
matching, 29 compared container positions, 28 modeled inventory matches, one
savanna loot-seed mismatch, and two failed simulations: seed-1 desert start
`(442,-935)` hit an unbound `wall_corals` tag, and seed-123456789 taiga start
`(110,-929)` timed out. Failed simulation counts must not be read as evidence
that those starts contain no chests.

**The blanket prefill has been removed from production source.** Its two
focused passes do not establish a village fix. They are diagnostic evidence
under `20261111-village-natural-prefill-retest`; the broad report is in
`20261111-village-natural-prefill-all-variants`. Neither rejected-experiment
report replaces current-source matrix rows. A subsequent focused savanna run
with the experiment retained the mismatch; its trace in
`20261111-village-savanna-focused-retest` identifies `pile_melon` at
`(-271,91,-6952)` consuming 198 live draws versus 200 offline. New opt-in
`StandaloneBlockPileTraceMixin` diagnostics record each pile attempt's state,
support state and RNG count without consuming RNG, to investigate that
remaining divergence on the restored production implementation.

## Continuation: current-source village retest

The current source was rerun against the unresolved plains-village fixture at
seed `123456789`, start `(-266,658)`, with a fresh live/offline trace. All three
chest positions matched; two inventories matched supported properties and the
weaponsmith chest at `(-4226,117,10527)` retained the loot-seed mismatch
`3569840482288194909` predicted versus `3963027861913339195` live. The initial
piece layout matched. This confirms the discrepancy is still present and is
not cleared by the successful east-approach retest.

The new trace pairs 12 feature calls and finds five differences. The flower
feature in chunk `(-265,657)` has identical attempt positions through 61, then
reads short grass live and air offline at `(-4243,115,10517)` on attempt 62;
the offline feature consequently consumes two extra RNG draws (458 versus
456). A hay-pile feature in chunk `(-267,656)` also consumes 211 versus 210
draws without a captured predicate divergence. These are open environment and
RNG-history differences; no correction is claimed. The isolated oracle report,
trace, and comparator output are retained at
`20261111-village-current-source-audit/`; the report is copied to
`26.3-village-current-source-audit.txt` and included in the matrix.

Reproduce with Java 25:

```powershell
$env:JAVA_HOME='C:\Users\Aubrey Martin\.codex\java\zulu25.32.21-ca-jdk25.0.2-win_x64'
.\gradlew.bat --% -p standalone runClientGameTest -x processResources -PpredictionOracle=true -PpredictionOracleSeeds=123456789 -PpredictionOracleTypes=village_plains -PpredictionOracleStarts=123456789:-266:658 -PpredictionOracleDimensions=0 -PpredictionOracleQueryAttempts=1 -PpredictionOraclePredictedSamples=1 -PpredictionOracleCaseTimeoutTicks=1200 -PvillageRngTrace=true -PvillageRngTracePath=../../../build/reports/oracle-archives/20261111-village-current-source-audit/village-live-rng.log --no-daemon --console=plain
python docs/prediction-evidence/compare_village_trace.py build/reports/oracle-archives/20261111-village-current-source-audit/village-live-rng.log
```

## Continuation: sampling history and cracking launch

The remaining-family two-seed audit completed in 29m06s. Its 56 structure/seed
groups matched all 61 starts and initial layouts; it captured 426 actual versus
427 predicted containers, 292 modeled inventory matches, and 67 inventories with
unmodeled map properties. All 18 camp variants have initial samples on both
seeds. The report records 96 failures: 57 missing/extra container positions,
two loot-seed differences (beached wreck and savanna village), and **37 invalid
inventory-fill comparisons**. The latter occurred when a concurrent Gradle
`processResources` refresh temporarily removed archaeology resources at
10:00:08–10:00:09. They are test-environment errors, not demonstrated item
differences, and require an isolated extreme-seed trail-ruins retest. Future builds
must use `-x processResources` while clients read the shared resource directory.
Raw report/log: `20261111-remaining-families-two-seeds`; report copy:
`26.3-remaining-families-two-seeds.txt`. No failed comparisons are erased.

The archaeology-resource correction matched all 90 sampled trail-ruin inventories
on seed 1, and ocean-ruin archaeology now compares successfully. Exploration-map
properties remain unverified (saved live camp stacks carry map IDs, decorations
and translated map names beyond the compact item model). The original first-seed
checkpoint and saved pyramid region remain in
`20261111-remaining-families-first-seed-checkpoint`.

The same seed-1 desert pyramid at start `(-700,-126)` now has its four chests at
Y=57, versus Y=58 in the earlier broader baseline. Initial piece layout matches;
the new run records 10 missing and 11 extra container positions, including
archaeology. A bounded replay of its four possible first chunks yields chest
Y=56, 57 or 58; first chunk `(-700,-125)` matches all 11 saved-world container
positions, including archaeology. Replay log and independent saved-region
comparison: `20261111-pyramid-placement-histories`. This is not seed-only exactness.
Observed refinement now supports pyramids and suspicious sand/gravel, selects
only one distinct complete output, and retains position-only results when
ambiguous. Renderer probe and GameTest compilation passed. The first independent
live adjacent-approach test matched all 12 containers and inventories and passed
the unique observed-refinement check; it generated the canonical Y=58 history,
and a southern-approach retest also generated that canonical Y=58 history.
Archive/report: `20261111-pyramid-observed-adjacent-approach` and
`26.3-pyramid-observed-adjacent-approach.txt`, plus
`20261111-pyramid-observed-south-approach` and its matching report.

The actual correction is independently verified against the saved Y=57 world:
`StandaloneSavedPyramidProbe` reads the archived region in read-only mode, decodes
its real block-state palettes into observation snapshots, and runs the production
refinement. All **11 positions and all saved inventory stacks/components match**,
including four chest inventories and seven archaeology items. Canonical output
contained 12 positions and did not match. This test checks stack counts and full
Minecraft component equality, not only item IDs. The saved containers were
already unpacked, so original loot-seed tags are absent; the reported predicted
seeds reproduce those independently saved inventories, but are not a direct
original-tag comparison. Region SHA-256:
`3f8f8b05badd0cafbbf511e47fd2f7643f00d1d3fc5b4722f8600f60a68935ae`.
Run `.\gradlew -p standalone standalonePlacementHistoryProbe -x processResources
"-PhistoryRegion=../build/reports/oracle-archives/20261111-remaining-families-first-seed-checkpoint/pyramid-r.-22.-4.mca"`.
Passing log: `20261111-pyramid-placement-histories/pyramid-saved-refinement.log`.
The original seed-only failures remain in the matrix. Beached-shipwreck
discrepancies also recur on both seeds.

The matrix now preserves family filters and sampling bounds as separate scopes,
so an isolated regional pass cannot erase a broader generation-history failure.
Four evidence-retention regressions pass (`python docs/prediction-evidence/test_update_matrix.py`).
The isolated regional mesa-mineshaft pass is archived as
`26.3-mineshaft-mesa-regional-isolated.txt`; the earlier broader failure remains.
Future oracle reports print every actual container's table, loot seed, inventory
and unrepresented components before matching, so a missing predicted position
does not discard independent ground truth. The matrix separates unavailable
inventory-fill comparisons from observed loot differences.

The isolated extreme-seed archaeology/layout retest passed in 1m18s with stable
resources: seeds `-9223372036854775808` and `9223372036854775807`, ten matched
starts/layouts across monuments, swamp huts, trail ruins and Trial Chambers,
and **201/201 trail-ruin inventories** matching positions, loot seeds, stacks and
supported properties. There were zero failures or unsupported-metadata inventories;
the 201 unverified labels are only the conservative product exactness flag.
Trial Chamber loot was skipped as requested. Archive/report:
`20261111-extreme-archaeology-layout-retest` and
`26.3-extreme-archaeology-layout-retest.txt`. This validates the restored resources;
the 37 failed comparisons in the earlier differently scoped run remain retained
as test-environment errors.

The cracking agent finished its initial integration and isolated tests. The earlier
standalone build, all ten probes, package verification and GameTest compilation
passed in 3m34s (`standalone/build/seed-cracking-latest-build.log`). The manual
client opened the existing `Seed Explorer Test` world and the new Seed cracking
workspace was visually confirmed. The user is testing that client; do not send
UI input to it without a further handoff. Its launch log is
`standalone/build/manual-seed-cracking-client.log`. The automated live cracking
capture/recovery smoke remains pending. The original two-seed oracle has ended;
focused pyramid retests are underway. The cracking agent is implementing the
user's new automatic/background workflow. At 10:24 the automatic checkpoint passed
compilation, 24 independent pyramid-recognition fixtures, outline-policy checks,
the observed-start -> low48 -> full-seed pipeline and settings/workspace checks
(`standalone/build/seed-cracking-manual-checkpoint.log`). The user's client was
reopened at 10:25 with background collection, pyramid recognition and blue outlines;
shipwreck recognition was not yet connected in that running session. Current
manual launch log: `standalone/build/manual-automatic-cracking-client.log`.
The user controls that client. The agent also owns the requested full method
reliability audit, shipwreck/ocean feedback, and subsequent emerald-ore research.
No commit or push has occurred.

## Latest continuation: UI fixes and overlapping mineshafts

The reported UI defects passed live regression and visual checks: approximate
loot now displays its items, observed matches are independent of the family's
confidence flag, and inspecting Nether/End structures from the Overworld no
longer leaves the selection loading indefinitely. Evidence is archived under
`20261111-prediction-ui-complete`.

The completed Nether/End two-seed audit (`1,-9223372036854775808`) matched all
40 inventories and all 13 initial layouts, with zero sampled discrepancies.
See `26.3-nether-end-loot-two-seeds.txt`.

The two extra mineshaft carts have now been fixed. At seed 1, start `(-58,-339)`,
vanilla references another mineshaft in the same decoration chunks. It consumes
RNG before the selected start. The old isolated replay omitted this consumption.
The fix discovers overlapping starts of the same mineshaft registry ID, reproduces
vanilla reference insertion/hash iteration, and shares the per-chunk RNG while
returning only the selected start's cart entities. The independent live retest
matched all 8 positions, tables, loot seeds, stack inventories and the initial
layout. The former baseline had 8 actual versus 10 predicted carts. Archives:
`20261111-mineshaft-overlap-baseline`, `20261111-mineshaft-overlap-fixed`.
A regression fixture is in `StandaloneLootProbe`; persistent cache revision is v9.
The opt-in mineshaft diagnostic helper was moved outside the mixin package to
avoid a classloading failure; failed diagnostic launches are not passing evidence.

The broader two-seed Overworld baseline finished in 9m11s with 44 structure/seed
groups, 52 matched initial layouts, 413 actual versus 412 predicted containers,
136 modeled inventory matches and 12 metadata-unverified inventories. Its 268
recorded discrepancies comprise 256 missing archaeology-table comparisons,
9 beached-shipwreck positional/seed differences, 2 Ancient City seed differences,
and 1 mesa-mineshaft container difference. Missing 26.3 archaeology resources
have now been bundled from the pinned runtime jar; their live retest is pending.
The baseline is `26.3-overworld-loot-archaeology-baseline.txt`.

The Ancient City fixture (`-9223372036854775808`, start `-429,-521`) reproduced
both seed differences with all 20 positions and the initial layout matching.
Feature tracing shows `sculk_patch_ancient_city` consumes 6878 draws offline
versus 5336 live in chunk `(-428,-525)`. In `(-436,-518)` it consumes 6881 offline
versus zero live, where its origin is a stone-brick slab rather than offline air.
This is an unsupported surrounding-terrain/structure interaction in the capture
world. All Ancient City outputs remain approximate. A full terrain/decorations
experiment exceeded the bounded test deadline and was reverted; it is not a fix
or passing evidence. Archives: `20261111-ancient-city-feature-terrain-baseline`,
`20261111-ancient-city-full-terrain-rejected`.

The mesa-mineshaft focused replay (`-9223372036854775808`, `-636,59`) matched all
three containers, while the earlier regional approach produced four live carts.
The focused pass is retained separately and does not erase the regional discrepancy;
a regional placement trace is in progress.

Remaining bounded coverage and targeted retests are in progress.
Villages and camp variants still need current-source broad coverage. The proven
village generation-history discrepancy, shipwreck placement-history limits and
camp lake overwrite remain explicitly recorded; these are not exact guarantees.
Trial Chamber loot remains deferred and End Gateway remains skipped.
Final build, review, commit and push remain pending complete coverage.

The checkpoints below preserve the earlier investigation history; the continuation
above supersedes their statements about mineshaft diagnosis and Nether/End coverage.

## Session checkpoint: 11 November 2026

The all-five-village experiment finished in 19m56s with `STATUS COMPLETE`,
11 failures and 86 conservative inexact results. Across seeds `1,123456789`,
all 27 initial layouts and all 97 container positions matched; 86 inventories
matched and 11 loot seeds differed. This run used the rejected village terrain
fallback and is diagnostic evidence, not validation of the current source.
Report, trace, log and both focused fixture regions are archived in
`build/reports/oracle-archives/20261111-village-all-terrain-fallback-rejected/`.
Desert seed 1 passed 3 layouts/9 containers/9 stack inventories.
The fallback change has already regressed the original seed-1 plains chest
(oaks now consume zero draws) and leaves one savanna mismatch. Do not ship it
unchanged. The broad fallback has now been reverted in source (the running audit
used the earlier compiled fallback). It did not fix seed 123456789 either.
The validated state/fluid predicate implementation and explicit-air removals remain.
Feature-origin/support-state and placement-predicate tracing is prepared for the
next focused retest. Explicit fixtures can now be selected using
`-PpredictionOracleStarts=1:785:995,123456789:-266:658` with the exact
`village_plains` type, so both known failures can be investigated directly.
The current production sources and new GameTest diagnostics compiled successfully
on 11 November (`standalone/build/focused-village-diagnostics-compile.log`).
A focused two-fixture baseline completed in 2m28s: both layouts and six chest
positions matched; seed 1 matched all three inventories and seed 123456789
retained one loot-seed mismatch. The trace proves the first flower divergence
is an air check: live grass versus offline air at `(-4244,114,10515)`.
Archive: `20261111-village-focused-predicate-divergence`.

The next correction includes the village start in Beardifier and enables
terrain reads for villages. Unlike the rejected unadapted fallback, both seed-1
oaks retain their matching RNG and all three fixture inventories match.
The two-fixture retest completed in 2m52s with the same one remaining chest
mismatch, but the seed-123456789 flower trace now agrees through attempt 61.
Attempt 62 reads short grass live versus air offline at `(-4243,115,10517)`,
across the placement-chunk boundary; both see the same supporting grass block.
Other recorded differences also involve vegetation in neighboring chunks.
Archive: `20261111-village-adapted-terrain`; report: `26.3-village-adapted-terrain.txt`.
The correction has not yet received the all-variant regression run.

An independent approach-history experiment completed for seed 123456789 with
`-PpredictionOracleFirstChunk=-264:657`, requesting that chunk before the usual
fixture start. Log: `standalone/build/village-east-approach-audit.log`.
All three inventories and the layout matched, zero failures (2m05s). The same
weaponsmith chest now has live loot seed `3569840482288194909`, whereas the
default approach generated `3963027861913339195`. The trace confirms attempt 62
now sees air rather than short grass. This is independent evidence that village
loot can depend on neighboring chunk-generation history, even with identical
seed, initial structure layout and chest coordinates. Archive:
`20261111-village-east-approach`; report: `26.3-village-east-approach.txt`.
Both approach reports are retained as separate matrix scopes; the passing
approach does not erase the default-history discrepancy. The option requires explicit
fixtures and records its value in the report. Compare feature traces using
`python docs/prediction-evidence/compare_village_trace.py <trace.log>`.
The user explored the manual client and closed it at approximately 08:49,
then authorized taking control and fixing prediction/UI bugs. Findings:

- The 3D sidebar hid every modeled item list with `exact=false`, showing only
  "Loot unverified". It now shows predicted items and separate location and loot
  labels; loaded real containers can show "Chest found".
- Observed inventory matches were incorrectly downgraded to unverified by the
  structure-family confidence flag. The comparison now reports a match when
  synchronized counts and supported properties agree, retaining incomplete
  metadata and position-only results as unverified. Ancillary probe passed.
- The user's bastion and End City requests finished and populated the cache,
  but publication required the inspected dimension to equal the player's
  dimension. This rejected Nether/End results while browsing from the Overworld,
  leaving loading=true. The guard now checks actual world identity, session,
  seed and version; remembered overlays still require the player's dimension.
  Session state is refreshed at dispatch, and rejected current requests end
  loading with a visible explanation. The live regression passed both
  user-reported structure starts while the player stayed in the Overworld.
  It also verifies session-reset rejection and observed MATCH/MISMATCH results
  for predictions with exact=false.
- The reused manual test profile selected historical `1.21.4` loot data; its
  two recorded village/mineshaft mismatches used that version, and a bastion
  parse failed on the obsolete `minecraft:chain` ID. The closed client's profile
  is corrected to 26.3, with a backup in `standalone/build/`. This is a profile
  correction, not evidence of a 26.3 predictor fix. Cache revision is now v7.

Live UI regression passed in 1m07s (`standalone/build/prediction-ui-regression.log`).
The full standalone build, package verification, all nine probes and GameTest
compilation passed in 2m28s (`standalone/build/prediction-ui-full-build.log`).
Visual review found the extra status rows crowded out items at the compact test
window size; the tighter layout passed a second live run in 55s and visual
inspection confirms "Chest found", "Predicted loot" and the item row remain
visible (`standalone/build/prediction-ui-compact-retest.log`). Final screenshots
and all three validation logs are archived in
`build/reports/oracle-archives/20261111-prediction-ui-complete/`.
The user's pre-fix logs/profile and idle-worker thread dump are archived in
`build/reports/oracle-archives/20261111-manual-ui-bugs/`.
The corrected manual client is being relaunched. These checks do not complete
the broad loot audit or establish zero unresolved prediction mismatches.
The Git branch is `main` at base commit
`54bc0f4f939646b6fa6c831c86dd5f40786ded74`; the current implementation and
diagnostic sources are local, uncommitted, and unpushed. Preserve unrelated
untracked `seed-cracking/` and `$log`.

Current findings:

- New code inspection: village template chests bypass `StructurePiece.createChest`;
  `StructureTemplate.placeInWorld` writes their loot seeds directly. Added a
  template-container hook recording live/offline source, world seed, position,
  table, RNG count and loot seed; removed the seed-1-only trace filter.
- The offline capture world's `isStateAtPosition` and `isFluidAtPosition` were
  unimplemented, defaulting to false. Tree collision checks use the former.
  Implemented predicate evaluation against captured block/fluid state. The first
  seed's trace now shows 18 draws for each oak offline and live, and both assign
  chest `(12571,64,15975)` loot seed `-2081197769866577064`. Seed 1's 13 chest
  positions and modeled loot matched. Seed `123456789` matched all six positions
  but one weaponsmith loot seed still differs: chest `(-4226,117,10527)`, predicted
  `3569840482288194909`, actual `3963027861913339195`. A feature before the house
  consumes 456 draws live versus 458 offline. Pool-element and block-predicate
  tracing has now identified the divergent reads described above.
  Added this independent fixture to the standalone loot probe and bumped the
  persistent loot cache revision to `26.3-loot-v6`. The standalone loot probe,
  including this fixture, passed along with GameTest compilation (1m48s).
  Report: `26.3-village-predicate-fix-two-seeds.txt`; trace archive:
  `build/reports/oracle-archives/20261111-village-predicate-fix-two-seeds/`.

- The two-seed location-only audit completed with zero sampled structure
  location mismatches. Chest positions and loot were skipped in that run.
- Beached shipwreck observed refinement passed its focused checks: 2 unique and
  2 ambiguous histories. Seed-only baseline differences remain documented.
- The camp phantom barrel is explained by a runtime write trace: structure
  placement creates the barrel, then `LakeFeature#place` replaces it with cave
  air. No predictor change was made.
- Before this session's predicate correction, the seed-1 plains village sample
  had 13 matching positions, one loot-seed mismatch and 12 inexact results. That
  failure is now superseded by the 13/13 modeled-match retest above; it is retained
  in the original trace archive.
- The newer seed-123456789 baseline (`26.3-village-flower-layout-stack-baseline.txt`)
  matched all four initial layouts and all six container positions. Five
  inventories matched stack sizes and supported metadata; the flower-related
  weaponsmith seed remains the sole mismatch in that baseline.

The next work sequence is:

1. Finish the all-variant village run; inspect every mismatch and compare live
   and offline feature RNG. Retest any correction across seeds.
2. Extend the matrix to completed chest/loot audits for every remaining family,
   including negative and extreme 64-bit seeds. Preserve empty, unavailable,
   history-dependent and metadata-unsupported results explicitly.
3. Keep Trial Chamber loot deferred and End Gateway skipped; Trial Chamber
   locations and initial configuration remain required.
4. Resolve or explicitly classify every mismatch with evidence, then run the
   final standalone build/probes, live UI smoke, diff review, commit and authorized
   GitHub push. Work remains uncommitted; coverage is not yet complete.

Key archived reports are under `build/reports/oracle-archives/`, including
`20261111-two-seed-location-only-complete`,
`20261111-shipwreck-beached-observed-refinement-retest`,
`20261111-camp-lake-overwrite`, and
`20261111-village-plains-live-rng-trace`.

## Active audit progress

The reproducible [per-structure audit matrix](AUDIT-MATRIX.md) includes all 52
live registry IDs plus dungeon rooms and the explicitly skipped gateway. Run
`python docs/prediction-evidence/update_matrix.py` after archiving completed
reports here. It preserves loot evidence when newer location-only runs exist,
and marks absent loot/property checks as untested. The oracle now separately
counts modeled loot matches and unsupported metadata, independent of exact flags.
New runs also replay vanilla container filling to compare stack sizes and check
each physical half of double chests. Initial piece NBT is compared before feature
decoration where available, covering piece order, bounds, rotations and configuration.
A registry-owner issue in the first layout verifier was archived and corrected by
encoding offline holders with their original datagen lookup. Compilation passed;
the combined live layout/stack verification is still running. Historical start
counters double-counted some predicted samples; new reports count unique starts.

The current objective is a broad empirical audit of every predictable Minecraft
26.3 structure in the Overworld, Nether, and End. The live structure registry is
the source of truth for the structure inventory; configured-feature structures
such as dungeons are tracked separately. Trial Chambers require location
verification only. Passing samples are empirical evidence, not proof for every
possible seed.

Completion requires chest-position and loot checks for every chest-bearing
structure family. Location-only passes do not satisfy chest/loot coverage.
Trial Chamber loot is deliberately deferred; Trial Chamber locations remain
in scope. End Gateway is intentionally skipped.

| Work item | Status | Evidence / next action |
|---|---|---|
| Standalone runtime cleanup | Implemented and probe-tested | `StandaloneAncillaryProbe` covers duplicate coordinates and repeated Y refinement. Async loot callbacks now check captured `Level` identity and runtime session generation. |
| Beached shipwreck observation refinement | Implemented; focused retest passed its observed checks | Seed 1 retest exercised 4 histories: 2 unique (candidate count 1/4) and 2 ambiguous (6/6). No observed-specific failures. The honest seed-only baseline still reports 16 failures: 8 missed and 8 extra chest positions across 3 histories; 2 loot comparisons remain unverified because the refined output is intentionally inexact. Archive: `build/reports/oracle-archives/20261111-shipwreck-beached-observed-refinement-retest/shipwreck-beached-observed-refinement.txt`. |
| Village loot RNG | Oak defect fixed and independently retested; flower discrepancy open | Seed 1: all 13 positions/modeled inventories matched after implementing capture-world predicates. Seed 123456789: all 6 positions matched, one weaponsmith seed differed. Full trace identifies flower_plain consuming 458 offline vs 456 live; village terrain fallback is the next unvalidated correction. Archives and regression probe are recorded above. |
| Camp phantom barrel | Diagnosed; no predictor change justified | Runtime block-write trace proves the barrel was placed, then replaced with cave air by `LakeFeature#place` during decoration (`recursion=512`). This is terrain feature overwrite after structure placement, not a missing structure placement. Archive: `build/reports/oracle-archives/20261111-camp-lake-overwrite/reports/camp-placement-trace.txt`. |
| Complete 26.3 registry inventory and three-dimension location baseline | Seed 1 complete; multi-seed location regression complete | Runtime registry inventory recorded 52 IDs in each dimension registry view; the predictor classified 53 dimension-applicable registry structure groups, plus dungeon configured-feature rooms. Seed 1 sampled 158 actual starts and 206 predicted starts with zero location mismatches. |
| Multi-seed location regression | Complete for the requested location-only scope | Seeds `123456789` and `-7046029254386353131`; dimensions `-1,0,1`; one actual locate attempt and one predicted sample per registry structure; dungeon loot skipped. The final report had 104 structure progress rows, zero sampled location mismatches, and one in-scope unverified empty mineshaft-mesa sample. It ended with `STATUS COMPLETE`, `FAILURES 0`. Archive: `build/reports/oracle-archives/20261111-two-seed-location-only-complete/two-seed-location-only.txt`. |
| Chest-position and loot coverage across chest-bearing structure families | Not complete | Existing focused reports cover selected families only. Next: build a per-family matrix, run bounded actual-vs-predicted chest/loot checks across seeds, resolve mismatches or mark genuinely history-dependent/unsupported results unverified. Trial Chamber loot is deferred. |
| End location audit | Passed for registry structures | End City location samples matched with no missed or extra starts. End Gateway is intentionally out of scope per the current goal because it is a configured feature rather than a registry structure. |
| Seed 1 all-dimension location audit | Passed for sampled structure starts | 53 report groups, 158 actual samples, and 206 predicted samples across Nether, Overworld, End City, and bounded dungeon rooms. Zero missed or extra locations. Archive: `build/reports/oracle-archives/20261111-seed1-all-dimensions-location-only-complete/seed1-location-only.txt`. |

The latest run can be reproduced from the repository root with Java 25:

```powershell
$env:JAVA_HOME='C:\Users\Aubrey Martin\.codex\java\zulu25.32.21-ca-jdk25.0.2-win_x64'
.\gradlew.bat -p standalone runClientGameTest -PpredictionOracle=true -PpredictionOracleSeeds=1 '-PpredictionOracleDimensions=-1,0,1' -PpredictionOracleQueryAttempts=2 -PpredictionOraclePredictedSamples=2 -PpredictionOracleCaseTimeoutTicks=1200 --no-daemon --console=plain
```

A visible live monitor is available at
`standalone/build/watch-prediction-audit.ps1`. It tails the current oracle
report, separates structure-location, chest-position, and chest-loot results,
and lists every reported mismatch or unverified case. Location-only runs are
shown honestly as `NOT AUDITED` for chest positions and loot; they are not
counted as passes for those checks.
Launch it with:

```powershell
Start-Process powershell.exe -ArgumentList @('-NoExit','-NoLogo','-ExecutionPolicy','Bypass','-File',
  (Resolve-Path '.\standalone\build\watch-prediction-audit.ps1').Path)
```

The interrupted run's worker was evaluating jigsaw biome viability for loot-map
destinations; the integrated server and test thread waited at the GameTest tick
barrier. Location-only mode was added to avoid coupling structure coverage to
the recursive loot path. Archive each completed run before starting another
because `runClientGameTest` recreates its run directory.

The visible watcher was refreshed on 11 November 2026 after the two-seed
location-only run. It remains useful for later chest/loot runs and reports the
three result classes independently so a location pass cannot hide an untested
loot path.

These reports compare predictions with independently generated vanilla structures
in a Fabric integrated server running Minecraft 26.3. They are bounded samples,
not exhaustive certification of every seed, structure, or generation history.

| Report | Scope | Result |
|---|---|---|
| `26.3-seed1-portals-treasure-igloo.txt` | Seed 1; six Overworld portal variants, buried treasure, igloo | 24 containers matched positions, tables, loot seeds and the item metadata compared by that run. Exact flags were not yet promoted. |
| `26.3-seed1-treasure-responsive.txt` | Seed 1; buried treasure | Four containers matched; zero failures or unverified cases. |
| `26.3-seed1-ancient-camp-dungeons.txt` | Seed 1; ancient city, old-growth spruce camp, dungeons | Completed without hanging. All 81 ancient-city containers and four dungeon rooms matched their modeled loot; one extra predicted camp barrel remains a recorded failure. |

Reports record the state of the predictor when captured. A later regression or
counterexample supersedes an earlier passing sample; do not use the table above
as a claim that the complete current Overworld audit passes. Intermediate and
interrupted runs are kept in local build output until reviewed.

## Reproducing checks

Run from the repository root with Java 25:

```powershell
.\gradlew.bat -p standalone build compileGametestJava --console=plain
.\gradlew.bat -p standalone runClientGameTest -PpredictionOracle=true -PpredictionOracleSeeds=1 -PpredictionOracleTypes=buried_treasure --console=plain
```

The live test opens an automated Minecraft window and closes it on completion.
Its report is written to
`standalone/run/clientGameTest/build/reports/seed-explorer-prediction-oracle-26.3.txt`.
Archive the report before starting another run: the test working directory is
recreated. A checkpoint without a final summary is an incomplete run.

The audit checks both actual starts against predictions and predicted starts
against actual generation. Container counts alone are insufficient: positions,
loot tables, seeds, item quantities, and supported metadata must agree. An
internal simulator exception is a defect, not evidence that a structure has no
containers. Unsupported item components must remain explicitly unverified.

## Generation history

Minecraft's scattered structure pieces can lock their height when their first
intersecting chunk is decorated. A different first chunk can produce different
chest heights from the same seed and structure start. The placement-order probe
includes a seed-1 desert pyramid counterexample. Canonical replay is reproducible,
but it cannot establish the generation order of an existing world from its seed
alone. Treat a demonstrated order-dependent prediction accordingly when assigning
exactness; do not erase the positional discrepancy from audit results.

## Interrupted broad audit, 10 November 2026

A seed-1 broad audit stalled during dungeon checks. Its thread dump showed the
server repeatedly computing stronghold ring biome adjustments through the dungeon
terrain simulator. The Fabric client test's render thread waited on its test
phaser while that work ran. Windows subsequently recorded `Application Hang`
(event 1002) and `AppHangB1` (event 1001); there was no corresponding Minecraft
crash report. The run did not complete and is not passing evidence.

Local diagnostics were archived under
`build/reports/oracle-archives/20261110-2139-seed1-noncamp/`. The separate manual
Minecraft client remained running. Regression checks for this issue must cover
the expensive dungeon path; the earlier short treasure pass did not cover it.

The subsequent mixed test completed in 3 minutes 37 seconds without an application
hang. Offline predictions ran on a worker; actual chunks loaded in separate server
tasks with client ticks between them. A smaller dungeon-only run first exposed an
obsolete `-8` offset in the test's dungeon origin calculation. Correcting that
bookkeeping restored its chest comparisons. Neither a test correction nor a
responsiveness pass certifies unrelated structure predictions.

## Additional audit coverage prepared in this session

- Vanilla inventory fill replay now checks individual stack sizes and supported
  properties, plus the table and seed of every physical half of double chests.
  The seed-123456789 baseline passed five such inventories; one seed mismatch
  was correctly retained. Dungeon checks use the same stack comparison.
- Initial piece NBT comparison now uses each registry's own holder lookup and
  fetches STRUCTURE_REFERENCES/STRUCTURE_STARTS before FEATURES. Four plains
  layouts passed the focused retest; the all-village run has passed all completed
  layout samples so far. This covers initial configuration, not an assertion that
  every final terrain block is identical.
- Audit inventory review found archaeology was silently omitted: trail-ruin
  table IDs incorrectly used `chests/`, and neither the capture world nor oracle
  captured brushable block entities. Support for suspicious sand/gravel and the
  correct `archaeology/` tables is implemented locally but **not yet compiled or
  live-tested**. It uses the actual live brushable entity's loot method as oracle,
  with an unenchanted brush and the test player's default zero luck.
- Mineshaft liquid-check bytecode clips its read box to the current placement
  chunk. The old untested missing-neighbor-liquid hypothesis is not sufficient
  to explain the two extra minecart chests. Fresh diagnostics are still required.
## Map and component verification follow-up, 11 November 2026

Treasure-map destinations are now modeled when the loot function explicitly
sets `skip_existing_chunks=false`: target structure, marker type, and rotation
are derived from the same 26.3 structure-placement search and emitted as the
vanilla map-decoration component. Saved map IDs and rendered preview pixels
remain world-saved-data properties and are not fabricated. Maps that skip
previously claimed structures remain history-dependent, including camp maps;
those should remain unverified until claim history is known.

Five independently captured normal and beached shipwreck-map inventories
across seeds `1` and `-7046029254386353131` matched target decorations exactly.
All actual container positions, map loot seeds, and expected decoration
coordinates are preserved in `26.3-map-targets-two-seeds.txt`; the two normal
ocean shipwreck region fixtures and their SHA256s are under
`build/reports/oracle-archives/20261111-map-targets-two-seeds/`. A standalone
probe replays all five inventory seeds, checks each target/type/rotation, and
asserts that no world-specific map ID was invented. This narrows the previous
67 unmodeled-property count, but does not erase saved map-ID/preview uncertainty.

The same run confirms the current seed-only beached-shipwreck defect again:
seed 1 has 6 actual versus 6 predicted containers but 3 positions missed,
3 extras and one loot-seed mismatch; the extreme negative seed has 3 actual
versus 3 predicted containers with all three positions shifted under canonical
history. Observed refinement is ambiguous in two seed-1 histories and unique in
the extreme-seed history. The complete discrepancies remain in the per-seed
matrix and are not converted into seed-only passes.

The oracle captures Suspicious Stew effect IDs/durations in diagnostic stack
signatures, and production `ItemLoot` and opening-time comparison now model
them. Independent property validation subsequently passed for four recorded
inventories plus a fresh live shipwreck, as detailed in the newer checkpoint
above; this does not retrospectively certify older unrecorded comparisons.

## Automatic cracking live checkpoint, 11 November 2026

The optional client GameTest completed successfully in 41 seconds with
`-PseedCrackingAudit=true` and `-x processResources`. It created an independent
singleplayer world, let the normal client packet/runtime path collect loaded
biome evidence in the background for 120 ticks, verified the runtime workspace
and nonblank status, and closed the test world. It did not provide the configured
world seed to the solver. Archive: `build/reports/oracle-archives/20261111-seed-cracking-live-audit/live-seed-cracking-audit.log`.

The headless 26.3 adapter rerun also passed actual comparisons: 6,144 Overworld
floor blocks, 6,144 Nether floor blocks, 6,144 Nether roof blocks, and 960
quart-biome checks across Overworld, Nether and End had zero mismatches across
two seeds and chunk-boundary/negative coordinates. Placement (20 registry sets,
720 checks), slime (42), pillars (60 ordered heights plus 20 columns), automatic
scheduler/recovery, outline policy and 36 settings controls passed. Archive:
`build/reports/oracle-archives/20261111-seed-cracking-live-audit/seed-cracking-legacy-validated.log`.

This live check validates background collection and lifecycle. It does not yet
force an in-world shipwreck or pyramid observation through the client, so the
separate synthetic and saved-world shipwreck recognizer evidence remains the
source for those recognition methods.

The extended live cracking audit also passed in 52 seconds. It created an explicit
normal-preset seed-1 world, used the independent test fixture solely for navigation,
verified a valid vanilla desert-pyramid start on the server, then let ordinary
client-loaded blocks run the automatic recognizer. The client recorded the
`recognized-intact-pyramid-26.3` start, generated an observed blue-outline
snapshot, and the captured start chunks matched independent vanilla placement.
The configured seed was not passed to the solver. Screenshot and run log:
`build/reports/oracle-archives/20261111-seed-cracking-live-audit/live-observed-pyramid-blue-outline.png`
and `live-pyramid-recognition-outline.log`.

The complete standalone probe suite then ran after the live test with stable outputs:
`check` executed all package and adapter probes, and `compileGametestJava` was up to
date. The log ends `BUILD SUCCESSFUL in 2m 53s` and includes passing loot (including
five map targets), renderer, settings, seed-cracking and legacy Nether-bedrock
probes. Log: `standalone/build/complete-check-after-live-outline.log`. An earlier
attempt was interrupted by overlapping Gradle sessions and failed; it is not the
accepted result.

The Nether rendering review found red/orange surface terrain and large black
cave-shaped openings in both surface and Underground modes. Chunk diagnostics
showed 324 rendered chunks, 2.53 million surface vertices, 931 cave bakes with
geometry, no empty cave bakes and no missing bakes. The interactive screenshot was
visible during the inspection but not saved. A quantitative comparison against a
live vanilla Nether did not happen because the existing saved world has cheats
disabled, so the openings' fidelity and any texture concern remain unresolved;
no renderer or texture change is claimed.

### Ancient City terrain and history follow-up (2026-11-11)

The isolated experiments in
`build/reports/oracle-archives/20261111-ancient-city-lazy-terrain/` did not
establish a production fix. Seed `Long.MIN_VALUE`, start `(-429,-521)`, still
has 20 matching container positions and two baseline loot-seed mismatches.
Lazy natural terrain without city adaptation corrected `(-6962,-37,-8273)`
to live seed `1771759478595005239`, but changed `(-6848,-37,-8396)` to another
incorrect value. Including city adaptation restored both original incorrect
values. Replaying all 154 chunks in the recorded live order, with and without
adapted lazy terrain, also retained both baseline mismatches. All experiments
completed; none changed the production simulator. These results rule out
promoting either lazy terrain variant or recorded chunk order alone as a fix.

### Dungeon ordering in item search (2026-11-11)

Dungeon rooms now participate in the same nearest-marker traversal as structure
starts, rather than running only after other structures fail to fill the request.
Expensive dungeon regions split lazily into individual chunks, and the traversal
stops on cancellation, fulfillment, or the contributing-chest limit. An isolated
compile and focused probe passed, including a nearby dungeon ahead of a farther
shipwreck, chunk-only dungeon replay, and early cancellation before scanning the
whole region. Evidence: `20261111-ui-search-highlights-menus/dungeon-frontier.log`
in the oracle archive directory. A complete live search mixing dungeon and
structure loot remains unverified; the probe does not establish performance or
independent correctness of dungeon loot generation.
The independent shipwreck fixture search also passed again with fresh caches
against the changed traversal (`dungeon-frontier-oracle.log`), returning chest
`(12037,42,15480)` and both suspicious stews.
