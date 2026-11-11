# Seed-Cracking Task List

Status legend:

- **DONE** — exact dependency-free baseline implemented in `seed-cracking/`.
- **BASE** — exact geometry/placement or a reusable partial baseline exists; the
  full method needs a Minecraft-dependent simulator or oracle.
- **ORACLE** — filter/interface exists; needs a validated version-matched oracle.
- **RESEARCH** — documented, no implementation yet.

No item is considered validated against a generated 26.1-26.3 world yet. That
audit is intentionally deferred per the current request.

Version coverage: `MinecraftVersion` and `VersionProfile` select the 26.1.x,
26.2.x, or 26.3.x data. The 19 random-spread sets shared by all three are
identical; 26.3 adds `minecraft:abandoned_camp`. The default Overworld bedrock
preset is floor-only for every audited version; the caves preset and the Nether
use floor plus roof.

## 1. Structure-based methods

| # | Method | Status | Baseline / next step | GitHub references |
| --- | --- | --- | --- | --- |
| 1 | Structure Coordinate Cracking | DONE | `PlacementSpec`, `StructureObservation`, `StructureSeedLifter` | 19MisterX98/SeedcrackerX |
| 2 | Structure Spacing Analysis | DONE | `PlacementSpec.range`, `StructureEntropy` | 19MisterX98/SeedcrackerX, Cubitect/cubiomes |
| 3 | Structure Triangulation | DONE | Multi-observation exact replay in `StructureSeedLifter` | 19MisterX98/SeedcrackerX |
| 4 | Structure Rotation Analysis | BASE | `StructureGeometry.Orientation` + `StructureVariantFilter` observation boundary; exact rotation needs structure-start oracle | 19MisterX98/SeedcrackerX |
| 5 | Structure Variant Analysis | BASE | `ShipwreckTemplateSelector` replays rotation/template draws from a supplied start RNG state; full version-matched start oracle remains missing | 19MisterX98/SeedcrackerX, Cubitect/cubiomes |
| 6 | Village Generation Analysis | BASE | Exact range-26 placement; five variant entries and Jigsaw biome/terrain/start oracle required; see `references/VILLAGE_AUDIT.md` | Cubitect/cubiomes, 19MisterX98/SeedcrackerX |
| 7 | Desert Temple Analysis | BASE | Exact range-24 placement + 20-bit lifting; 21x21/sea-level start gate and later loot need an oracle; see `references/DESERT_PYRAMID_AUDIT.md` | 19MisterX98/SeedcrackerX |
| 8 | Ocean Monument Analysis | BASE | Exact triangular placement; radius-29 biome gate/start oracle required; see `references/MONUMENT_MANSION_AUDIT.md` | Cubitect/cubiomes |
| 9 | Woodland Mansion Analysis | BASE | Exact triangular placement; rotation-dependent terrain and Y>=60 start gate required; see `references/MONUMENT_MANSION_AUDIT.md` | Cubitect/cubiomes |
| 10 | Pillager Outpost Analysis | BASE | Exact placement + 20-bit lifting + `PillagerOutpostFrequency`; village exclusion/biome/Jigsaw oracle remains | 19MisterX98/SeedcrackerX |
| 11 | Ruined Portal Analysis | BASE | Exact range-25 placement + versioned `RuinedPortalSelector`; seed-to-start RNG/biome/terrain oracle needed; see `references/RUINED_PORTAL_AUDIT.md` | Cubitect/cubiomes, jellejurre/GPULootSeedFinder |
| 12 | Shipwreck Analysis | DONE | Exact placement/lifting + `ShipwreckVariant` baseline | 19MisterX98/SeedcrackerX |
| 13 | Mineshaft Analysis | BASE | Exact range-1 placement + `MineshaftFrequency`; recursive piece/terrain/start oracle needed; see `references/MINESHAFT_AUDIT.md` | Cubitect/cubiomes |
| 14 | Stronghold Analysis | BASE | `ConcentricRingsStrongholdOracle` adapts exact low-48 ring geometry; version-matched full-seed biome gate still needed; see `references/STRONGHOLD_AUDIT.md` | Earthcomputer/StrongholdFinder, orlp/StrongholdCoords |
| 15 | Bastion Analysis | BASE | Shared `nether_complexes` placement is audited; weighted set selection and observable start-family/rotation may constrain candidates, but full Jigsaw start prediction is not verified; see `references/BASTION_AUDIT.md` | Cubitect/cubiomes |
| 16 | Nether Fortress Analysis | BASE | Shared `nether_complexes` placement audited; weighted recursive start/piece oracle remains unverified; see `references/FORTRESS_AUDIT.md` | Cubitect/cubiomes |
| 17 | Ancient City Analysis | BASE | Range-16 placement replay is available; deep-dark/terrain/Jigsaw start oracle absent; moderate-low priority; see `METHOD_AUDIT.md` | Cubitect/cubiomes |
| 18 | Trail Ruin Analysis | BASE | Range-26 random-spread replay only; buried terrain/template oracle absent; see `METHOD_AUDIT.md` | Cubitect/cubiomes |
| 19 | Trial Chamber Analysis | BASE | Range-22 placement replay is available; biome/terrain/Jigsaw start oracle absent; see `METHOD_AUDIT.md` | Cubitect/cubiomes |
| 20 | Multi-Structure Correlation | DONE | `SeedCracking`, `IncrementalCandidateSet` | 19MisterX98/SeedcrackerX, doublegate/MC-SeedFinder |

