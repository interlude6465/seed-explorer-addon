# Seed-Cracking Methods — 26.1+ Research Notes

This document records what was verified, what is implemented, and what still
needs a Minecraft-dependent oracle. It is deliberately conservative: code
derived from the audited 26.1-26.3 jars is not the same as proof against a
generated world, and older-version results are not proof of newer behavior.

## 0. Scope and evidence standard

- Target: Minecraft Java 26.1.x, 26.2.x, and 26.3.x, single-player, Java 25.
- Implementation: dependency-free Java under `seed-cracking/`.
- Minecraft-dependent work (biomes, terrain, loot, structure starts) is exposed
  through interfaces; no addon classes are imported or modified.
- Placement, pillars, slime, bedrock, stronghold rings, and `WorldgenRandom`
  helpers were checked against the 26.1.2, 26.2, and 26.3 jars and vanilla data.
- No generated-world validation has been run yet. Oracle results from one
  version are not accepted as proof of another version's behavior.

### 0.1 Version differences found in the audited range

- 26.1.2 and 26.2 have 20 structure-set JSON files; 26.3 has 21. The 20 shared
  files are identical. 26.3 adds `minecraft:abandoned_camp` (spacing 37,
  separation 8, salt 91231127).
- Random-spread placement math and frequency reducers are unchanged. 26.3 only
  refactors `StructurePlacement` and introduces
  `AbstractSpreadingStructurePlacement`.
- `EndSpikeFeature` is refactored into a record in 26.3, but the spike-cache
  seed and height formula are unchanged.
- Stronghold ring-position generation is bytecode-identical. 26.3 adds an
  unused-by-rings `getDimensionOrigin`/`origin` field.
- The slime predicate is unchanged. The `Slime` class moved package in 26.2.
- Bedrock floor/roof conditions and anchors are unchanged. 26.3 moved the rules
  from `noise_settings` into `material_rule` JSON files with the same content.
- `LegacyRandomSource`, `WorldgenRandom`, `Xoroshiro128PlusPlus`,
  `XoroshiroRandomSource`, `RandomSupport`, and `Mth.getSeed` are unchanged.

## 1. Exact 26.1+ primitives

### 1.1 Java 48-bit LCG

`LegacyRandomSource` stores the low 48 bits of `seed ^ 0x5DEECE66D` and advances
with:

```text
state = (state * 0x5DEECE66D + 0xB) mod 2^48
next(bits) = state >>> (48 - bits)
nextInt() = next(32)
nextLong() = (next(32) << 32) + next(32)
```

Every feature driven by this generator depends only on the low 48 bits of the
world seed. That is the **structure seed** used throughout the module.
`Lcg48.previousState` reverses one transition using the modular inverse
`0xDFE05BCB1365`. `Lcg48.advanceState` and `retreatState` apply any non-negative
number of transitions in logarithmic time using affine exponentiation modulo
`2^48`.

`Lcg48OutputRecovery` provides another exact route when raw `next(bits)` values
of at least 24 bits, `nextFloat()`, `nextDouble()`, `nextInt()`, or `nextLong()`
outputs are observable. A 24-bit output leaves at most `2^24` possible
post-call states; 32-bit outputs leave `2^16`. `RandomOutputObservation`
allows those widths and known, per-observation skipped-call counts to be mixed
in one sequence. The implementation reverses each state to a low-48 seed and
replays the complete sequence. This does not recover high 16 world-seed bits,
and it does not yet handle bounded `nextInt(bound)` outputs or unknown gaps.

### 1.2 Random-spread structure placement

For a region `(regionX, regionZ)`, 26.1+ computes:

```text
mixed = structureSeed
      + regionX * 341873128712
      + regionZ * 132897987541
      + salt
random.setSeed(mixed)
offsetX = spread(random, spacing - separation)
offsetZ = spread(random, spacing - separation)
chunkX = regionX * spacing + offsetX
chunkZ = regionZ * spacing + offsetZ
```

`spread` is either one `nextInt(range)` per axis (linear) or the average of two
calls per axis (triangular). `PlacementSpec` and `VanillaPlacements` encode the
version-specific structure-set values. The 19 random-spread sets shared by
26.1-26.3 are identical; 26.3 adds abandoned camps.

Important separation of concerns:

- placement answers "is this a potential chunk?";
- frequency reduction, exclusion zones, biome checks, and structure-start
  generation answer "does a structure actually exist here?".

