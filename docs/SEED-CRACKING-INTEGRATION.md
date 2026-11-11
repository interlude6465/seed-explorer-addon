# Seed cracking in the standalone mod

Automatic background cracking is enabled by default. Explore the world normally;
you do not need to enter coordinates. Open **Settings > Seed cracking > Open
workspace** to see collecting/searching/results, pause/resume, retry, clear saved
evidence or review candidates. The separate category also sets a 10 to 300 second
budget per search and 1 to 4 CPU workers. The workspace opens on Background;
Observations and Search/candidates are advanced pages. The map seed changes only
when you explicitly click **Use unique candidate**.

## Automatic workflow

The client inspects one already-loaded nearby chunk every five ticks (a 5x5 area,
nearest first). It never loads chunks to collect evidence. It records up to 192
Overworld and 64 Nether bedrock gradient samples; 64 Overworld, 16 Nether and 16 End
raw noise-biome samples; 64 recognized structure starts; and one End-pillar
fingerprint. Dimension quotas preserve Overworld capacity when exploration begins
in the Nether or End. The total workspace limit is 512 rows.
Observations are deduplicated and incrementally saved per world/server and version,
including their source. No spawn absence is interpreted as a negative slime chunk.
Loaded checks use the actual client chunk cache without requesting a chunk:
26.3's `ClientLevel.hasChunk` always returns true, including absent packets.
Earlier automatic biome/shipwreck records made with that check are discarded
on load, along with their automatic candidate list, and recollected during exploration.

The structure recognizers support **intact desert pyramids and shipwrecks**. Pyramid
recognition requires all four trap-chamber chests, the pressure plate, nine TNT blocks, the
floor, wall details and central blue terracotta, at the vanilla chunk-aligned
anchor. Vanilla's 21x21 piece bounds make this anchor rotation-independent. Missing,
modified or unloaded parts fail recognition. `/locate` coordinates, predicted map
markers and arbitrary visible structure blocks are never treated as confirmed
starts.

Shipwreck recognition independently matched the 20 shipped 26.3 templates and 8
palettes across four rotations. Tests matched 5,632 chest anchors across 2,560
generated-piece fixtures and 14 chests in six saved vanilla wrecks, with zero
wrong start chunks; negative/unloaded cases fail closed. Matching establishes
consistent geometry under vanilla assumptions, not generation provenance: exact
player builds and compatible modified/degraded wrecks remain indistinguishable.
The detector caps block reads at 8,192 per chest (tested maximum 727).

## Integrated method coverage

| Method | Integrated evidence path | Independent validation and limit |
| --- | --- | --- |
| Random-spread low-48 lift | Confirmed automatic pyramid/shipwreck starts or advanced start-chunk observations | All 20 registry placements × 6 seeds × 6 signed regions (720 vanilla checks); the sieve omits rare bounded-`nextInt` rejection paths and remains non-exhaustive. |
| Upper-16 recovery | Automatic bedrock/noise-biome evidence and explicit low-48 input | 18,432 generated vanilla bedrock blocks and 960 quart-biome samples across 12 ProtoChunks, 2 seeds, all 3 dimensions: zero mismatches. Completeness applies only to supplied low-48 inputs. |
| End pillars | Loaded central-island columns, all ten must be intact | 60 ordered fingerprints and 20 obsidian columns matched vanilla; crystal/cap spawning is outside this adapter. |
| Slime chunks | Advanced confirmed status only; no false inference from missing spawns | 42 vanilla RNG comparisons, including extremes: all matched. Automatic slime recognition is disabled. |
| Pyramid starts | Automatic complete loaded trap-chamber signature | 24 generated pieces across rotations/negative coordinates; missing, unloaded, shifted and partial lookalikes rejected. Exact replicas remain indistinguishable. |
| Shipwreck starts | Async shipped-template matching from loaded chest/hull geometry | 2,560 generated fixtures, 5,632 anchors, 10,240 negatives; six saved wrecks/14 chests across 2 seeds: all correct. Five additional live client recognitions passed in the complete automatic workflow. |

