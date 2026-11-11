# Method Research Progress

This ledger tracks the 106 methods in `TASK_LIST.md` individually. It is
deliberately separate from the implementation-status labels there: an existing
baseline is not considered researched or verified merely because code exists.

## Status key

- `R`: source/research review; `M`: mathematical derivation; `V`: Java 26.1,
  26.2, and 26.3 compatibility review; `C`: standalone code; `T`: compilation
  or behavioral test. `pending` means work remains, `partial` means baseline
  evidence exists but review is incomplete, and `done` means reviewed.
- `P` is the current estimated probability that the described code matches the
  target versions. Estimates are provisional until source comparison and an
  independent behavioral test are recorded. A method under 85% is not complete.
- Limits record the main known boundary. Detailed evidence belongs in the
  method notes under `research/` as the audit proceeds.

## 1. Structure methods

| # | Method | R / M / V | C | T | P | Limits / next evidence |
|---:|---|---|---|---|---:|---|
| 1 | Structure Coordinate Cracking | done / done / done | `PlacementSpec`, `StructureSeedLifter` | Java 21 compile, self-tests, 2M low-bit cases; 26.1.2/26.2/26.3 placement bytecode and catalog data compared | 85% for vanilla placement/lifting; generated-start identification unverified | Assumes the correct vanilla start/potential chunk and version catalog; generated-world vectors still needed. See `references/PLACEMENT_AUDIT.md`. |
| 2 | Structure Spacing Analysis | done / partial / done | `PlacementSpec.range`, `StructureEntropy` | 26.1.2/26.2/26.3 structure-set data comparison; catalog arithmetic reviewed | 80% provisional | Entropy is an approximation; summed bits may overstate information when observations/gates are dependent. See `references/PLACEMENT_AUDIT.md`. |
| 3 | Structure Triangulation | partial / partial / done | `StructureSeedLifter`, `findLowCandidates` | Four-observation low-seed intersection test retains the true seed and yields one candidate for its fixed fixture | 80% provisional | This is constraint intersection rather than geometric bearing triangulation; independent generated-world vectors remain absent. |
| 4 | Structure Rotation Analysis | partial / partial / partial | `StructureOrientationObservation`, `StructureOrientationOracle`, `StructureOrientationFilter` | Synthetic oracle match and missing-start rejection test | 55% provisional | Filter contract is functional; exact target-version structure-start/rotation RNG oracle and observation extraction are still required. |
| 5 | Structure Variant Analysis | done / done / done | `ShipwreckTemplateSelector`, `ShipwreckVariant`, `StructureVariantFilter` | 10,000-seed selector bounds test; 26.1.2/26.2/26.3 `ShipwreckStructure` and `ShipwreckPieces` bytecode compared | 75% provisional | Selector is exact only given the correctly seeded/advanced structure LCG; terrain gates, chest marker processing, and start seeding still need a runtime oracle. |
| 6 | Village Generation Analysis | done / partial / done | exact placement plus `StructureStartFilter`/`StructureVariantFilter` oracle boundary | 26.1.2/26.2/26.3 structure-set data and 26.2 `ChunkGeneratorStructureState`/`JigsawStructure` bytecode reviewed; placement self-test | 50% provisional | Jigsaw pools, start-height, biome/climate and terrain oracle remain. See `references/VILLAGE_AUDIT.md`. |
| 7 | Desert Temple Analysis | done / partial / done | placement and lifting; generic start/orientation/block/loot oracle filters | 26.1.2/26.2/26.3 catalog data and 26.2 single-piece/pyramid bytecode reviewed; placement self-test | 80% provisional | Terrain rejection, start extraction, chest/loot, and generated-world fixture absent. See `references/DESERT_PYRAMID_AUDIT.md`. |
| 8 | Ocean Monument Analysis | done / partial / done | triangular placement plus generic start/orientation filters | 26.1.2/26.2/26.3 placement data and 26.2 biome/heightmap start bytecode reviewed; placement self-test | 80% provisional | Radius-29 biome tag, start selection and ocean-floor height need an exact oracle; no generated-world fixture. See `references/MONUMENT_MANSION_AUDIT.md`. |
| 9 | Woodland Mansion Analysis | done / partial / done | triangular placement plus `StructureOrientationFilter`/`StructureStartFilter` | 26.1.2/26.2/26.3 placement data and 26.2 rotation/terrain start bytecode reviewed; placement self-test | 80% provisional | Rotation-dependent 5x5 terrain and Y>=60 gate need an exact oracle; no generated-world fixture. See `references/MONUMENT_MANSION_AUDIT.md`. |
| 10 | Pillager Outpost Analysis | done / done / done | placement/lifting + `PillagerOutpostFrequency` | Java 21 compile/self-test; 26.1.2/26.2/26.3 reducer bytecode comparison | 82% provisional | Exact frequency reducer is implemented; village exclusion, biome tag, terrain adaptation and Jigsaw start still require a version-matched oracle. See `references/PILLAGER_OUTPOST_AUDIT.md`. |
| 11 | Ruined Portal Analysis | done / done / done | placement/lifting + versioned `RuinedPortalSelector` | Java 21 compile/self-test; 26.1.2/26.2/26.3 selector bytecode and setup data compared; 10,000 RNG-sequence cases | 78% provisional | Exact selector conditional on its caller-supplied RNG state; full seed-to-start RNG, biome, terrain/Y, template placement and portal/loot oracle remain. 26.3 changed four setup probabilities. See `references/RUINED_PORTAL_AUDIT.md`. |
| 12 | Shipwreck Analysis | done / done / done | placement/lifting + `ShipwreckTemplateSelector.selectForStart` | Java 21 compile/self-tests; 26.1.2/26.2/26.3 data and template arrays compared; seed/start replay checked against `java.util.Random` over signed/extreme seeds and negative chunks | 82% provisional | Exact start RNG seed, rotation, and template list are low-48-only; beached Y adjustment, terrain, biome gate, and generated-world observation extraction remain outside this segment. |
| 13 | Mineshaft Analysis | done / done / done | range-1 placement + `MineshaftFrequency` reducer/type selector | Java 21 compile/self-test; 26.1.2/26.2/26.3 structure-set and reducer bytecode review; signed/extreme seed vectors | 70% provisional | Coordinates carry no entropy; recursive pieces, terrain adjustment, biome/start, and loot remain oracle-dependent. See `references/MINESHAFT_AUDIT.md`. |
| 14 | Stronghold Analysis | done / done / done | `ConcentricRings263`, `ConcentricRingsStrongholdOracle`, `StrongholdFilter` | Java 21 compile/self-tests; 26.1.2/26.2/26.3 ring placement and RNG fork bytecode reviewed | 75% provisional | Ring centers constrain only low 48 bits; final indexed coordinates require exact preferred-biome search/fallback oracle and generated-world fixtures. See `references/STRONGHOLD_AUDIT.md`. |
| 15 | Bastion Analysis | done / done / partial | shared `nether_complexes` placement; no new standalone segment pending final top-ten selection | Existing placement self-test; 26.2 structure-set JSON and Jigsaw bytecode reviewed; 26.1.2 Nether oracle capture inspected (not parity validation) | 55% placement/selection; 30% full start prediction | Four start families and rotation are observable, but shortcut RNG predictions are not accepted without target-version Jigsaw pool/start parity. Full-seed Nether biome and template generation oracle needed. See `references/BASTION_AUDIT.md`. |
| 16 | Nether Fortress Analysis | done / done / partial | shared `nether_complexes` placement; no new standalone segment pending final top-ten selection | Placement self-test; 26.2 `NetherFortressStructure`/piece bytecode reviewed | 55% placement; 30% full start prediction | Start uses weighted recursive bridge/castle expansion and height relocation; exact piece/terrain replay and 26.1/26.3 fixtures remain absent. See `references/FORTRESS_AUDIT.md`. |
| 17 | Ancient City Analysis | done / partial / partial | range-16 placement baseline; no separate implementation pending final selection | Existing placement self-test; 26.2 catalog reviewed | 60% placement; 25% full prediction | Deep-dark/terrain and Jigsaw start gates require exact oracle; lower priority than robust low-bit structure intersections. |
| 18 | Trail Ruin Analysis | done / partial / partial | range-26 placement replay baseline; no standalone segment pending final selection | Catalog values reviewed; generic placement self-test | 55% placement; 20% full prediction | Range 26 is outside current low-bit sieve; buried terrain/template start oracle absent; likely redundant with terrain/Jigsaw filters. |
| 19 | Trial Chamber Analysis | done / partial / partial | range-22 placement replay baseline; no standalone segment pending final selection | Catalog values reviewed; generic placement self-test | 60% placement; 25% full prediction | Biome/terrain/Jigsaw start and variant oracle absent; lower priority than independent low-bit and upper-bit filters. |
| 20 | Multi-Structure Correlation | done / done / done | `SeedCracking`, `IncrementalCandidateSet` | Java 21 compile/self-test; synthetic pipeline tests pass | 80% provisional | Composition adds no entropy by itself; full recovery depends on exact observations and oracles. Included in the focused ten as the coordinator. |

