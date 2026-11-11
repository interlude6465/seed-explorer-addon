# Seed Cracking (26.1+ engine)

## Standalone mod integration (11 November 2026)

The user has now authorized integration. The standalone Fabric build includes
`src/main/java` from this module and exposes **Settings > Seed cracking**.
The core remains dependency-free and can still be compiled/tested alone.
The earlier isolation statements below describe the research phase and are
superseded by this explicit integration request.
Vanilla server jars used for bytecode research are local references and are
excluded from Git; the extracted JSON and audit notes remain in the project.

The workspace automatically recognizes loaded pyramids and shipwrecks, records
bedrock/noise-biome/End-pillar evidence, and starts bounded low-48 lifting and
upper-bit recovery when enough evidence is available. Blue outlines identify
recognized structures. No coordinate entry is required for this background flow;
manual structure-start and slime observations remain advanced options. It supports
cancellation, evidence persistence per world and prediction version, and explicit
application of a unique full-seed candidate. It never reads the
configured map seed or integrated-server seed to obtain recovery results.
The runtime biome adapter is limited to 26.3 and captures **noise biomes** from
loaded chunk palettes, matching the offline adapter's quart-coordinate domain.
Loaded block capture assumes unmodified vanilla terrain and default presets.

Candidates remain experimental. An upper-bit search can exhaust the supplied
low-48 inputs; it does not establish uniqueness across other low-48 seeds. The
research low-bit lifting sieve omits rare Java bounded-RNG rejection paths and is
always labeled non-exhaustive in the integrated UI. A Java Random boundary fixture
in the self-test documents that limitation. Partial searches
and low-48 results cannot be applied as full map seeds. Results should be checked
against hold-out observations before use. Unsupported oracle contracts (feature,
loot, climate, screenshots and similar methods) are not exposed as working methods.
See [`../docs/SEED-CRACKING-INTEGRATION.md`](../docs/SEED-CRACKING-INTEGRATION.md).

Independent vanilla checks cover placement, bedrock, noise biomes, pillars and
slime RNG. The complete live automatic workflow also passed on seed 1: six
recognized structures led to the full candidate `1`, without changing the
deliberately incorrect configured map seed. The linked integration report records
the scope, counts and remaining limitations. Emerald-ore cracking remains research;
it is not an integrated recovery method.

## Archived research-phase overview

The sections below preserve the original isolated-module research checkpoint.
Statements about integration being prohibited or generated-world validation not
having run describe that earlier phase. Use the integration report above for the
current mod behavior and validation status.

This folder is a standalone, dependency-free Java 25 module for Minecraft Java
seed cracking from 26.1 onward. The audited baseline covers **26.1.x, 26.2.x,
and 26.3.x**; 26.3 is the newest audited version and the default
(`MinecraftVersion.latest()`). It is intentionally **not** wired into the
addon, screens, Gradle build, commands, or the other AI team's work. Nothing
outside `seed-cracking/` is required to compile or use it.

The module currently provides the baseline calculations and functions for:

- version-aware random-spread structure placement for 26.1+;
- SeedCrackerX-style low-48 structure-seed lifting;
- upper-16-bit recovery through pluggable full-seed filters;
- SeedCrackerX-style pillar time-machine candidates;
- exact low-48 candidate recovery from consecutive Java `nextInt()` outputs;
- End pillar, slime chunk, bedrock, and stronghold-ring primitives;
- entropy estimation, candidate ranking, incremental filtering, and
  structure geometry helpers;
- structure-orientation observations and a shipwreck rotation/template
  selector for caller-supplied structure-start RNG states;
- documented task/status tracking for 106 seed-cracking methods.

No generated-world validation has been run yet. Treat predictions as
code-and-bytecode-derived until an independent generated-world audit confirms
them. Older-version results are not proof of newer-version behavior.

## Progress handoff (updated 2026-11-11)

