# SUITE-PROGRESS

Persistent handoff log for the seed-cracking suite objective. Update this file
after each audited method or material validation milestone. This is not a claim
of completion.

## Objective

Research and evaluate all 106 methods in `TASK_LIST.md` for Minecraft Java
26.1, 26.2, and 26.3; after the complete comparison, select and justify the
ten strongest complementary methods, develop standalone code for those ten,
design a combined 64-bit candidate recovery architecture, and validate it with
independent known-seed worlds. Keep all work in this directory. Do not integrate
anything into Meteor Client or the addon.

## Current State (2026-11-11)

- Phase: complete inventory screen; the lightweight practical top ten is
  documented and its existing standalone pieces have been reviewed. It is not
  an 85%-validated end-to-end recovery claim.
- Individual audits completed through method #106, Hybrid Seed Cracking.
- Next phase: review and validate the selected standalone segments against
  independent generated worlds.
- Overall inventory: all 106 methods have an audit row and a progress row.
  Methods #22-49, #55-70, and #89-106 were screened at the level of their main
  principle/blocker; promising and version-sensitive methods received deeper
  source review.
- Existing isolated implementation already covers low-48 placement/lifting,
  upper-16 enumeration, several RNG and fingerprint methods, and reusable
  oracle-backed filters. Existing code is not proof of version compatibility.
- New isolated segments added during this continuation include
  `PillagerOutpostFrequency`, version-aware `RuinedPortalSelector`,
  `ShipwreckTemplateSelector.selectForStart`, and `MineshaftFrequency`.
- `COMBINED_ARCHITECTURE.md` documents staged candidate filtering and a
  hold-out verification policy. It is architectural documentation only until
  version-matched Minecraft oracles and reference worlds are available.

## Compatibility and Evidence

- Vanilla references used: cached 26.1.2 and 26.3 merged jars, plus the
  official 26.2 embedded server jar and extracted datapack JSON under
  `references/minecraft-26.2`.
- Inspected placement bytecode agrees across versions. Outpost frequency
  reducer and ruined-portal selection order are also compared across all three.
- Ruined portal setup data has a real 26.3 delta: four air-pocket probabilities
  change from `0.5` to `0.0`; `RuinedPortalSelector` accepts a version.
- Shipwreck structure-start RNG uses `setLargeFeatureSeed(worldSeed, chunkX,
  chunkZ)`, so rotation/template choices constrain only the low 48 bits.
- Mineshaft range-1 placement has no coordinate entropy; its exact
  `legacy_type_3` gate is `nextDouble() < 0.004` after chunk-seed initialization.
- Stronghold ring centers use `RandomSource.create(levelSeed)` (legacy
  48-bit RNG); final indexed positions require full-seed preferred-biome search
  with radius 112. The current identity gate is not a world oracle.
- Bastions share the exact range-23 `nether_complexes` placement constraint
  and set weights 2:3 for fortress:bastion. The 26.2 Jigsaw structure path
  calls `JigsawPlacement.addPieces` and carries an 80-block maximum-distance
  setting. A 26.1.2 Nether oracle capture exists but does not validate a
  predictor. A prior variant shortcut is not accepted as exact because actual
  pool expansion/template starts are not modeled. Full-family confidence is
  30%; see `references/BASTION_AUDIT.md`.
- Fortresses share that placement and set-selection gate. Their 26.2 start is
  an unconditional Y=64 stub followed by weighted recursive bridge/castle
  expansion and `moveInsideHeights(48,70)`. Full piece replay is not yet
  verified; see `references/FORTRESS_AUDIT.md`.
- Ancient cities use range-16 placement (spacing 24/separation 8) and add
  deep-dark, terrain, and Jigsaw gates. The placement is reusable but full
  prediction currently has low priority without an exact oracle.
- Trail ruins use range-26 placement and terrain-dependent buried templates;
  the range is outside the current low-bit sieve and the full oracle is absent.
- Trial chambers use range-22 placement (spacing 34/separation 12) but need
  biome/terrain/Jigsaw start and variant prediction; they remain a supplement.
- Temurin JDK 25.0.5 was downloaded to a unique temporary directory. All
  main/test sources compile with `--release 25 -Xlint:all`, and the complete
  dependency-free self-test suite passes. No project files outside this
  isolated module were modified by that setup.
- `test.ps1` currently fails under PowerShell native argument forwarding
  (`javac: invalid flag: :`) after recreating its two test-output directories;
  equivalent direct Java 25 compile/test commands pass in a fresh temporary
  output directory.
- No independently generated 26.1/26.2/26.3 fixture worlds or end-to-end
  complete 64-bit recovery tests have been produced.
