# Seed Explorer

Standalone Fabric seed maps, 3D terrain exploration, structure and loot prediction,
ore overlays, waypoints, and enchanting tools. Predictions run locally from a
world seed. **Meteor is not required by the standalone artifact.**

## Install

Use Minecraft **26.3**, Java **25**, Fabric Loader **0.19.5 or newer**, and Fabric API
**0.162.0+26.3**. Put `seed-explorer-1.0.0.jar` in the instance's `mods` folder.
Remove the old Meteor Seed Explorer addon jar from that instance to avoid duplicate
frontends. A compatible standalone Baritone is optional for automatic navigation;
mapping, loot, settings and enchanting do not require it.

Press **X** in a world to open the map. The gear button opens the native settings
panel with the original 32 module controls. Open 3D from the map; move with WASD,
drag to look, and use Space/C vertically. Right-click a 2D structure to inspect
its containers; right-click empty map space to create a waypoint. Search accepts
structure/biome names, spelling approximations, coordinates, and `loot:`/`item:`
queries. The Items/books mode supports multiple items and quantities.
The **Actions** menu contains recentering, visibility, waypoint management,
copying coordinates, teleporting, navigation and completion controls.

The map includes per-structure visibility, completion markers, editable waypoints,
copy/teleport/navigation controls, loaded terrain, predicted ores, a minimap, and
surface/underground 3D views. Terrain work uses background queues, bounded capture
and uploads, and a spiral presentation order. There is no guaranteed chunks-per-second
rate; throughput depends on terrain detail, worldgen, hardware and competing mods.

## Build and verify

From a clean checkout with Java 25 available:

```powershell
.\gradlew -p standalone build --console=plain
```

The jar is written under `standalone/build/libs/`. Press `X` in a world to open
the map. This Fabric Loom build resolves its own dependencies and needs no Meteor
checkout or cached Meteor jar. It runs nine headless probes and checks the packaged
class closure, mixins, service provider, texture index and absence of Meteor symbols.
GitHub Actions runs the same build and publishes a downloadable build artifact.

Seeds and prediction versions are scoped per world/server. Visibility and completion
are also scoped per profile; waypoints are scoped by seed and dimension. Display
settings are shared. Settings are stored in `config/seed-explorer.properties`.

For a development client: `.\gradlew -p standalone runClient`.
For a live client smoke test: `.\gradlew -p standalone runClientGameTest`.
The test creates a temporary world, checks transformed hooks, opens the native
map/settings/3D screens, waits for generated map data and terrain draw calls,
checks return navigation and saves screenshots. It is separate from the headless
build checks and requires a working graphics session.
For manual testing without automatic shutdown, copy a saved world into
`standalone/run/manualTest/saves/<folder>` and run
`.\gradlew -p standalone runClient "-PmanualTestWorld=<folder>"`.
The repository root build remains a legacy Meteor compatibility and validation
harness; use **`-p standalone`** for the independent mod.

To use a local texture pack only for the map, copy its block textures into
`config/seed-explorer/texture-pack/assets/minecraft/textures/block/` in the game
directory. The map falls back to active Minecraft resources and bundled textures.
Pale oak leaves and leaf litter are excluded from the 3D map as requested.

## Prediction scope

The selector exposes 1.19.4, 1.20.4, 1.21, 1.21.4, 1.21.5, 1.21.11, 26.1.2, 26.2 and
26.3 profiles. It selects loot data and gates
version-specific structures/biomes; **it does not emulate the complete historical
terrain generator**. Terrain and placement registries come from the running Minecraft
version. New 26.3 camps use that runtime's placement sets and container tables.
Sulfur caves are searched as an underground biome, not as a chest-bearing structure.
Server plugins, datapacks, terrain edits, previously opened containers and moving
minecarts can change results. Approximate loot is labelled separately from exact replay.

Historical oracle comparisons against Paper 26.1.2 cover pyramids, strongholds,
shipwrecks, outposts, jungle temples, treasure, bastions and dungeons. These are
historical results, not certification of every structure on 26.3. Current build probes
exercise camp loot, village key mappings, dungeon replay, ore determinism, settings,
searches, renderer linkage and dependency isolation. Full live visual and server
oracle coverage remains a separate validation step.

## Standalone commands

Standalone chat commands use `.se`, `.seedexplorer`, or `.seed-explorer` so they are handled
locally and never sent to the server:

| Command | Purpose |
|---|---|
| `.se open` | Open the map. |
| `.se seed [set] <seed>` | Show or set the current profile's seed. Seed zero is valid. |
| `.se version <version>` | Select the prediction version. |
| `.se status` | Show seed, position, navigation, mining, and enchanting state. |
| `.se waypoint list` | List waypoints for the active seed and dimension. |
| `.se waypoint add [x z] [name]` | Add a waypoint at the player or supplied coordinates. |
| `.se waypoint remove <id or number>` | Remove a waypoint from the current list. |
| `.se goto <x> <y> <z>` | Navigate with Baritone when it is installed. |
| `.se mine <ore> [radius] [targets]` | Predict targets in the background, navigate, and mine loaded targets. |
| `.se mine status`, `.se mine next`, `.se mine stop` | Inspect or control predicted mining. |
| `.se loot search [--min=N] <item or enchantment>` | Search remembered predicted containers. |
| `.se enchanting [status or reset]` | Inspect or reset player-RNG recovery. |
| `.se enchant find <enchantments>` | Inspect current offers and bookshelf plans. |
| `.se enchant item <slot> <enchantments>` | Query an inventory item. |
| `.se stop` | Stop navigation and predicted mining. |

The familiar `.seed`, `.waypoint`, `.loot`, `.mine-predicted`, `.enchanting`,
and `#mine` aliases are also accepted. Baritone is optional and is discovered
at runtime; every map, prediction, search, and enchanting feature remains usable
without it.

Opening an enchanting table displays the native exact-offer panel. It recovers
the hidden XP seed from vanilla costs and clues, lists compatible enchantments,
checks conflicts, and searches current bookshelf settings. After two confirmed
enchantments recover the player RNG, it can also search future item-drop and
dummy-enchant sequences. Opening a remembered predicted container displays a
predicted-versus-actual contents panel. Mining, chest, and enchanting session
state is cleared whenever the client disconnects, changes world, or changes
dimension so stale coordinates cannot leak into another context.

## Experimental Terrain Sampler

The Horizon-inspired far-terrain sampler is isolated from the default generator
and has no UI control. To try it in a Minecraft launch, add
`-Dseedexplorer.terrainMode=horizon` to the JVM arguments. It only replaces
Overworld surface samples at LOD steps 4, 8, and 16; close terrain, other
dimensions, caves, and the default mode continue to use the existing path. The
experiment approximates biome surface materials and is not a vanilla-parity
mode. Remove the JVM argument to return to the normal generator.

The headless comparison probe is:

```powershell
.\gradlew horizonTerrainSamplerProbe -Pseed=42 -PchunkX=-2 -PchunkZ=-2 -PchunksPerSide=4 -Pstep=8 --console=plain
```

## Natural Feature Search Prototype

The experimental natural-feature finder is not connected to the map UI. Its first query
looks for a roughly circular elevated ridge around a lower center in a river
biome. Search a square around an origin with:

```powershell
.\gradlew naturalFeatureSearchProbe -Pseed=42 -PoriginX=0 -PoriginZ=0 -PradiusBlocks=512 --console=plain
```

To require a deep nearby cave as well, add
`-PrequireDeepCave=true -PminimumCaveDepth=80`. To scan a seed interval, add
`-PlastSeed=1000`. The query and scoring are in `NaturalFeatureFinder`; cave
checks use carved vanilla chunks and are an experimental refinement.

## Legacy verification harness

The following root-build tasks require the legacy compatibility workspace and its
oracle captures. They are not part of the standalone installation. The 26.1.2
regression suite explicitly skips other runtimes rather than claiming cross-version parity.

