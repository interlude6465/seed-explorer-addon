# Meteor Seed Explorer Addon

Offline Minecraft loot prediction for Meteor Client. Simulates vanilla worldgen
deterministically from a world seed to predict chest contents in structures
**without** loading any chunks or connecting to a server.

**Currently validated:** desert pyramids (4 seeds, 16/16 chests, zero differences
against Paper 26.1.2-74). Stronghold loot-seed parity is in advanced research.

---

## Build

```powershell
.\gradlew compileJava
```

Java 25 toolchain. Dependencies are resolved from the parent meteor-client
build tree and the Gradle cache — no Fabric Loom plugin required.

---

## Verification Tasks

All tasks are registered under the `verification` Gradle group.

| Task | Class | What it checks |
|---|---|---|
| `predictionRegressionSuite` | `PredictionRegressionSuite` | **Fail-fast gate.** Runs all accepted oracles (4 desert + 1 stronghold), checks vanilla terrain height SHA-256, carver-replaceable tag count (52), critical carved height — exits 2 on any mismatch. |
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

---

## Project Structure

```
src/main/java/me/seedexplorer/addon/
  loot/          Core prediction engine: structure simulation, loot-table
                 loading, deterministic item generation, and the product-facing
                 ChestLootPredictor API (desert pyramid + stronghold only).
  worldgen/      Offline worldgen: noise-based terrain heightmap, biome source,
                 vanilla registry access, structure-start generation.
  structures/    Structure-type enum (StructureType), prediction status,
                 in-memory structure cache.
  ore/           Ore-vein prediction (based on density functions).
  seed/          Seed manager, seed cracking from world spawn chunks.
  cache/         On-disk seed cache for repeated predictions.
  map/           Minimap biome generation and tile management.
  render/        Overlays (minimap, ore, structure markers), 3D viewer
                 rendering, UI icons and structure textures.
  gui/           Minecraft screens: seed-explorer main screen, 3D structure
                 viewer with orbit/zoom camera and sidebar loot panel.
  commands/      In-game chat commands (/seed, /locate, /loot, etc.).
  events/        Event bus types for seed-analysis lifecycle.
  mixin/         Client-side mixins for predicted-mine chat integration.
  modules/       Meteor Client module registration.
  tools/         Headless verification tools (all Gradle task entry points).
  waypoints/     Structure waypoints tied to a seed.
  workers/       Background chunk-scan and seed-analysis workers.
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