The persistent restart log is [`SUITE-PROGRESS.md`](SUITE-PROGRESS.md). It
records the current audit position, validation gaps, top-ten status, and the
rule that nothing is integrated into the main mod.

The full method-by-method screen is complete and the practical top-ten
pipeline is now documented. All 106 task IDs have individual research/audit
rows. The isolated module includes
version-audited placement/lifting, upper-16 enumeration, LCG output recovery,
pillar/slime/bedrock/stronghold primitives, oracle-backed biome/terrain/feature/
loot filters, the exact outpost `legacy_type_1` reducer, and a versioned ruined
portal selector. The latest direct Java 25 compile and dependency-free
self-test pass. The PowerShell wrapper may still fail on native argument
forwarding on some machines; direct commands are the authoritative check.

The complete inventory audit and complementary shortlist are recorded in
`METHOD_AUDIT.md`, `METHOD_PROGRESS.md`, and `TOP10_SELECTION.md`; the combined
candidate flow is in `COMBINED_ARCHITECTURE.md`. Strongest current direction:
low-48 structure placement/lifting plus End pillar and conditional LCG evidence,
then independent full-seed biome, terrain, slime, and bedrock checks. Several
shortlist entries remain contracts because exact Minecraft biome, terrain, and
cross-dimension adapters need generated-world fixtures. They are optional
complements and are not presented as 85%-verified. Generated
26.1/26.2/26.3 reference worlds and full-seed hold-out recovery are still
required; Java 25.0.5 has now been used for the isolated compile/self-test.

Latest completed audit: #106 Hybrid Seed Cracking. All 106 IDs have research
and progress rows. The next phase is reviewing/developing the selected
standalone segments and validating version-matched oracles; do not claim 85%
confidence or completion for unvalidated adapters. See
[`SUITE-PROGRESS.md`](SUITE-PROGRESS.md) for the evidence and restart point.

All edits remain inside `seed-explorer-addon/seed-cracking`. No source,
resource, screen, command, build file, or integration point in the main Meteor
Client/addon has been modified.

## Version coverage

| Version | Random-spread catalog | Bedrock default | Pillars / slime / strongholds |
| --- | --- | --- | --- |
| 26.1.x | 19 shared sets; no abandoned camps | Overworld floor-only; caves/Nether floor+roof | Same shared 26.1-26.3 math |
| 26.2.x | 19 shared sets; no abandoned camps | Overworld floor-only; caves/Nether floor+roof | Same shared 26.1-26.3 math |
| 26.3.x | 19 shared sets + `minecraft:abandoned_camp` | Overworld floor-only; caves/Nether floor+roof | Same shared 26.1-26.3 math |

The 20 structure-set JSON files shared by 26.1.2, 26.2, and 26.3 are identical;
strongholds use concentric rings and are not part of the random-spread catalog.
The only catalog change found in the audited range is 26.3's abandoned-camp
set (spacing 37, separation 8, salt 91231127). The 26.1 profile uses the
26.1.2 reference jar; 26.1.1 and 26.1.0 are mapped to the same minor profile.

`MinecraftVersion.tryParse` accepts patch names such as `26.1.2` and maps them
to the audited minor version. It rejects unknown future minors such as 26.4;
add and audit a new enum constant before using the module on that version.

## Build

Requirements: JDK 25. No Minecraft, Fabric, Gradle, or third-party jars.

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

`build.ps1` compiles every `.java` file under `src/main/java` into
`build/classes`. It uses `--release 25` and no external classpath. The module is
deliberately not referenced by the addon's Gradle project.

Run the dependency-free regression checks with:

```powershell
powershell -ExecutionPolicy Bypass -File .\test.ps1
```

The checks cover LCG jump/reversal, mixed-width fixed-gap output recovery,
placement replay, pillar fingerprinting/search, stronghold adapter behavior,
and the seed-cracking pipeline.

## Quick start

### 1. Lift the low 48 bits from structure starts