## 2. Biome and terrain methods

| # | Method | R / M / V | C | T | P | Limits / next evidence |
|---:|---|---|---|---|---:|---|
| 21 | Biome Layout Cracking | done / done / partial | `BiomeFilter`/`BiomeOracle` | Synthetic filter only; no Minecraft-backed runtime oracle | 40% readiness | Strongest complement for distinguishing upper-16 extensions after low-48 structure recovery; implement/validate versioned biome adapter before counting as complete. |
| 22 | Biome Boundary Analysis | done / done / partial | `BiomeBoundaryObservation`, `BiomeBoundaryFilter` | synthetic filter self-test | 35% provisional | Nearby samples correlate; target-version biome oracle absent. |
| 23 | Biome Temperature Analysis | done / done / partial | `ClimateObservation`, `ClimateFilter`, `ClimateOracle` | synthetic tolerance self-test | 20% provisional | Exact sampler scale/oracle absent. |
| 24 | Biome Noise Analysis | done / done / done | research-only; no code claimed | source/version risk review | 10% provisional | Exact noise-router parity absent; intentionally skipped. |
| 25 | Terrain Heightmap Matching | done / done / partial | `TerrainFilter`/oracle contract | contract inspection | 35% provisional | Versioned noise/heightmap oracle absent. |
| 26 | Mountain Shape Matching | done / done / partial | multi-column `TerrainProfileFilter` | synthetic composition only | 30% provisional | Correlated samples and terrain oracle gap. |
| 27 | Coastline Matching | done / done / partial | biome/terrain filter composition | none | 25% provisional | Joint oracle absent. |
| 28 | River Network Analysis | done / done / done | research-only | none | 10% provisional | Router/source absent; redundant with biome layout. |
| 29 | Lake Generation Analysis | done / done / done | research-only | none | 10% provisional | Feature/terrain simulator absent. |
| 30 | Cave System Analysis | done / done / done | research-only | none | 5% provisional | Carver/aquifer simulator absent. |
| 31 | Aquifer Analysis | done / done / done | research-only | none | 5% provisional | Version-specific internals absent. |
| 32 | Ravine Analysis | done / done / done | research-only | none | 5% provisional | Carver simulator absent. |
| 33 | Overhang and Cliff Analysis | done / done / done | compose terrain profile when oracle exists | none | 10% provisional | Derived, no independent engine. |
| 34 | Surface Material Analysis | done / done / partial | `BlockPatternFilter` contract | synthetic filter only | 15% provisional | Surface-rule/block oracle absent. |
| 35 | Multi-Region Terrain Matching | done / done / partial | multi-sample `TerrainProfileFilter` | synthetic composition only | 30% provisional | Oracle and generated-world checks absent. |