| Task | Class | What it checks |
|---|---|---|
| `predictionRegressionSuite` | `PredictionRegressionSuite` | **Fail-fast gate.** Runs all accepted oracles (4 desert, stronghold, shipwreck, outpost, buried treasure, jungle temple, bastion), the mineshaft saved-entity cart pin, vanilla terrain height SHA-256, carver-replaceable tag count (52), and critical carved height — exits 2 on any mismatch. |
| `validateLootProbeOracle` | `LootProbeOracleValidator` | Diffs a single desert-pyramid prediction against a LootProbe JSON capture (positions, loot-table IDs, seeds, aggregated items). |
| `offlineLootSimulation` | `LootSimulationReport` | Runs the desert-pyramid simulator for a given seed/chunk and prints every chest, loot seed, and item. |
| `simulateStronghold` | `SimulateStronghold` | Auto-discovers stronghold positions for a seed and prints piece/chest layout. |
| `lootTableAccuracyTest` | `LootTableAccuracyTest` | Verifies loot-table parsing (table references, random-chance conditions, single-entry RNG rule) against oracle data. |
| `crossVersionTest` | `CrossVersionTestReport` | Loads all Minecraft version profiles and reports loot-table path/resolution failures per version. |
| `dungeonStepTest` | `DungeonStepTest` | Tests dungeon (`monster_room`) feature placement in a specific decoration step. |
| `strongholdDecorationIndexSearch` | `StrongholdDecorationIndexSearch` | Brute-forces the decoration RNG index to match a Paper stronghold chest seed. |
| `strongholdRandomSequenceSearch` | `StrongholdRandomSequenceSearch` | Locates a Paper stronghold loot seed in per-chunk RNG streams. |
| `strongholdSavedStartSimulation` | `StrongholdSavedStartSimulation` | Replays one chunk from a Paper-serialized stronghold start for RNG comparison. |
| `terrainInteractionReport` | `TerrainInteractionReport` | Reports overlapping structure starts and terrain adaptation effects. |
| `terrainBlockParityReport` | `TerrainBlockParityReport` | Compares structure-disabled Paper chunk blocks against offline generated terrain. |
| `placedFeatureInventoryReport` | `PlacedFeatureInventoryReport` | Lists vanilla placed features for a chunk rectangle (no execution). |
| `surfaceLakePyramidSearch` | `SurfaceLakePyramidSearchReport` | Searches seeds for a surface lava lake that alters a desert-pyramid footprint. |
| `savedChunkNbtReport` | `SavedChunkNbtReport` | Reads structure metadata from an oracle Anvil region file. |

Run the full gate with:

```powershell
.\gradlew predictionRegressionSuite --console=plain
```

## Legacy in-game structure audit

These automated QA/admin commands remain in the legacy Meteor frontend, not the
standalone router. With that frontend, a seed set and cheats available, run:

```text
.seed-explorer test-tp audit-all 3
```

This visits one predicted instance of every loot-capable structure variant in
the Overworld, Nether, and End. It opens every predicted chest/container,
compares the generated inventory, force-loads each structure piece for a final
container scan, and reports both missing predicted containers and unexpected
containers. `all-structures` is an equivalent command name. Use
`.seed-explorer test-tp status` while running, `.seed-explorer test-tp stop` to
cancel, and `.seed-explorer test-tp last` to re-save the latest report.

The detailed report is written to a timestamped file such as
`seed-explorer-reports/loot-tp-complete-20260808-123456.txt` (or
`loot-tp-stopped-<timestamp>.txt`), and per-chest prediction/open/scan events are
appended to `seed-explorer-reports/prediction-debug.log` for diagnosing and
improving prediction code.

## Enchanting Table Queries

When an enchanting table screen is open, Seed Explorer adds a prediction panel
beside the vanilla interface. Place an item in the table slot and the panel
will crack the full XP seed from the server's masked seed plus the vanilla
cost/clue data. The first observation scans the 2^20 possible seeds; replace
the item with another enchantable item to add another observation and narrow
the candidates until the seed resolves. The panel lists every compatible
table enchantment, the exact current offers, the recovered seed, and the
detected bookshelf count. While the crack is running, offers are deliberately
withheld instead of showing unverified guesses. Click an enchantment to select
it, then use the `-` and `+` controls to choose its level; a green `MATCH`
marks an offer containing all selected enchantments at those levels.

With one or more targets selected, the panel searches every bookshelf count
from zero through the table's supported bookshelf power and tells you which
bookshelf count and offer slot will produce the target for the current seed.
After two consecutive real enchantments, Seed Explorer recovers the player's
Java RNG state. It can then search future states in action order and report the
minimal sequence: drop N items, perform one dummy enchantment, set the required
bookshelf power, and click the indicated offer. Each vanilla item drop consumes
four player-RNG calls, so drops must be made normally and no other random player
actions should be performed while following the plan.
Scroll the list when the item has more compatible enchantments. The panel is
read-only and does not click offers, throw items, or place bookshelves for you.

Open an enchanting table, hold the item to inspect in the main hand, then run:

```text
.seed-explorer enchant find sharpness 2, unbreaking 3
```