## 2. Biome and terrain methods

| # | Method | Status | Baseline / next step | GitHub references |
| --- | --- | --- | --- | --- |
| 21 | Biome Layout Cracking | ORACLE | `BiomeFilter` + `BiomeOracle`; cubiomes adapter | Cubitect/cubiomes, Cubitect/cubiomes-viewer |
| 22 | Biome Boundary Analysis | ORACLE | `BiomeBoundaryObservation` + `BiomeBoundaryFilter`; exact target-version biome oracle required | Cubitect/cubiomes |
| 23 | Biome Temperature Analysis | ORACLE | `ClimateObservation` + `ClimateFilter`; exact climate sampler required | Cubitect/cubiomes |
| 24 | Biome Noise Analysis | RESEARCH | Needs exact version-matched noise router exposure | Cubitect/cubiomes, misode/mcmeta |
| 25 | Terrain Heightmap Matching | ORACLE | `TerrainFilter` + `TerrainOracle` | Cubitect/cubiomes, doublegate/MC-SeedFinder |
| 26 | Mountain Shape Matching | ORACLE | Multi-column height profile via `TerrainFilter` | Cubitect/cubiomes |
| 27 | Coastline Matching | ORACLE | Height/biome samples along a coast | Cubitect/cubiomes |
| 28 | River Network Matching | RESEARCH | Needs river-noise/biome oracle | Cubitect/cubiomes |
| 29 | Lake Generation Analysis | RESEARCH | Needs surface/height oracle | Cubitect/cubiomes |
| 30 | Cave System Analysis | RESEARCH | Needs carver/aquifer implementation | Cubitect/cubiomes |
| 31 | Aquifer Analysis | RESEARCH | Needs version-matched aquifer implementation | Cubitect/cubiomes |
| 32 | Ravine Analysis | RESEARCH | Needs carver implementation | Cubitect/cubiomes |
| 33 | Overhang and Cliff Analysis | RESEARCH | Derived from exact terrain oracle | Cubitect/cubiomes |
| 34 | Surface Material Analysis | RESEARCH | Needs surface-rule/block oracle | Cubitect/cubiomes, misode/mcmeta |
| 35 | Multi-Region Terrain Matching | ORACLE | Multiple `TerrainObservation`s through one filter | Cubitect/cubiomes |

## 3. RNG and feature-generation methods