The latter are not in the pure placement model. An observed structure proves the
gates passed, but a candidate potential chunk does not prove a structure exists.

### 1.3 Low-48 lifting

For a linear, non-power-of-two range `R` divisible by four:

- `nextInt(R)` is `next(31) % R`.
- The low `m = trailingZeros(R)` bits of `next(31)` depend only on the low
  `17 + m` bits of the LCG state.
- Therefore `nextInt(R) % 2^m` is determined by the low `17 + m` seed bits.

SeedCrackerX uses the special case `R % 4 == 0`, checks `offset % 4`, and
enumerates `1 << 19` low partial seeds. This module generalises the same idea to
the exact range:

| Structure set | spacing/separation | range | low seed bits |
| --- | --- | --- | --- |
| shipwrecks | 24/4 | 20 | 19 |
| ocean ruins | 20/8 | 12 | 19 |
| desert pyramids | 32/8 | 24 | 20 |
| igloos | 32/8 | 24 | 20 |
| jungle temples | 32/8 | 24 | 20 |
| swamp huts | 32/8 | 24 | 20 |
| pillager outposts | 32/8 | 24 | 20 |

Power-of-two ranges (for example ancient cities, range 16) take the high bits of
the LCG output and are not liftable this way. Triangular placements (mansions,
monuments, End cities) consume two calls per axis and are also exact-only.
Ranges not divisible by four (villages, trail ruins, trial chambers, ruined
portals, abandoned camps) are exact-only.

The lifting search:

1. enumerates compatible low partial seeds;
2. enumerates the remaining high bits;
3. applies cheap filters (End pillars, slime chunks);
4. replays exact placement for every observation.

Five or six independent lifting structures usually leave one low partial seed.
The final exact replay is necessary because the low-bit sieve only checks the
low offset bits.

### 1.4 Upper-16-bit recovery

After a structure seed is known:

```text
worldSeed = (upperBits << 48) | structureSeed,  upperBits in [0, 65535]
```

The 65,536 possibilities must be separated by a feature that reads the full
64-bit seed. Feature dependence matters:

| Feature | Seed bits used | Upper-bit verifier? |
| --- | --- | --- |
| Structure placement | low 48 | No |
| End pillar key/heights | low 48 | No |
| Slime chunks | low 48 | No |
| Stronghold ring geometry | low 48 | No |
| Stronghold final biome offset | full seed through biome source | Yes |
| Biomes | full seed | Yes |
| Terrain / noise router / surface rules | full seed | Yes |
| Overworld bedrock | full seed through Xoroshiro | Yes |
| Structure-start generation / variant / loot | oracle-dependent | Usually yes |
| Chest loot | full seed chain | Yes, if the oracle is exact |

A "filter" that only reads the low 48 bits will accept all 65,536 upper halves
and is not a verifier. `UpperBitRecovery` does not try to detect this; the
caller must supply a genuinely full-seed filter.

Budget: 65,536 candidates per structure seed. The default `maxCandidates` is
`1 << 24` (256 structure seeds), and an oversized list is rejected before work
starts rather than silently truncated.

### 1.5 End pillars

26.1-26.3 `EndSpikeFeature.getSpikesForLevel`:

```text
LegacyRandomSource(worldSeed)
spikeKey = nextLong() & 0xFFFF
LegacyRandomSource(spikeKey)
shuffle indices 0..9 with nextInt(i) for i=10..2
height[i] = 76 + shuffledIndex[i] * 3
```

The ten heights are a permutation of `76, 79, ..., 103`. Enumerating all 65,536
spike keys and comparing the shuffle rejects about 99.9% of random structure
seeds. It is a 16-bit filter, not a full seed recovery method.

SeedCrackerX's "time machine" uses the inverse observation: the spike key
occupies bits 16..31 of the raw LCG state after two forward steps. Building a
partly known state, reversing two steps, and XORing the Java multiplier produces
a structure seed with the requested pillar key. `PillarTimeMachine` implements
that exact calculation.

### 1.6 Slime chunks

26.1-26.3 slime chunk seed:

```text
mixed = structureSeed
      + (chunkX^2 * 4987142)          // 32-bit overflow
      + (chunkX * 5947611)            // 32-bit overflow
      + chunkZ^2 * 4392871
      + (chunkZ * 389711)             // 32-bit overflow
mixed ^= 987234911
slime if new LegacyRandomSource(mixed).nextInt(10) == 0
```

