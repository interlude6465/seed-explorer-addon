# Emerald observations and seed recovery in Minecraft 26.3

Status: **researched, not enabled as a cracking constraint**. The existing ore ESP
predictor is not a certified emerald seed filter. Positive observed emerald blocks
could help validate a small list of full world-seed candidates, but no defensible
bounded standalone low-48 recovery algorithm is implemented here.

## Actual vanilla feature

The 26.3 vanilla jar stores the definition at
`data/minecraft/worldgen/feature/ore_emerald.json`, not the older
`worldgen/configured_feature` location. Its placed feature is still
`data/minecraft/worldgen/placed_feature/ore_emerald.json`.

| Property | 26.3 vanilla value |
| --- | --- |
| Feature | `minecraft:ore`, size 3 |
| Placement | count 100, in-square, height range, biome filter |
| Height distribution | Trapezoid from absolute Y -16 to 480; actual blocks must also fit the world's build height |
| Air-exposure discard | 0.0 |
| Outputs | Emerald ore and deepslate emerald ore |
| Generation stage | `UNDERGROUND_ORES`, ordinal 6 |
| Feature seed index | Must be obtained from vanilla's global `FeatureSorter` step mapping; a biome's local feature-list index is not this index |

The two replacement predicates include the new
`minecraft:height_specific_ore_replaceables` handling, with height checks, as well
as ordinary stone/deepslate ore-replaceable tags. They must be evaluated through
the actual vanilla predicates. Treating every solid stone-like block as replaceable
is insufficient to establish exact placement.

The shipped biome JSON files include emerald generation in these ten biomes:
cherry grove, grove, jagged peaks, meadow, frozen peaks, stony peaks, snowy slopes,
windswept hills, windswept forest and windswept gravelly hills. The placement biome
check is evaluated at each candidate feature origin. A player standing in a
mountain biome does not establish that every nearby block passes that check.

## What an observation proves

A loaded `emerald_ore` or `deepslate_emerald_ore` block gives a positive position
and block-state observation. It does not reveal the feature attempt number,
decoration origin chunk, ore-vein center, RNG state or structure seed. Veins can
write across chunk boundaries. The block's own chunk is therefore not necessarily
the origin chunk of the feature attempt.

A future collector should record only positive loaded blocks, with world/session,
dimension/version and provenance. It must deduplicate positions and use a bounded
loaded-chunk scan. Absence is unusable when a block is mined, replaced, hidden by
an unloaded chunk, or removed by later generation. A vanilla client also cannot
distinguish natural ore from an exact player-placed block or a changed datapack;
any enabled mode would need to retain that untouched-vanilla assumption visibly.
No configured seed, predicted ESP position or integrated-server seed can be used
as observation evidence.

## Why this is not another legacy structure-seed constraint

Vanilla decoration uses `WorldgenRandom` backed by `XoroshiroRandomSource`, then
`setDecorationSeed(worldSeed, originBlockX, originBlockZ)` and
`setFeatureSeed(decorationSeed, globalFeatureIndex, generationStep)`. This path
depends on the full 64-bit world seed. The existing legacy LCG low-48 structure
placement sieve does not apply to emerald generation.
This RNG path was checked directly in the 26.3
`ChunkGenerator.applyBiomeDecoration` bytecode: it creates the Xoroshiro-backed
generator, seeds it from the world, and calls `FeaturePlacer.placeWithBiomeCheck`
after the global feature-index reseed.

Placement is also not a flat list of 100 independent X/Y/Z draws. Vanilla's lazy
placement iteration interleaves position sampling with feature execution. Biome
rejection, terrain/height checks, vein geometry and replacement predicates affect
which subsequent RNG calls occur. Reversing a few observed blocks while silently
assuming those hidden calls would reject valid seeds. No complete bounded reverse
algorithm for that uncertainty has been established in this integration.

## Existing OrePredictor limitations

`OrePredictor` is useful for marker/ESP predictions, but it should not be reused as
an exact exclusion oracle without independent generated-world certification:

- Its terrain accessor uses vanilla base columns plus an explicit bedrock rule,
  rather than complete surface, carver, structure and prior-decoration state.
- The selected-ore fast path replays only features targeting that ore. It omits
  earlier unrelated replacement writes that can affect later predicates.
- Vein geometry, attempt ordering and biome checks are reimplemented. Marker
  plausibility does not prove identical RNG consumption under every early exit.
- It has fallback replacement predicates after vanilla predicate rejection.
  Those widen accepted blocks and can change outputs compared with actual rules.
- Per-origin simulated terrain overrides and a fixed neighboring-origin radius
  are practical display choices; their equivalence to all generation histories
  has not been proven for seed exclusion.

Consequently, a missing ESP prediction must not remove a seed candidate merely
because the client observed emerald at that position.

## Defensible next implementation

The narrowest useful route is an **optional positive-only full-seed candidate
filter** after the structure/bedrock/biome workflow has produced a small candidate
list. Before enabling it:

1. Obtain the actual registry feature, global stage/index and native RNG. Replay
   vanilla placement rather than copying marker geometry.
2. Cover every possible feature-origin chunk capable of writing the observation.
   Establish that bound from the actual size-3 feature, including rotations and
   negative/chunk-boundary positions.
3. Supply independent complete pre-feature terrain, relevant structure state and
   preceding replacement writes. If a required state or history is unknown,
   return **unverified**, not a negative match.
4. Compare against independently generated 26.3 chunks across multiple seeds,
   every emerald-bearing biome, high/low Y, cave exposure, the stone/deepslate
   transition and neighboring chunk generation histories. Include both matching
   true seeds and independently verified mismatching candidates.
5. Bound candidate count, time and memory; propagate cancellation and reject
   stale world/session results. Keep the filter disabled if any true-seed
   exclusion remains unexplained.

No emerald observation is currently collected or used by automatic cracking, and
no standalone emerald seed-recovery feature is claimed. This document records
resource/code findings and the remaining validation requirement; it is not an
independent generated-world emerald parity report.