The optional live client GameTest passed the complete automatic workflow in 59
seconds (`standalone/build/live-six-structure-cracking-loaded-cache-retest.log`).
It created a real seed-1 NORMAL world and independently verified one pyramid,
four beached wrecks and one ocean wreck for test navigation. Ordinary client packets
fed the collector; every start and outline snapshot matched, and every recorded
biome/bedrock observation accepted the independent vanilla ground truth. Automatic
lifting and upper-bit recovery produced the unique full candidate `1` within the
searched low-48 inputs. The deliberately wrong configured map seed `-987654321`
remained unchanged. Disabling automation hid the observed outlines. The corrected
observations are archived in `standalone/build/live-six-structure-corrected-evidence.properties`.

The earlier run exposed six false default-plains samples from absent chunks, one
at each teleport. `standalone/build/seed-cracking-live-evidence-replay.log` reproduces
those exact rejected observations; actual-cache guards and old-evidence migration
resolve that issue. The complete live test covers one seed and these two recognized
families, alongside the broader independent fixtures above; it is not an exhaustive
all-world certification.

Six distinct recognized starts trigger a bounded low-48 lift automatically; this
is an attempt threshold, not a guarantee of a unique candidate. If low-48 candidates
exist and at least 48 Overworld bedrock or 8 Overworld biome samples have accumulated, upper-bit recovery
follows automatically. It checks all 65,536 upper halves per accepted input within
the configured budget, applying cheap bedrock filters before noise biomes. Newly
collected evidence can trigger another attempt after a 60-second cooldown. Unchanged
inputs are not repeatedly searched. A successful lift has a one-second handoff to
upper-bit recovery. Automatically derived low-48 inputs are persisted and replaced
when later lifting yields a new candidate set; explicit advanced inputs remain
the user's restriction. New structure constraints take priority over retrying an
older automatic candidate list. An empty replacement lift clears that list.
Nether/End legacy randomness cannot independently identify the upper 16 bits;
advanced upper-bit recovery also requires Overworld evidence.

Collection and searches continue with the workspace closed. Pause/cancel stops
automatic work; both the settings toggle and workspace control cancel the active
automatic worker, and resuming retries interrupted evidence. Advanced searches
are still available while paused. World/session
or version changes cancel the old worker before accepting any new result. Candidate
application independently checks the originating world and prediction version.

These observations assume **untouched default vanilla generation**. The client
cannot distinguish an exact player-built replica, a modified bedrock layer, or a
server/datapack that changes generation rules. Bedrock capture requires the invariant
base layer before recording gradient absence; flat/void chunks without that layer
are skipped. Automatic capture is restricted to the running **26.3** profile.
Overworld and Nether bedrock use independently validated Xoroshiro and legacy
positional RNG paths respectively. Automatic Nether collection uses the loaded
floor gradient; it does not assume a missing spawn indicates a slime-negative.

## Advanced evidence

Cycle the observation-type button. Enter the space-separated values shown in the
example and click Add, or use Capture loaded where available.
Capture batches are atomic, and **Undo last add** removes a whole capture from the
current session. After reopening, it removes one persisted observation at a time.

| Type | Input | Source / constraint |
| --- | --- | --- |
| Structure | `shipwrecks -151 271` | Structure-set ID and confirmed **start chunk** X/Z. `/locate` block coordinates and the minimum bounding-box corner are not generally equivalent to the start chunk. Liftable sets include shipwrecks, ocean ruins, pyramids, igloos, jungle temples, swamp huts and outposts. Other supported sets can filter the candidates. |
| Bedrock | `overworld 0 -62 0 true` | Unmodified Overworld or Nether block coordinates and presence/absence. Automatic capture uses validated vanilla floor gradients; Nether uses legacy positional RNG while Overworld uses Xoroshiro. Custom presets/datapacks are unsupported. |
| Noise biome | `overworld 0 64 0 plains` | Capture reads the loaded chunk's noise-biome palette at the containing quart coordinate. This is intentionally not the visually blended/F3 biome at a boundary. The integrated worldgen adapter supports 26.3 only. |
| Slime | `12 -8 true` | Confirmed slime-chunk status at chunk coordinates. No observed spawn is not sufficient evidence for `false`. |
| End pillars | ten heights | Capture reads the central End island's ten loaded, unmodified pillars in vanilla order. Manual values are highest **obsidian block Y**, not the height of the bedrock cap. |