| # | Method | Status | Baseline / next step | GitHub references |
| --- | --- | --- | --- | --- |
| 36 | Population Seed Recovery | BASE | `WorldgenSeeds263`; feature oracle needed | KaptainWutax/FeatureUtils |
| 37 | Decorator RNG Analysis | BASE | `WorldgenSeeds263.decorationSeed` | KaptainWutax/FeatureUtils |
| 38 | Feature Placement Analysis | ORACLE | `FeaturePlacementObservation` + `FeaturePlacementFilter`; placed-feature oracle required | KaptainWutax/FeatureUtils, 19MisterX98/SeedcrackerX |
| 39 | Ore Vein Analysis | RESEARCH | Version-matched ore feature simulator needed | SeedFinding/fnseedc, 19MisterX98/SeedcrackerX |
| 40 | Emerald Ore Analysis | RESEARCH | Legacy SeedCrackerX finder reference only | 19MisterX98/SeedcrackerX |
| 41 | Vegetation Pattern Analysis | RESEARCH | Feature + biome oracle | KaptainWutax/FeatureUtils |
| 42 | Flower Pattern Analysis | RESEARCH | Feature + biome oracle | KaptainWutax/FeatureUtils |
| 43 | Tree Generation Analysis | RESEARCH | Tree feature simulator needed | Nel-S/tree-cracker, MCRcortex/TreeCracker, polymetric/treecrackerPOS |
| 44 | Mushroom Generation Analysis | RESEARCH | Feature + biome oracle | Cubitect/cubiomes |
| 45 | Dripstone Generation Analysis | RESEARCH | Feature/cave oracle | Cubitect/cubiomes |
| 46 | Lush Cave Feature Analysis | RESEARCH | Feature/cave oracle | Cubitect/cubiomes |
| 47 | Geode Generation Analysis | RESEARCH | Feature simulator needed | SeedFinding/fnseedc |
| 48 | Dungeon Generation Analysis | RESEARCH | SeedCrackerX dungeon finder reference | 19MisterX98/SeedcrackerX |
| 49 | Spawner Location Analysis | RESEARCH | Structure-start/tile-entity oracle | 19MisterX98/SeedcrackerX |
| 50 | Random Sequence Recovery | BASE | `Lcg48OutputRecovery` handles mixed raw 24-32-bit, float, and double outputs with known per-observation gaps; bounded outputs and unknown gaps remain | mjtb49/LattiCG, mjtb49/ChunkRandomReversal |

## 4. Special world-generation methods

| # | Method | Status | Baseline / next step | GitHub references |
| --- | --- | --- | --- | --- |
| 51 | End Pillar Height Analysis | DONE | `EndPillarFingerprint` | 19MisterX98/SeedcrackerX |
| 52 | End Pillar Ordering Analysis | DONE | Exact shuffle/key model | 19MisterX98/SeedcrackerX |
| 53 | Nether Bedrock Pattern Analysis | DONE | `BedrockPreset.NETHER` floor+roof model; independent observations/validation pending | 19MisterX98/Nether_Bedrock_Cracker |
| 54 | Slime Chunk Cracking | DONE | `SlimeChunkFilter` | 19MisterX98/SeedcrackerX |
| 55 | Mushroom Island Analysis | ORACLE | `BiomeFilter` | Cubitect/cubiomes |
| 56 | Nether Biome Analysis | ORACLE | Cubiomes/version-matched Nether biome oracle | Cubitect/cubiomes |
| 57 | Nether Terrain Analysis | RESEARCH | Version-matched Nether noise/terrain oracle | Cubitect/cubiomes |
| 58 | Nether Lava Lake Analysis | RESEARCH | Nether aquifer/terrain oracle | Cubitect/cubiomes |
| 59 | Bastion Chest Configuration | ORACLE | `LootFilter` + bastion loot oracle | jellejurre/GPULootSeedFinder, cev-api/LootProbe |
| 60 | End Gateway Analysis | RESEARCH | SeedCrackerX observation path; exact simulator missing | 19MisterX98/SeedcrackerX |

## 5. Loot and inventory methods

| # | Method | Status | Baseline / next step | GitHub references |
| --- | --- | --- | --- | --- |
| 61 | Structure Chest Loot Analysis | ORACLE | `LootFilter` + exact version-matched loot chain | cev-api/LootProbe, DuncanRuns/MiLTSU |
| 62 | Loot Combination Matching | ORACLE | Exact stack-list comparison via `LootFilter` | jellejurre/GPULootSeedFinder |
| 63 | Loot Quantity Analysis | ORACLE | Stack counts are part of `LootObservation` | DuncanRuns/MiLTSU |
| 64 | Buried Treasure Analysis | BASE | Placement range 1; treasure loot oracle needed | 19MisterX98/SeedcrackerX, cev-api/LootProbe |
| 65 | Shipwreck Loot Analysis | ORACLE | `ShipwreckVariant` + `LootFilter` | jellejurre/GPULootSeedFinder |
| 66 | Dungeon Chest Loot Analysis | ORACLE | `LootFilter` + dungeon start oracle | 19MisterX98/SeedcrackerX, MiLTSU |
| 67 | Mineshaft Chest Loot Analysis | ORACLE | `LootFilter` + mineshaft start oracle | DuncanRuns/MiLTSU |
| 68 | Bastion Loot Analysis | ORACLE | `LootFilter` + bastion start oracle | cev-api/LootProbe |
| 69 | Ancient City Loot Analysis | ORACLE | `LootFilter` + ancient city start oracle | DuncanRuns/MiLTSU |
| 70 | Fishing Loot Analysis | RESEARCH | Fishing uses bobber RNG/luck, not directly world seed | DuncanRuns/MiLTSU |