## 3. RNG and feature-generation methods

| # | Method | R / M / V | C | T | P | Limits / next evidence |
|---:|---|---|---|---|---:|---|
| 36 | Population Seed Recovery | done / done / partial | `WorldgenSeeds263` primitives | primitive self-tests only | 45% provisional | Need observed-feature adapter and call-order checks. |
| 37 | Decorator RNG Analysis | done / done / partial | decoration-seed helper | primitive self-tests only | 50% provisional | Need decorator-specific feature replay. |
| 38 | Feature Placement Analysis | done / done / partial | `FeaturePlacementObservation`, `FeaturePlacementFilter`, `FeaturePlacementOracle` | synthetic contract test | 25% provisional | Exact attempts, ordering, biome filters, and RNG consumption need target-version oracle. |
| 39 | Ore Vein Analysis | done / done / done | research-only | none | 5% provisional | Feature/block simulator absent. |
| 40 | Emerald Ore Analysis | done / done / done | research-only | none | 5% provisional | Legacy finder does not establish target-version behavior. |
| 41 | Vegetation Pattern Analysis | done / done / done | research-only | none | 5% provisional | Feature and biome oracle absent. |
| 42 | Flower Pattern Analysis | done / done / done | research-only | none | 5% provisional | Feature oracle absent. |
| 43 | Tree Generation Analysis | done / done / done | research-only | none | 5% provisional | Target-version tree simulator absent. |
| 44 | Mushroom Generation Analysis | done / done / done | research-only | none | 5% provisional | Feature and biome oracle absent. |
| 45 | Dripstone Generation Analysis | done / done / done | research-only | none | 5% provisional | Cave/feature simulator absent. |
| 46 | Lush Cave Feature Analysis | done / done / done | research-only | none | 5% provisional | Cave/feature simulator absent. |
| 47 | Geode Generation Analysis | done / done / done | research-only | none | 5% provisional | Geode simulator absent. |
| 48 | Dungeon Generation Analysis | done / done / done | research-only | none | 5% provisional | Structure/cave/loot oracle absent. |
| 49 | Spawner Location Analysis | done / done / done | research-only | none | 5% provisional | Tile entity is downstream of dungeon generation, not independent seed RNG. |
| 50 | Random Sequence Recovery | done / done / done | `Lcg48OutputRecovery` | Java 25 compile + full dependency-free self-test passed, including mixed widths and known gaps | 82% provisional | Exact for supported outputs/gaps; bounded outputs, unknown gaps, and non-LCG paths remain outside scope. |