Use the structure start chunk, not the block returned by `/locate`. Select the
catalog and specs for the world's version:

```java
import me.seedexplorer.seedcracking.*;

VersionProfile profile = VersionProfile.parse("26.1.2");
PlacementCatalog catalog = profile.placements();

var observations = java.util.List.of(
    catalog.byId("minecraft:shipwrecks").observe("minecraft:shipwreck", -151, 271),
    catalog.byId("minecraft:desert_pyramids").observe("minecraft:desert_pyramid", -204, 358),
    catalog.byId("minecraft:pillager_outposts").observe("minecraft:pillager_outpost", -205, 363)
    // add 3-5 more independent lifting structures for a practical search
);

LiftResult lift = StructureSeedLifter.lift(observations,
    LiftOptions.builder()
        .threads(8)
        .timeBudgetMillis(120_000)
        .build());
```

The low-bit sieve works on linear, non-power-of-two ranges divisible by four.
In 26.1-26.3 that includes shipwrecks, ocean ruins, desert pyramids, igloos,
jungle temples, swamp huts, and pillager outposts. Shipwrecks and ocean ruins
constrain 19 low bits; the range-24 sets constrain 20.

`VanillaPlacements.SHIPWRECKS` and the other shared constants are also
available directly. Use `VanillaPlacements.forVersion(MinecraftVersion.V26_3)`
or `Vanilla263Placements.all()` only when you specifically want the 26.3
catalog (which includes abandoned camps).

### 2. Recover the upper 16 bits

Structure placement, End pillars, slime chunks, and other
`LegacyRandomSource` features only read the low 48 bits. Use a feature that
reads the full 64-bit world seed.

```java
// Default Overworld: floor-only. Non-gradient blocks such as Y=-64 are
// guaranteed and therefore carry no seed information.
WorldSeedFilter bedrock = new BedrockFilter(
    profile.bedrockOracle(Dimension.OVERWORLD),
    java.util.List.of(
        new BedrockObservation(0, -62, 0, true),
        new BedrockObservation(0, -61, 0, false)
    ));

RecoveryResult recovered = UpperBitRecovery.recover(
    lift.structureSeeds(), java.util.List.of(bedrock), RecoveryOptions.defaults());
```

The default Overworld material rule uses **only** `minecraft:bedrock_floor`.
The roof rule is real in the caves preset and the Nether; select it explicitly:

```java
WorldSeedFilter cavesBedrock = new BedrockFilter(
    new VanillaBedrockOracle(MinecraftVersion.V26_1, BedrockPreset.OVERWORLD_CAVES),
    caveObservations);
```

`BiomeFilter`, `TerrainFilter`, `StrongholdFilter`, `BedrockFilter`,
`LootFilter`, `StructureStartFilter`, and `StructureVariantFilter` all implement `WorldSeedFilter`. They
delegate the Minecraft-dependent part to a supplied oracle; nothing in this
module imports Minecraft.

For observations spanning dimensions, `DimensionBiomeFilter` passes an
explicit `Dimension` to its `DimensionBiomeOracle`. This is an adapter
contract only; it does not provide or validate the Minecraft biome source.

### 3. Use the end-to-end facade

```java
CrackResult result = SeedCracking.crack(observations, java.util.List.of(bedrock));
if (result.found()) {
    System.out.println(result.worldSeeds());
}
```

If no filter is supplied, the result contains low-48 structure seeds with
`CrackResult.Status.STRUCTURE_SEEDS_ONLY`.

### 4. Pillar time machine

```java
int pillarSeed = EndPillarFingerprint.spikeSeedForStructureSeed(0x123456789ABCL);
long candidate = PillarTimeMachine.candidateStructureSeed(0xDEADBEEFL, pillarSeed);
```

`candidateStructureSeed` is the exact SeedCrackerX time-machine calculation: it
builds the partly known LCG state, reverses two steps, and XORs the Java
multiplier. A complete search is 2^32 partial seeds; shard it with
`PillarTimeMachine.shardStart/shardEnd` and `searchRange`.

