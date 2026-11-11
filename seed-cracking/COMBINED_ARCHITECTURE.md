# Combined Top-10 Cracking Architecture

The isolated implementation is a staged candidate pipeline. It never edits
the addon or assumes that a potential structure chunk is a valid structure.

```text
observations
  -> StructureSeedLifter (placement + low-bit lifting)
  -> optional EndPillarFingerprint / SlimeChunkFilter / conditional LCG recovery
  -> low-48 candidate database
  -> UpperBitRecovery (65,536 upper halves per low seed)
  -> Overworld biome + terrain + Nether biome/bedrock filters
  -> IncrementalCandidateSet / CandidateRanker
  -> independent hold-out observations
```

The intended high-value combination is compact: structure starts constrain the
low 48 bits; End pillars and, where exact outputs are observable, LCG recovery
reduce low-bit candidates; biome, terrain, slime, and dimension-specific
bedrock/biome observations distinguish upper bits. Feature and loot filters
remain extension points until their exact target-version oracles are validated.

## Components

- `StructureSeedLifter` owns the low-48 search. It uses exact version-selected
  placement constants and does not apply biome, frequency, exclusion, or start
  gates unless a caller supplies a structure oracle.
- `EndPillarFingerprint` and `SlimeChunkFilter` are cheap candidate checks.
  Pillars depend on the structure seed; slime predicates depend on the full
  seed. `Lcg48OutputRecovery` applies only when exact LCG outputs and call gaps
  are observable from a relevant feature stream.
- `UpperBitRecovery` combines each surviving low seed with all 65,536 upper
  values. It evaluates the most selective full-seed filter first when the
  caller orders filters that way and enforces a candidate/time budget.
- `BiomeFilter`, `TerrainFilter`, and `BedrockFilter`
  are independent oracle-backed constraints. A version-aware caller must use
  26.1, 26.2, or 26.3 data explicitly; none silently reuses another version.
- Feature-placement and loot filters are not counted as verified constraints
  until their generators pass known-world parity tests.
- `IncrementalCandidateSet` is the shared in-memory candidate database. Every
  elimination records source, before/after counts, removed count, and revision.
  Persist its snapshots externally if a durable database is required.
- `SeedCrackingPipeline` and `SeedCracking` are the coordination facades. They
  return structure-only results when no full-seed oracle is supplied instead of
  falsely reporting a complete world seed.

## Evidence policy

Each observation is tagged by its source and whether it is exact or partial.
Exact filters reject candidates; partial observations may be ranked with
`CandidateRanker` but must not be treated as proof. Hold-out observations are
kept out of recovery filters and checked only after a candidate is reported.

The complete seed is accepted only when:

1. one low-48 candidate survives exact placement and independent low-48 checks;
2. one upper-half candidate survives at least two independent full-seed
   observations from different oracle families; and
3. all hold-out observations match.

If multiple candidates remain, the result is explicitly `AMBIGUOUS`/partial in
the caller's report; do not choose the highest score as a recovered seed.

## Current implementation boundary

The core filters and candidate stages are functional and dependency-free, but
Minecraft-dependent biome, terrain, and bedrock oracle truth needs target-
version generated-world fixtures. This is a research baseline, not a claim of
complete 64-bit recovery. The environment has Java 21 rather than the module's
required Java 25, and no independent 26.1/26.2/26.3 fixture matrix is retained.