## 4. Special world-generation methods

| # | Method | R / M / V | C | T | P | Limits / next evidence |
|---:|---|---|---|---|---:|---|
| 51 | End Pillar Height Analysis | done / done / partial | `EndPillarFingerprint` | fingerprint self-test; 26.2 `EndSpikeFeature` confirms `nextLong() & 0xFFFF` cache key | 80% provisional | Generated End-world height comparison pending; useful seed filter with structure placement. |
| 52 | End Pillar Ordering Analysis | done / done / partial | same `EndPillarFingerprint` permutation | fingerprint self-test; ordering is the same shuffle as #51 | 80% provisional | Not independent of height fingerprint; do not double-count entropy. |
| 53 | Nether Bedrock Pattern Analysis | done / done / partial | bedrock model/oracle | synthetic model tests only | 60% provisional | Preset and generated Nether vectors pending. |
| 54 | Slime Chunk Cracking | done / done / partial | `SlimeChunkFilter` | arithmetic/self-tests | 75% provisional | Generated-world truth checks pending; strong cheap full-seed filter. |
| 55 | Mushroom Island Analysis | done / done / partial | `BiomeFilter` reuse | no runtime oracle | 30% provisional | Special biome case; no independent segment. |
| 56 | Nether Biome Analysis | done / done / partial | dimension-aware `BiomeFilter` contract | no runtime oracle | 25% provisional | Requires exact Nether biome source; potential complement to Overworld biome samples. |
| 57 | Nether Terrain Analysis | done / done / done | terrain filter contract | none | 10% provisional | Nether density/noise oracle absent; overlaps #25/#56. |
| 58 | Nether Lava Lake Analysis | done / done / done | research-only | none | 5% provisional | Nether aquifer/lava simulator absent. |
| 59 | Bastion Chest Configuration | done / done / done | loot filter boundary | none | 15% provisional | Bastion start and loot oracle absent; downstream of #15. |
| 60 | End Gateway Analysis | done / done / done | research-only | none | 5% provisional | End runtime/state model absent. |

## 5. Loot and inventory methods

| # | Method | R / M / V | C | T | P | Limits / next evidence |
|---:|---|---|---|---|---:|---|
| 61 | Structure Chest Loot Analysis | done / done / partial | generic loot filter/oracle boundary | synthetic filter only | 20% provisional | Exact target-version loot chain absent. |
| 62 | Loot Combination Matching | done / done / partial | item multiset filter | synthetic comparison only | 40% provisional | Prediction oracle absent. |
| 63 | Loot Quantity Analysis | done / done / partial | item-count constraint | synthetic comparison only | 40% provisional | Downstream of loot oracle. |
| 64 | Buried Treasure Analysis | done / done / partial | placement baseline | placement self-test | 25% provisional | Terrain/start/loot gates absent. |
| 65 | Shipwreck Loot Analysis | done / done / partial | variant + loot filter composition | synthetic composition only | 20% provisional | Exact variant/loot oracle absent. |
| 66 | Dungeon Chest Loot Analysis | done / done / done | research-only | none | 10% provisional | Dungeon generation/loot oracle absent. |
| 67 | Mineshaft Chest Loot Analysis | done / done / done | research-only | none | 10% provisional | Start/entity/loot oracle absent. |
| 68 | Bastion Loot Analysis | done / done / done | research-only | none | 10% provisional | Bastion start/loot oracle absent. |
| 69 | Ancient City Loot Analysis | done / done / done | research-only | none | 10% provisional | Ancient-city start/loot oracle absent. |
| 70 | Fishing Loot Analysis | done / done / done | non-world-seed boundary | none | 10% direct-seed viability | Player RNG is not a direct world-seed signal. |