### 5. Stronghold rings

```java
var rings = ConcentricRings263.ringOrigins(0x123456789ABCL);
```

The geometry is exact for 26.1-26.3, including the per-candidate `fork()`
consumption. The actual stronghold chunk depends on the biome gate; supply a
`BiomeGate` backed by a version-matched `BiomeSource.findBiomeHorizontal`
adapter, then pass `new ConcentricRingsStrongholdOracle(gate)` to
`StrongholdFilter` for final coordinates.

## What is exact

| Area | Status |
| --- | --- |
| Java 48-bit LCG (`nextInt`, `nextLong`, `nextFloat`, `nextDouble`, reversal) | Exact 26.1-26.3 |
| Random-spread placement constants and offset math | Exact for the audited catalogs; 26.3 adds abandoned camps |
| Structure-seed low-bit sieve | Exact for supported linear ranges |
| End spike keys and heights | Exact 26.1-26.3 model |
| Slime chunk predicate | Exact 26.1-26.3 model |
| Bedrock floor/roof mechanics | Exact 26.1-26.3 model; preset-aware |
| Default Overworld bedrock | Floor-only; roof is not part of the default rule |
| Stronghold ring geometry | Exact 26.1-26.3 model; biome gate is pluggable |
| `WorldgenRandom` seed helpers | Exact 26.1-26.3 model |
| Upper-16-bit search and filtering | Exact enumeration; accuracy depends on oracle |
| Biome/terrain/loot/structure-start predictions | Oracle boundary, not implemented in pure Java |

## Package map

| File | Purpose |
| --- | --- |
| `MinecraftVersion`, `VersionProfile`, `PlacementCatalog` | Version selection and immutable per-version data |
| `VanillaPlacements` | Canonical 26.1+ random-spread constants and catalog |
| `Vanilla263Placements` | 26.3 compatibility facade |
| `Lcg48` | Java 48-bit LCG, including `previousState` |
| `Lcg48OutputRecovery`, `RandomOutputObservation` | Low-48 candidates from mixed raw 24-32-bit, float, double, int, and long outputs with explicit gaps |
| `PlacementSpec`, `SpreadType` | Exact random-spread placement model |
| `StructureObservation`, `StructureGeometry` | Observation records and chunk/geometry helpers |
| `StructureVariantObservation`, `StructureVariantFilter` | Exact structure variant/rotation observation boundary |
| `StructureSeedLifter`, `LiftOptions`, `LiftResult`, `LiftEstimate` | Low-48 lifting |
| `UpperBitRecovery`, `RecoveryOptions`, `RecoveryResult` | 65,536-upper-half recovery |
| `SeedCracking`, `CrackResult` | End-to-end facade |
| `SeedCrackingPipeline` | SeedcrackerX-style phase trace around the isolated facade |
| `WorldSeedFilter` and the `*Filter` classes | Full-seed filter boundary |
| `BiomeOracle`, `TerrainOracle`, `StrongholdOracle`, `BedrockOracle`, `LootOracle`, `StructureStartOracle` | Minecraft-dependent oracle interfaces |
| `DimensionBiomeObservation`, `DimensionBiomeOracle`, `DimensionBiomeFilter` | Explicit-dimension biome constraints for Overworld/Nether/End adapters |
| `StructureVariantOracle` | Version-matched structure variant oracle interface |
| `FeaturePlacementObservation`, `FeaturePlacementFilter`, `FeaturePlacementOracle` | Partial or complete feature-anchor checks backed by a version-matched placed-feature oracle |
| `BlockPatternObservation`, `BlockPatternFilter`, `BlockPatternOracle` | Exact-coordinate block pattern checks for explicit observations |
| `BiomeBoundaryObservation`, `BiomeBoundaryFilter`, `ClimateObservation`, `ClimateFilter` | Boundary and tolerance-based climate observations |
| `EndPillarFingerprint`, `PillarTimeMachine`, `PillarSearchResult` | End pillar fingerprint and bounded/parallel SeedCrackerX time-machine search |
| `SlimeChunkFilter` | Slime chunk predicate |
| `ConcentricRings263` | Stronghold ring geometry and biome-gate hook |
| `ConcentricRingsStrongholdOracle` | Stronghold oracle backed by ring geometry and a caller-supplied biome gate |
| `Dimension`, `BedrockPreset`, `BedrockModel`, `VanillaBedrockOracle`, `VerticalGradient263` | Version/preset-aware bedrock model |
| `Vanilla263BedrockOracle` | 26.3 compatibility facade; default Overworld is floor-only |
| `Xoroshiro128PlusPlus`, `XoroshiroRandom263`, `RandomSupport263`, `MthSeed263` | Positional Xoroshiro subset shared by 26.1-26.3 |
| `StructureEntropy` | SeedCrackerX-style bit accounting |
| `CandidateRanker` | Probabilistic/partial-observation ranking |
| `IncrementalCandidateSet` | Incremental elimination with history |
| `ShipwreckVariant` | Shipwreck observation/variant baseline |
| `PillagerOutpostFrequency` | Exact 26.1-26.3 `legacy_type_1` outpost frequency reducer |
| `RuinedPortalSelector` | Versioned setup and RNG-visible template/rotation/mirror selection |
| `MineshaftFrequency` | Exact `legacy_type_3` frequency reducer and caller-supplied type selector |
| `WorldgenSeeds263` | Exact `WorldgenRandom` seed helpers shared by 26.1-26.3 |