## 6. Mathematical and computational methods

| # | Method | Status | Baseline / next step | GitHub references |
| --- | --- | --- | --- | --- |
| 71 | 48-Bit Structure Seed Recovery | DONE | `StructureSeedLifter` | 19MisterX98/SeedcrackerX, hube12/MinecraftSeedCrackingC |
| 72 | 16-Bit Seed Extension | DONE | `UpperBitRecovery` | 19MisterX98/SeedcrackerX |
| 73 | Brute-Force Enumeration | DONE | Parallel lifting/recovery with budgets | hube12/MinecraftSeedCrackingC |
| 74 | GPU-Accelerated Brute Force | RESEARCH | CPU baseline only; GPU port needed | jellejurre/GPULootSeedFinder, hube12/MinecraftSeedCrackingC |
| 75 | CPU Multithreaded Search | DONE | `StructureSeedLifter`, `UpperBitRecovery`, `PillarTimeMachine.searchRangeParallel` | 19MisterX98/SeedcrackerX |
| 76 | RNG Reversal | BASE | `Lcg48.advanceState`/`retreatState` jump exactly; mixed-width output recovery accepts known per-observation gaps; bounded outputs and unknown gaps remain open | mjtb49/ChunkRandomReversal |
| 77 | Java Random Reversal | BASE | `Lcg48OutputRecovery` recovers seeds from mixed raw `next(bits)`, `nextFloat()`, `nextDouble()`, `nextInt()`, and `nextLong()` outputs; bounded-output/lattice recovery remains open | mjtb49/LattiCG |
| 78 | Linear Congruential Generator Analysis | DONE | `Lcg48` + modular inverse + logarithmic jump-ahead/reversal | mjtb49/LattiCG |
| 79 | Lattice Reduction | RESEARCH | Not implemented | mjtb49/LattiCG |
| 80 | Bit Lifting | DONE | Range-derived low-bit sieve | 19MisterX98/SeedcrackerX |
| 81 | Modular Arithmetic Analysis | DONE | Exact `% 2^m` derivation and replay | 19MisterX98/SeedcrackerX |
| 82 | Constraint Satisfaction | BASE | Sequential filter pipeline; no SAT encoding | jonathan-rogers-dev/mceed |
| 83 | SAT Solver Cracking | RESEARCH | No encoding yet | deut-erium/RNGeesus |
| 84 | SMT Solver Cracking | RESEARCH | Z3 reference implementation exists | EricSpencer00/reverse-xoroshiro128plusplus |
| 85 | Meet-in-the-Middle Search | BASE | `PillarTimeMachine` split and parallel search; full structure-start predicate still external | 19MisterX98/SeedcrackerX |
| 86 | Probabilistic Candidate Ranking | DONE | `CandidateRanker` | doublegate/MC-SeedFinder |
| 87 | Multi-Source Seed Recovery | DONE | `SeedCracking` + multiple filters | 19MisterX98/SeedcrackerX |
| 88 | Incremental Seed Recovery | DONE | `IncrementalCandidateSet` | 19MisterX98/SeedcrackerX |

## 7. Image recognition and automated observation