## 6. Mathematical and computational methods

| # | Method | R / M / V | C | T | P | Limits / next evidence |
|---:|---|---|---|---|---:|---|
| 71 | 48-Bit Structure Seed Recovery | done / done / partial | `StructureSeedLifter` | Java 21 compile/self-tests and placement replay | 82% provisional | Generated-world vectors absent. |
| 72 | 16-Bit Seed Extension | done / done / partial | `UpperBitRecovery` | synthetic enumeration tests | 80% provisional | Oracle truth is unvalidated. |
| 73 | Brute-Force Enumeration | done / done / partial | parallel lifter/recovery/time machine | small-shard self-test | 80% provisional | Large-search completeness/stress vectors absent. |
| 74 | GPU-Accelerated Brute Force | done / done / done | research-only | none | 5% provisional | No GPU backend; CPU is reference. |
| 75 | CPU Multithreaded Search | done / done / partial | parallel lifter/recovery/pillar search | small-range test | 80% provisional | Production stress/cancellation measurements absent. |
| 76 | RNG Reversal | done / done / done | logarithmic LCG advance/retreat | jump/output self-tests | 85% provisional | Unknown gaps/bounded outputs remain. |
| 77 | Java Random Reversal | done / done / partial | mixed-width output recovery | recovery self-tests | 82% provisional | First output must expose >=24 bits. |
| 78 | Linear Congruential Generator Analysis | done / done / done | `Lcg48` arithmetic | jump/reversal self-tests | 85% provisional | Independent Minecraft vectors pending. |
| 79 | Lattice Reduction | done / done / done | research-only | none | 5% provisional | No solver implementation. |
| 80 | Bit Lifting | done / done / partial | range-derived structure sieve | placement self-test | 82% provisional | Independent vectors pending. |
| 81 | Modular Arithmetic Analysis | done / done / partial | placement modulo sieve | placement self-test | 82% provisional | Edge-case/generated vectors pending. |
| 82 | Constraint Satisfaction | done / done / partial | sequential filter pipeline | pipeline self-test | 65% provisional | No general CSP explanation engine. |
| 83 | SAT Solver Cracking | done / done / done | research-only | none | 5% provisional | No encoding/dependency. |
| 84 | SMT Solver Cracking | done / done / done | research-only | none | 5% provisional | No Z3 encoding/dependency. |
| 85 | Meet-in-the-Middle Search | done / done / partial | pillar structured search | small-shard test | 60% provisional | Not a generic MITM solver. |
| 86 | Probabilistic Candidate Ranking | done / done / partial | `CandidateRanker` | synthetic composition only | 55% provisional | Weights need calibration. |
| 87 | Multi-Source Seed Recovery | done / done / partial | `SeedCracking` + filters | pipeline self-test | 65% provisional | Exact oracles needed. |
| 88 | Incremental Seed Recovery | done / done / partial | `IncrementalCandidateSet` | synthetic mechanics only | 65% provisional | History/candidate-kind audit remains. |

## 7. Image and automated observation methods

| # | Method | R / M / V | C | T | P | Limits / next evidence |
|---:|---|---|---|---|---:|---|
| 89 | Screenshot-Based Structure Detection | done / done / done | research-only | none | 5% provisional | CV model/coordinate calibration absent; defer. |
| 90 | Coordinate Extraction from Maps | done / done / done | research-only | none | 5% provisional | External map adapter absent; defer. |
| 91 | Biome Recognition | done / done / done | research-only | none | 5% provisional | Classifier/palette absent; exact biome oracle is separate. |
| 92 | Terrain Image Matching | done / done / done | research-only | none | 5% provisional | Renderer/registration/oracle absent. |
| 93 | Block Pattern Recognition | done / done / partial | `BlockPatternObservation`/`BlockPatternFilter` | synthetic filter self-test | 20% provisional | Explicit samples work; classifier/block oracle absent. |
| 94 | Chest Content Recognition | done / done / done | research-only | none | 5% provisional | OCR and loot oracle absent. |
| 95 | Structure Orientation Recognition | done / done / done | orientation record | none | 15% provisional | Observation extraction only; no classifier. |
| 96 | World Map Reconstruction | done / done / done | research-only | none | 5% provisional | Renderer and worldgen oracles absent. |
| 97 | Multi-Screenshot Correlation | done / done / partial | ranker/candidate set | synthetic composition only | 35% provisional | No image extractor; no independent entropy. |