See `TOP10_SELECTION.md` for the provisional ranking and exclusions, and
`COMBINED_ARCHITECTURE.md` for the candidate database and hold-out validation
flow.

## Deliberate differences from SeedCrackerX

- SeedCrackerX hard-codes a 19-bit low sieve (`% 4`, `1 << 19`) for its
  old-structure/shipwreck finders. This module uses the exact range-derived
  width: 19 bits for range 20/12 and 20 bits for range 24. That is a sound
  generalization, not a different placement model.
- SeedCrackerX also feeds pillager outposts into the final exact cache but not
  into its lifting bit count. This module allows outposts in the sieve because
  the 26.1+ range 24 is liftable, while documenting that outposts still have a
  frequency reducer and exclusion zone.
- SeedCrackerX has a stateful phase engine (`PILLARS -> STRUCTURES -> LIFTING ->
  STRUCTURE_REDUCE -> BIOMES`). This module exposes the same building blocks but
  uses an explicit caller-driven pipeline instead of a global singleton.
- Minecraft-dependent methods are oracle interfaces, so the module compiles and
  ships independently of the addon.
- The version is an explicit input. The module does not silently reuse 26.3
  data for 26.1/26.2 or for unaudited future versions.

## Limitations

- No screenshot/CV ingestion is implemented. The geometry/orientation records
  are the intended boundary for a future visual pipeline.
- No biome, terrain, carver, aquifer, or loot-table simulator is implemented in
  pure Java. Use the oracle interfaces with a validated version-matched
  implementation.
- `PlacementSpec` models placement only. Frequency reduction, exclusion zones,
  biome checks, and structure-start generation are separate gates.
- Stronghold ring centres depend on the low 48 bits; the biome-offset final step
  can depend on the full seed. Do not treat ring geometry alone as an upper-bit
  verifier.
- Feature generation is version-specific. Older oracles are not proof of newer
  behavior.
- Future 26.x versions require a data/bytecode audit before being added to
  `MinecraftVersion`.

See `SEED_CRACKING_METHODS.md` for algorithm references, `METHOD_AUDIT.md` for
the per-method math/version/feasibility audit, `METHOD_PROGRESS.md` for
implementation and test status, and `TASK_LIST.md` for the full 106-method
inventory.