- Do not treat compile success, synthetic oracle tests, or bytecode agreement
  alone as generated-world validation.
- Multi-structure correlation is implemented and synthetic-tested as the
  coordinator for candidate filters, but it contributes no independent entropy
  and remains oracle-quality-dependent.
- Best complementary direction so far: use independent high-entropy structure
  starts to recover low 48 bits, then use well-spaced biome samples to select
  among the 65,536 upper-bit extensions. The biome filter is only an interface
  today; no target-version oracle is validated, so this is not yet a proven
  end-to-end method.
- The Java LCG recovery path passed the isolated Java 25 compile and complete
  self-test suite, including mixed-width outputs and known gaps. It only helps
  when an observation exposes an exact LCG output stream.
- End spike height/order is one 16-bit fingerprint signal: 26.2 bytecode
  confirms the level-seeded `nextLong() & 0xFFFF` key and the model enumerates
  compatible permutations. Generated End vectors remain absent.
- Methods #55-70 screened: Nether biome/terrain overlaps the full-seed biome
  and terrain stages; loot is downstream of unresolved structure starts and
  loot-chain oracles; fishing uses player RNG, not direct world-seed RNG.
- Methods #22-49 screened: biome boundaries/climate and terrain profiles can
  contribute only through the missing biome/terrain oracle; caves/features and
  surface-dependent observations need larger simulators or are redundant.
  Population/decorator seed helpers remain useful primitives but need exact
  feature call-order replay.
- Full inventory review is complete. `TOP10_SELECTION.md` records the
  complementary shortlist centered on low-48 structure recovery plus
  independent full-seed filters. It remains provisional for implementation;
  several selected filter contracts have no target-version oracle and must not
  be labeled 85% complete.
- Added `DimensionBiomeObservation`, `DimensionBiomeOracle`, and
  `DimensionBiomeFilter` for the selected Nether-biome complement. The
  dimension-aware filter passes the isolated Java 21 self-test; it remains an
  oracle contract, not a Minecraft biome implementation.

## Top-Ten Status

`TOP10_SELECTION.md` now records the practical focused set: methods 1, 72, 53,
54, 51, 50, 76, 78, 14, and 20. The strongest usable path is structure
placement/lifting plus low-48 fingerprints, then upper-bit enumeration with
bedrock/slime filters. Biome and terrain remain documented optional contracts
until exact target-version oracles are available.

## Validation Gaps

- Generate independent target-version worlds for 26.1, 26.2, and 26.3 with
  positive, negative, and extreme signed seeds.
- Build or reuse verified biome, terrain, feature-placement, structure-start,
  and loot oracles, with version-specific data and tests.
- Measure observations needed, candidates remaining, success rate, elapsed
  time, and resource usage for each selected method and their combined pipeline.
- Verify final seed candidates using observations held out from recovery.
- Raise each ultimately selected method to at least 85% evidence-based
  confidence or revise the selection. Do not fabricate scores.

## Safety Boundary

All source, tests, and documentation remain under `seed-explorer-addon/seed-cracking`.
No Meteor Client/addon source, resources, commands, screens, Gradle files, or
integration points are to be edited. Keep this boundary in force until Aubrey
explicitly requests a later integration phase.

## Restart Instructions

1. Read this file, `TOP10_SELECTION.md`, and the 106-row ledgers.
2. Review each shortlisted segment and preserve the 85% evidence threshold.
3. Do not fabricate biome/terrain/bedrock adapters. Obtain exact
   version-matched sources and generated-world fixtures; record blockers when
   unavailable.
4. Keep README's handoff paragraph aligned with this file. Keep all changes in
   `seed-cracking/`; never integrate into the main mod.

## Latest Continuation Checkpoint (2026-11-11)

- Applied the user's preference for light screening: prioritize exact,
  reusable math and avoid building speculative biome/terrain simulators.
- Updated `TOP10_SELECTION.md` to a focused combination: structure placement,
  upper-bit enumeration, bedrock/slime filters, End pillar and conditional LCG
  observations, plus shared RNG/stronghold/coordinator support.
- Kept confidence values below 85% where the ledger lacks generated-world
  evidence. In particular, bedrock and slime are promising but not yet
  validated against independent target-version worlds.
- Recompiled all main/test sources with the available Java 21 compiler using
  `--release 21 -Xlint:all`; `SeedCrackingSelfTest` passed. A Java 25
  compile/self-test had passed in the previous checkpoint.
- No main-mod/addon files were changed. No generated Minecraft worlds or
  end-to-end 64-bit recovery fixtures have been added.