## 8. Experimental and hybrid methods

| # | Method | R / M / V | C | T | P | Limits / next evidence |
|---:|---|---|---|---|---:|---|
| 98 | Cross-Version Seed Analysis | done / partial / partial | `MinecraftVersion`, `VersionProfile` | catalog self-test | 75% provisional | 26.1 patch mapping and per-class deltas need continued audit. |
| 99 | World Generation Code Analysis | done / done / partial | extracted RNG/catalog primitives | Java 21 compile/self-tests | 75% provisional | Generated vectors and reproducible extraction records needed. |
| 100 | Seed RNG Dependency Mapping | done / done / partial | low-48/full-64 notes | RNG tests | 80% provisional | Continue per-feature source/call-order audit. |
| 101 | Chunk Boundary Analysis | done / done / partial | chunk/region geometry | placement self-test | 80% provisional | Extreme-coordinate vectors pending. |
| 102 | Regional RNG Analysis | done / done / partial | region-salted placement math | placement self-test | 80% provisional | Independent vectors pending. |
| 103 | Feature Dependency Analysis | done / done / partial | `WorldgenSeeds263` helpers | primitive tests only | 40% provisional | No dependency graph solver/call-order map. |
| 104 | Rare Feature Correlation | done / done / partial | filters + `CandidateRanker` | none | 35% provisional | Likelihood calibration absent. |
| 105 | Anomaly-Based Seed Identification | done / done / partial | `CandidateRanker` | synthetic baseline only | 35% provisional | Score calibration/false-positive tests absent. |
| 106 | Hybrid Seed Cracking | done / done / partial | facade/pipeline and filters | pipeline self-test | 60% provisional | Full path remains oracle-dependent and world validation absent. |

## Current audit record

- SeedcrackerX source reviewed: current checkout targets Minecraft 26.3 and
  Java 25. Its LCG lifting, pillar shuffle, and pillar time-machine primitives
  have isolated counterparts; its client finder/UI integration is intentionally
  not part of this output.
- Standalone addon source and its opt-in prediction GameTest were read as
  reference material only. No files outside this directory were edited.
- Core `src/main/java` and `src/test/java` compile with Java 25 `--release 25`
  and `-Xlint:all`; all dependency-free self-tests pass. JDK 25.0.5 was
  unpacked under a temporary directory for this check. Generated-world
  validation remains pending.
- The `test.ps1` wrapper currently fails under PowerShell native argument
  forwarding (`javac: invalid flag: :`) after recreating its two test-output
  directories. Equivalent direct Java 25 compile/test commands pass into a
  fresh temporary output directory.
- Added oracle-driven biome-boundary, climate, feature-anchor, block-pattern,
  and explicit-dimension biome
  segments. Their synthetic tests verify filter behavior only; none establishes
  Minecraft compatibility without a target-version oracle.
- Compared `WorldgenRandom` and `RandomSupport` 26.1.2 vs 26.3 decompiled
  bytecode output; both class dumps were byte-identical. The 26.2 embedded
  server jar is retained in `references/minecraft-26.2` and was used for the
  placement, outpost, ruined-portal, and structure-start audits.
- Full method research is complete through #106. The current checkpoint is
  selected-shortlist review and combined architecture validation. `TOP10_SELECTION.md`
  records a complementary shortlist, but several entries remain below the 85%
  evidence threshold until exact target-version oracles and generated fixtures
  exist.
- No method with only an internal consistency test is counted as Minecraft
  version-verified. Update each row when primary source, mappings, independent
  vectors, or generated-world results are recorded.

## Top-ten implementation checkpoint

`TOP10_SELECTION.md` records the selected research shortlist after the full
106-method comparison. Existing standalone classes cover the low-48, RNG,
fingerprint, and filter boundaries; methods requiring Minecraft runtime
behavior remain oracle-backed rather than fabricated.
`COMBINED_ARCHITECTURE.md` defines the shared candidate database, stage ordering,
confidence policy, and hold-out verification requirement.