| # | Method | Status | Baseline / next step | GitHub references |
| --- | --- | --- | --- | --- |
| 89 | Screenshot-Based Structure Detection | RESEARCH | No CV in module; emit `StructureObservation` | Cubitect/cubiomes-viewer |
| 90 | Coordinate Extraction from Maps | RESEARCH | Manual/map adapter needed | Cubitect/cubiomes-viewer |
| 91 | Biome Recognition | RESEARCH | CV + `BiomeOracle` validation | Cubitect/cubiomes |
| 92 | Terrain Image Matching | RESEARCH | Render candidate maps and compare | Cubitect/cubiomes, cev-api/VoxelMap-x-SeedMapper |
| 93 | Block Pattern Recognition | ORACLE | `BlockPatternObservation` + `BlockPatternFilter` accepts explicit block samples; visual classifier/oracle required | acheong08/MC-SeedLocator |
| 94 | Chest Content Recognition | RESEARCH | OCR + `LootObservation` | cev-api/LootProbe |
| 95 | Structure Orientation Recognition | BASE | `StructureGeometry.Orientation` | 19MisterX98/SeedcrackerX |
| 96 | World Map Reconstruction | RESEARCH | Map/candidate renderer needed | Cubitect/cubiomes-viewer |
| 97 | Multi-Screenshot Correlation | BASE | `CandidateRanker`, `IncrementalCandidateSet` | doublegate/MC-SeedFinder |

## 8. Experimental and hybrid methods

| # | Method | Status | Baseline / next step | GitHub references |
| --- | --- | --- | --- | --- |
| 98 | Cross-Version Seed Analysis | BASE | `MinecraftVersion`/`VersionProfile` cover 26.1-26.3; add future versions only after audit | Cubitect/cubiomes |
| 99 | World Generation Code Analysis | DONE | 26.1.2-26.3 bytecode and data extraction for implemented primitives | misode/mcmeta |
| 100 | Seed RNG Dependency Mapping | DONE | Low-48 vs full-64 documented | This module |
| 101 | Chunk Boundary Analysis | DONE | `StructureGeometry`, exact chunk/region math | This module |
| 102 | Regional RNG Analysis | DONE | Region-salted placement documented | This module |
| 103 | Feature Dependency Analysis | BASE | `WorldgenSeeds263` + docs | KaptainWutax/FeatureUtils |
| 104 | Rare Feature Correlation | BASE | `BiomeFilter` + `CandidateRanker` | Cubitect/cubiomes |
| 105 | Anomaly-Based Seed Identification | DONE | `CandidateRanker` partial-match scoring | doublegate/MC-SeedFinder |
| 106 | Hybrid Seed Cracking | BASE | `SeedCracking` facade plus oracle filters | 19MisterX98/SeedcrackerX, Cubitect/cubiomes |

## Near-term implementation order

- [x] Exact version-selected placement model and structure-set catalog.
- [x] Low-48 lifting and upper-16 recovery.
- [x] End pillar, slime, bedrock, stronghold-ring, and `WorldgenRandom` math.
- [x] Facade, ranking, incremental filtering, and method inventory.
- [x] 26.1/26.2/26.3 placement, bedrock-preset, and version-profile support.
- [ ] Add a version-matched observation adapter outside `seed-cracking/` (do not
      modify existing screens/commands yet).
- [ ] Audit placement, pillars, slime, bedrock, and strongholds against generated
      26.1.2, 26.2, and 26.3 worlds.
- [ ] Add a validated biome oracle and wire `BiomeFilter`.
- [ ] Add a validated terrain-height oracle and wire `TerrainFilter`.
- [ ] Complete `ConcentricRings263.BiomeGate`.
- [ ] Add a structure-start oracle for existence/variant/rotation.
- [ ] Add independent Nether bedrock observations and validate the existing model.
- [ ] Add exact version-matched loot-chain support.
- [ ] Consider GPU/CV work only after the CPU pipeline is audited.

## Blockers and cautions

- 26.1-26.3 changed worldgen data and code relative to older versions; older
  libraries and oracle outputs cannot be assumed correct.
- 26.3 adds abandoned camps; do not apply the 26.3 catalog to 26.1/26.2 worlds.
- The default Overworld has no bedrock roof. Only the caves preset and the
  Nether use the roof rule.
- Loot and terrain oracles are the highest-risk stages because a subtle RNG
  consumption mismatch silently rejects the true seed.
- A filter that only reads the low 48 bits cannot recover the upper 16 bits.
- Stronghold ring centres are low-48 geometry; the biome-offset final step is
  the full-seed-dependent part.
- `PlacementSpec` proves a potential chunk, not structure existence.