Only the low 48 bits matter. A slime chunk is worth about 3.32 bits; a non-slime
chunk is worth about 0.15 bits. Positive observations are much more selective
than negative ones and are checked first by `StructureSeedLifter`.

### 1.7 Bedrock

26.1-26.3 use the same two `VerticalGradientCondition`s:

- floor: true at/below `minY`, false at/above `minY + 5`;
- roof: false at/below `maxY - 5`, true at/above `maxY`, wrapped in `not(...)`.

Between the anchors, the condition compares a named positional Xoroshiro
`nextFloat()` with a linear gradient from 1 to 0. The named factory chain is:

```text
new XoroshiroRandomSource(worldSeed)
  .forkPositional()
  .fromHashOf("minecraft:bedrock_floor" or "minecraft:bedrock_roof")
  .forkPositional()
```

Default Overworld material rules use **only the floor rule**, with
`minY = -64`, `maxY = 319`, span 5. The caves preset (`minY = -64`,
`maxY = 127`) and the Nether (`minY = 0`, `maxY = 127`) use both floor and
roof. The End has no bedrock rule. Bedrock is therefore a strong full-seed
verifier when the correct preset is selected. Do **not** use guaranteed blocks
such as Y = -64 or a preset's `maxY` as observations: they match every seed.
Use gradient rows.

A subtlety observed in the vanilla oracle: at `(0, y, 0)`, the top 24 bits of the
Xoroshiro output can stay constant across the gradient because the positional
hash changes only low bits. A column's float can therefore be constant while
still being correct. Use well-spread X/Z coordinates for observations.

### 1.8 Strongholds

26.1-26.3 `ConcentricRingsStructurePlacement`:

- distance 32, spread 3, count 128, salt 0;
- `LegacyRandomSource(levelSeed)`;
- initial angle `nextDouble() * 2π`;
- radius `4 * 32 + 32 * ring * 6 + (nextDouble() - 0.5) * 32 * 2.5`;
- x/z are rounded cosine/sine positions;
- angle advances `2π / spread`;
- after a ring, spread grows by `(2 * spread) / (ring + 1)`, capped by the
  remaining count, and a new random angle offset is added;
- every candidate consumes one `fork()` before the biome search.

The ring centres depend only on the low 48 bits. The final step:

```text
findBiomeHorizontal(centerX, 0, centerZ, 112, preferredBiomes, fork, randomState)
```

can move the stronghold to a biome-dependent chunk, so the final location can
depend on the full seed. `ConcentricRings263` exposes the exact forks and a
`BiomeGate`; without a real biome oracle the output is a ring centre, not a
guaranteed stronghold.
`ConcentricRingsStrongholdOracle` adapts that gate to `StrongholdFilter` and
caches the 128 positions per candidate seed while multiple ring observations
are checked.

### 1.9 `WorldgenRandom` seed helpers

Verified 26.1-26.3 helpers:

```text
setFeatureSeed(seed, index, step)
    seed + index + 10000 * step       // 10000 * step is 32-bit int math

setLargeFeatureSeed(seed, chunkX, chunkZ)
    r = LegacyRandomSource(seed)
    a = r.nextLong(); b = r.nextLong()
    seed ^ (chunkX * a) ^ (chunkZ * b)

setDecorationSeed(seed, chunkX, chunkZ)
    r = LegacyRandomSource(seed)
    a = r.nextLong() | 1; b = r.nextLong() | 1
    seed ^ (chunkX * a + chunkZ * b)

setLargeFeatureWithSalt(seed, regionX, regionZ, salt)
    seed + regionX * 341873128712 + regionZ * 132897987541 + salt
```

These are exposed by `WorldgenSeeds263`.

## 2. How closely this matches SeedCrackerX

### 2.1 Phase pipeline

SeedCrackerX runs:

```text
PILLARS -> STRUCTURES -> LIFTING -> STRUCTURE_REDUCE -> BIOMES
```

- `PILLARS` brute-forces the 16-bit spike key from observed heights.
- `STRUCTURES` uses the time machine with pillar seeds and candidate structure
  starts.
- `LIFTING` starts when the lifting data reaches 40 bits; it enumerates
  `1 << 19` low partials with `% 4`, then all remaining high bits.