Evidence persists per world/server **and prediction version**, independently of the
map seed. Resetting display settings does not erase the observations. The workspace
never imports predicted map markers as observed structures and never reads the
server's seed to solve the user's search.

## Search

1. Collect independent structure starts. **Lift structures** searches low-48
   candidates and applies any End-pillar/slime evidence. Underconstrained searches
   stop and explain that more evidence is needed.
   The research lifting sieve is non-exhaustive: rare Java bounded-RNG rejection
   paths can omit a true low-48 candidate even after its high-bit window finishes.
   A no-match is therefore not proof that the observations are wrong.
2. Click **Use lifted candidates**, or enter known low-48 candidates separated by
   commas. These must be unsigned decimal values from 0 to 281474976710655.
3. Add bedrock and/or noise-biome evidence, then **Recover upper bits**. It checks
   all 65,536 upper halves for each accepted low-48 candidate within the configured
   budget. Cheap bedrock checks precede expensive biome generation.
4. Review candidate counts and the complete/partial status; Copy report exports
   the evidence, status and candidate values to the clipboard. **Use unique
   candidate** becomes available only after a complete upper-bit search with one
   surviving full seed **for the supplied low-48 inputs**. Other low-48 inputs may
   yield other full seeds. Validate with independent hold-out observations first.

Cancel or switching worlds prevents stale publication. Closing the workspace keeps
the background service alive. Applying a candidate also checks the originating
world session, profile and version.
Workers run outside Minecraft's render thread and cancellation propagates into the
core search loops. Candidate and result caps are explicit; reaching a budget is not
reported as an exhaustive no-match.
Mineshafts, buried treasures and Nether fossils are not accepted as structure
evidence: their range-one placement conveys no information without generation
frequency adapters, which are not integrated into this workspace.

## Validation and limits

`standaloneSeedCrackingProbe` compares all registry placements, slime RNG, ordered
End-pillar heights/columns, bedrock and noise biomes against vanilla: 720 placement
checks; 42 slime cases; 60 pillar fingerprints and 20 obsidian columns; 12 generated
ProtoChunks with 18,432 bedrock and 960 biome checks across Overworld/Nether/End,
two seeds and negative/chunk-boundary coordinates, zero mismatches. It tests
recognition against 24 independently generated
vanilla pyramid pieces spanning all rotations and negative coordinates, plus
missing/unloaded/misaligned/lookalike negatives. It also tests the automatic
observed-start -> low48 -> upper16 pipeline, deduplication, sufficient-evidence
gates, cooldown, persistence, revalidation, cancellation and world/version isolation.
The focused `standaloneSeedCrackingProbe --args="--workflow-only"` run additionally
passed Nether-only upper-bit rejection, dimension counts, fresh-start priority,
and automatic cancellation/resume regressions; see
`standalone/build/seed-cracking-workflow-reliability.log`.
The dependency-free module self-tests cover core arithmetic and search paths. The
client smoke covers settings navigation, real loaded bedrock/noise-biome capture,
independent actual-seed comparison, upper-bit recovery, resize and navigation.
The module's PowerShell `build.ps1` and `test.ps1` also passed directly with
Java 25 during release review. Source lists are explicitly arrays, including the
single test file, so PowerShell does not splat a path into individual characters.

The integrated screen intentionally labels results experimental: the research module
has not completed a multi-version generated-world certification suite. Structure
variants, loot/feature/climate/terrain adapters, recognition of other structure families,
raw LCG-output collection, screenshot ingestion and exhaustive pillar-time-machine
search are not presented as supported UI workflows. Their core interfaces remain
available for later validated adapters. This integration does not claim that every
one of the research inventory's 106 methods is implemented.