The command reports the three vanilla offers and only marks a match when the
requested enchantment levels are exact. Name-only requests (for example,
`.seed-explorer enchant find sharpness`) continue to match any level. Inventory
slots can be queried with `.seed-explorer enchant item <slot> <enchantments>`.

---

## Project Structure

```
src/main/java/me/seedexplorer/addon/
   loot/          Core prediction engine: structure simulation, loot-table
                  loading, deterministic item generation, and the product-facing
                  ChestLootPredictor API.
   worldgen/      Offline worldgen: noise-based terrain heightmap, biome source,
                  vanilla registry access, structure-start generation.
   structures/    Structure-type enum (StructureType), prediction status,
                  in-memory structure cache, dungeon predictor.
   enchant/       Enchanting-table XP-seed cracking, player RNG tracking,
                  offer prediction and target planning.
   ore/           Ore-vein prediction (based on density functions).
   seed/          Seed manager, seed cracking from world spawn chunks.
   search/        Loot search term parsing for item/enchanted-book queries.
   preview/       Offline structure preview extraction from vanilla templates.
   live/          Recording of observed chunk snapshots for comparison with
                  predictions.
   cache/         On-disk seed cache for repeated predictions.
   map/           Minimap biome generation and tile management.
   render/        Overlays (minimap, ore, structure markers), 3D terrain/viewer
                  rendering, streaming policies, UI icons and structure textures.
   gui/           Minecraft screens: seed-explorer main screen, 3D structure
                  viewer with orbit/zoom camera and sidebar loot panel.
   commands/      In-game chat commands (/seed, /locate, /loot, etc.).
   events/        Event bus types for seed-analysis lifecycle.
   mixin/         Client-side mixins (enchanting seed sync/RNG, predicted-mine
                  chat integration, container overlays).
   modules/       Meteor Client module registration.
   tools/         Headless verification tools (all Gradle task entry points).
   waypoints/     Structure waypoints tied to a seed.
   workers/       Background chunk-scan and seed-analysis workers.
   debug/         Prediction debug logging.
   utils/         Shared utility helpers.
```

**Validation evidence** lives in `validation/oracles/` (Paper-captured JSON
oracles pinned by SHA-256) and `validation/runtime/` (Paper server jars and
LootProbe capture run directories).

---

## How to Add a New Structure Type

1. **Add the enum constant** in `StructureType.java` with its Cubiomes-matching
   salt, region size, chunk range, and dimension.

2. **Register the BuiltinStructure key** in
   `ChestLootPredictor.STRUCTURE_MAP` so `predictForStructure()` can resolve
   it.

3. **If the structure has loot chests**, run `VanillaLootStructureSimulator`
   for a known seed/chunk and verify it produces the expected containers.

4. **Capture a Paper oracle** (see below) — this is the only accepted evidence
   for product promotion. The oracle must include all chest positions,
   loot-table IDs, and 64-bit loot seeds.

5. **Add the oracle file** to `validation/oracles/` and pin its SHA-256 in
   `PredictionRegressionSuite.validateOracle()`.

6. **Add a biome fallback** in `WorldgenEngine.generateStructureStart()` if
   the structure's biome tag may be unbound in offline lookups.

7. **Add `VALIDATED_STRUCTURES`** entry in `ChestLootPredictor` and add its
   loot tables to `VALIDATED_LOOT_TABLES`.

8. **Run `predictionRegressionSuite`** — it must pass with zero differences
   before any product exposure.

---

## Running a Paper Oracle Scan

> **Caveat:** The Paper API web endpoints (`api.papermc.io`) return HTTP
> 403/410 and are effectively sunset. However, Paper server JARs remain
> downloadable from the Paper website, and oracle scans can be run locally
> with a downloaded server + the LootProbe plugin.

Workflow:

1. Download `paper-26.1.2-74.jar` (or your target version) and place it in
   `validation/runtime/`.
2. Build the LootProbe plugin:
   `cd ../lootprobe-paper-plugin && .\gradlew jar`
3. Launch a headless Paper server with `--nogui` and the plugin installed.
4. The plugin scans a configurable region and writes a JSON oracle file with
   every container's position, loot-table ID, loot seed, and inventory slots.
5. Copy the JSON to `validation/oracles/` and update the SHA-256 pin in
   `PredictionRegressionSuite`.

**Validation evidence must be independently generated.** Simulator output
regenerated from the same codebase that it is meant to validate is circular
and is not accepted as an oracle. Every oracle file must trace back to a
fresh, independently launched Paper server.