- `STRUCTURE_REDUCE` removes candidates that fail exact structure data.
- `BIOMES` starts the normal search at 32 bits and tries upper bits with biome
  data, then a hashed-seed path, then random-world-seed fallback.

This module provides the same primitives and an explicit phase trace through
`SeedCrackingPipeline`; the Minecraft observation collector remains outside
this folder. The search building blocks are `StructureSeedLifter`,
`UpperBitRecovery`, `PillarTimeMachine`, `ConcentricRings263`,
`ConcentricRingsStrongholdOracle`, and `SeedCracking`.

### 2.2 Bit accounting

SeedCrackerX `DataStorage.getBits`:

```text
linear:       log2(offset^2)
triangular:   log2(peak^2)
buried:       log2(100)
```

`StructureEntropy` matches those formulas. It also exposes exact Shannon entropy
for triangular offsets and separates offset bits from frequency bits. Outposts
are excluded from `baseBits` to match SeedCrackerX's `getBaseBits`.

### 2.3 Deliberate improvements

- The sieve width comes from `trailingZeros(range)` rather than a hard-coded
  19. This is sound and finds fewer false partials for range-24 sets.
- Exact version-selected placement constants are used rather than a
  version-specific library table.
- `UpperBitRecovery` is parallel, budgeted, and filter-order aware.
- The package has no global singleton and no Minecraft imports, so it cannot
  interfere with the addon.

## 3. Structure methods (items 1–20)

Feasibility notes:

- **1–3, 6–14, 17–20:** placement math is exact and already implemented.
  Actual structure existence needs frequency/exclusion/biome/structure-start
  gates. Village, trail ruin, trial chamber, ruined portal, ancient city, and
  Nether sets are exact-only (no low-bit sieve).
- **4 (rotation), 5 (variant), 12 (shipwreck variant):** orientation and variant
  are post-placement structure-start data. `StructureGeometry.Orientation` and
  `ShipwreckVariant` are observation baselines; exact prediction requires a
  structure-start simulator.
- **14 (strongholds):** exact ring geometry; biome gate needed for final chunks.
- **15–16 (bastion/fortress):** share `nether_complexes` placement; the variant
  choice is a separate structure-start decision.
- **20 (multi-structure):** `StructureSeedLifter` + `SeedCracking` +
  `IncrementalCandidateSet`.

Key repositories: `19MisterX98/SeedcrackerX`, `Cubitect/cubiomes`,
`Earthcomputer/StrongholdFinder`, `orlp/StrongholdCoords`,
`doublegate/MC-SeedFinder`.

## 4. Biome and terrain methods (items 21–35)

These methods need a full-seed worldgen oracle. Pure Java placement cannot
answer them.

Practical tiers:

1. **Biome point samples** (`BiomeFilter`): strongest effort/benefit ratio.
   Sample well-separated climate regions, not adjacent blocks.
2. **Heightmap samples** (`TerrainFilter`): strong, but only if the oracle
   matches the target version's noise, density, and surface rules exactly.
3. **Boundaries, coastlines, rivers, lakes, mountains:** derived from the same
   biome/terrain oracle; useful for ranking and for reducing samples.
4. **Caves, aquifers, ravines, overhangs, surface material:** require much more
   of the target generation stack; high effort and high risk of subtle mismatch.

`cubiomes` is the most practical C reference for biomes and many structures;
`cubiomes-viewer` provides map rendering and candidate comparison. A Java-side
oracle can wrap cubiomes or a validated version-matched implementation, but it
must be audited against generated worlds of the same version.

## 5. RNG and feature methods (items 36–50)

- **36–38 (population/decorator/feature placement):** feature RNG is usually
  low-48 or chunk-seeded; useful for structure-seed verification and for
  candidate ranking, but not automatically an upper-bit verifier. Use
  `WorldgenSeeds263` and `KaptainWutax/FeatureUtils` as references.
- **39–40 (ore/emerald):** feature placement is low-48 seeded; emerald ore has
  biome/height constraints. SeedCrackerX historically used emerald ore and
  dungeon observations. Exact target-version ore-vein generation needs a feature
  simulator.
- **41–46 (vegetation, flowers, trees, mushrooms, dripstone, lush caves):**
  feature placement plus biome/height. Tree crackers exist, but tree feature
  code is version-specific. Treat as observation/ranking sources until audited.
- **47 (geodes):** placement and geode layout are feature-level; no pure
  implementation here.
- **48–49 (dungeons/spawners):** SeedCrackerX's dungeon finder is the closest
  reference; exact target-version dungeon generation needs a structure/feature
  oracle.
- **50 (random sequence recovery):** `Lcg48.previousState` and
  `WorldgenSeeds263` provide the reversal primitives; full sequence recovery
  from partial outputs is a research task (lattice/SMT).

Key repositories: `SeedFinding/fnseedc`, `KaptainWutax/FeatureUtils`,
`Nel-S/tree-cracker`, `MCRcortex/TreeCracker`,
`polymetric/treecrackerPOS`, `jellejurre/GPULootSeedFinder`.

## 6. Special world-generation methods (items 51–60)

- **51–52 (End pillars):** exact fingerprint and exact time machine baseline.
- **53 (Nether bedrock):** 26.1-26.3 Nether bedrock is seed-dependent and uses
  floor plus roof. `BedrockPreset.NETHER` and `VanillaBedrockOracle` model it;
  use `19MisterX98/Nether_Bedrock_Cracker` as an independent reference and add
  generated-world observations before treating it as validated.
- **54 (slime):** exact low-48 predicate.
- **55 (mushroom island):** biome layout; cubiomes.
- **56–58 (Nether biomes/terrain/lava lakes):** cubiomes and target-version
  noise research; oracle required.
- **59 (bastion chest configuration):** structure-start + loot oracle.
- **60 (End gateways):** gateway positions are derived from dragon/End state and
  are not a simple world-seed placement; SeedCrackerX uses gateway observations
  as data, but exact target-version simulation needs more research.

## 7. Loot methods (items 61–70)

Loot is generated from a per-container seed chain derived from the world seed,
structure start, and container position. This makes it a strong full-seed
verifier, but only if the chain and loot table are exact for the target version.

Practical guidance:

- Use **complete, untouched** containers for exact filtering.
- Partial observations can still reject candidates that lack the observed items,
  but cannot prove a seed by themselves.
- Loot table RNG consumes a variable number of values depending on item rolls;
  a mismatch is easy to introduce by using an older table or simulator.
- GPU brute force is useful for loot-seed searches, not for replacing the
  structure/upper-bit stages.

References: `jellejurre/GPULootSeedFinder`, `cev-api/LootProbe`,
`xVoid879/1.20-pre5-Loot-Drops`, `DuncanRuns/MiLTSU`,
`Crafterdark/Desert-Temple-Loot-Simulation`.

**Fishing (item 70):** fishing loot is driven by the bobber's own RNG and luck
state, not directly by the world seed. It is generally not a useful world-seed
constraint unless a specific mechanic ties the roll back to a world-seeded
value. Treat it as research-only.

## 8. Mathematical and computational methods (items 71–88)

- **71 (48-bit recovery):** implemented by `StructureSeedLifter`.
- **72 (16-bit extension):** implemented by `UpperBitRecovery`.
- **73–75 (brute force/GPU/CPU):** structure lifting and upper-bit recovery
  are CPU-parallel, and `PillarTimeMachine.searchRangeParallel` covers sharded
  pillar searches with budgets. GPU references remain
  `jellejurre/GPULootSeedFinder` and `hube12/MinecraftSeedCrackingC`.
- **76–78 (RNG reversal/Java Random/LCG):** `Lcg48.previousState`,
  `WorldgenSeeds263`, and the time machine cover the common forward/inverse
  cases. Full state recovery from arbitrary outputs needs lattice/SMT tools.
- **79 (lattice reduction):** `mjtb49/LattiCG` is the primary reference. Not
  implemented in pure Java here.
- **80–81 (bit lifting/modular arithmetic):** implemented for structure
  placement; the derivation is documented in section 1.3.
- **82–84 (constraint satisfaction/SAT/SMT):** research references:
  `EricSpencer00/reverse-xoroshiro128plusplus`, `deut-erium/RNGeesus`,
  `jonathan-rogers-dev/mceed`.
- **85 (meet-in-the-middle):** the time machine is effectively a partial
  meet-in-the-middle over the 32-bit partial seed and 16-bit pillar key.
- **86 (ranking):** `CandidateRanker`.
- **87 (multi-source):** `SeedCracking` plus multiple filters.
- **88 (incremental):** `IncrementalCandidateSet`.

## 9. Image recognition and automated observation (items 89–97)

Nothing in this module performs CV or OCR. The intended boundary is:

- structure detection -> `StructureObservation`;
- orientation -> `StructureGeometry.Orientation`;
- shipwreck variant -> `ShipwreckVariant`;
- biome/terrain screenshots -> biome/terrain observations for oracles;
- map coordinates -> `StructureObservation` or `TerrainObservation`;
- chest contents -> `LootObservation`;
- multi-screenshot correlation -> `IncrementalCandidateSet` or
  `CandidateRanker`.

Map/terrain references: `Cubitect/cubiomes-viewer`,
`cev-api/VoxelMap-x-SeedMapper`, `acheong08/MC-SeedLocator`. Chest OCR has no
dedicated seed-cracking repository in the collected references; use a generic
OCR pipeline and validate the extracted stacks manually.

## 10. Experimental and research methods (items 98–106)

- **98 (cross-version):** cubiomes and SeedCrackerX support multiple versions;
  this module supports 26.1.x-26.3.x explicitly. Recheck version-specific
  constants and data before adding a new minor version.
- **99 (worldgen code analysis):** the 26.1.2, 26.2, and 26.3 jars plus
  `misode/mcmeta` data are the primary sources. This module's constants were
  extracted that way.
- **100 (RNG dependency mapping):** documented in section 1.4. The key split is
  low-48 versus full-64.
- **101 (chunk boundaries):** placement regions and biome noise cells are
  chunk-aligned; boundary mismatches are a common observation error.
- **102 (regional RNG):** structure placement is region-salted; nearby regions
  are independent, not a shared sequence.
- **103 (feature dependency):** features can share a chunk seed but consume it
  in a fixed order; reusing one feature's RNG for another is a common bug.
- **104 (rare feature correlation):** use multiple independent filters and
  `CandidateRanker`; rare biomes/structures are strong priors but not proof.
- **105 (anomaly detection):** rank candidates by partial matches rather than
  discarding on one bad observation.
- **106 (hybrid pipeline):** `SeedCracking` is the baseline; add oracle-backed
  filters one stage at a time.

## 11. Recommended implementation order

1. **Observation adapter (outside this isolated module):** capture structure
   starts, biome samples, terrain columns, bedrock gradient blocks, and
   containers for the target version without changing the addon's existing
   screens or commands.
2. **Generated-world audit (deferred by request):** verify placement, pillars,
   slime, bedrock, and stronghold rings against the 26.1.2, 26.2, and 26.3 jars
   and generated worlds.
3. **Biome oracle:** cubiomes or a version-matched adapter; start with
   `BiomeFilter`.
4. **Terrain oracle:** heightmap columns; add `TerrainFilter`.
5. **Stronghold biome gate:** complete `ConcentricRings263.BiomeGate`.
6. **Structure-start oracle:** enables true structure existence, variants, and
   rotation analysis.
7. **Nether bedrock:** `BedrockPreset.NETHER` is modeled; add independent
   observations and validate it against generated Nether worlds.
8. **Loot oracle:** only after an independent version-matched loot-chain audit.
9. **CV/OCR and GPU search:** later, larger projects.

## 12. Primary references

- `19MisterX98/SeedcrackerX` — phase pipeline, lifting, time machine, finders.
- `SeedFinding/fnseedc` — curated index of seed-cracking tools and resources.
- `Cubitect/cubiomes`, `Cubitect/cubiomes-viewer` — biomes, structures, maps.
- `doublegate/MC-SeedFinder` — integrated structure/biome search.
- `Earthcomputer/StrongholdFinder`, `orlp/StrongholdCoords` — strongholds.
- `KaptainWutax/FeatureUtils` — feature RNG reference.
- `mjtb49/LattiCG`, `mjtb49/ChunkRandomReversal`,
  `YanWQ-monad/MinecraftSeedReverse` — RNG reversal and lattices.
- `hube12/MinecraftSeedCrackingC` — C/CPU/GPU-oriented seed cracking.
- `jellejurre/GPULootSeedFinder`, `cev-api/LootProbe`,
  `DuncanRuns/MiLTSU` — loot simulation/search.
- `Nel-S/tree-cracker`, `MCRcortex/TreeCracker`,
  `polymetric/treecrackerPOS` — tree features.
- `19MisterX98/Nether_Bedrock_Cracker` — Nether bedrock.
- `EricSpencer00/reverse-xoroshiro128plusplus`, `deut-erium/RNGeesus` —
  SMT/RNG research.
