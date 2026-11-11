# Top-10 Selection After Full Inventory Review

The user requested a lightweight, practical top ten. The full 106-method
screen remains available in the audit ledger; this file ranks the methods that
are most useful together and already have isolated arithmetic or filter code.
Confidence values should agree with `METHOD_PROGRESS.md`; they are not
end-to-end recovery success rates:
independent 26.1/26.2/26.3 generated-world fixtures do not exist yet. All code
remains isolated in this directory and is not integrated into Meteor Client.

## Current candidates

| Candidate rank | Task method | Existing code boundary | Potential contribution | Confidence now |
|---:|---|---|---|---:|
| 1 | 1. Structure Coordinate Cracking | `StructureSeedLifter`, `PlacementSpec` | Recovers low-48 structure candidates from exact starts; the primary first stage. | 85% placement-only |
| 2 | 72. 16-Bit Seed Extension | `UpperBitRecovery` | Enumerates every full-seed extension and applies full-seed filters. | 80% mechanics, oracle-dependent |
| 3 | 53. Nether Bedrock Pattern Analysis | `VanillaBedrockOracle`, `BedrockFilter` | Full-seed positional-RNG evidence when preset and dimension are known. | 60%; generated Nether vectors pending |
| 4 | 54. Slime Chunk Cracking | `SlimeChunkFilter` | Exact legacy-RNG predicate at known chunks; combine separated observations. | 75%; generated vectors pending |
| 5 | 51. End Pillar Height Analysis | `EndPillarFingerprint` | Cheap 16-bit legacy-RNG fingerprint for low-48 candidates. | 80% pending generated vector |
| 6 | 76. RNG Reversal | `Lcg48` jump/reversal methods | Shared primitive for skipping known calls and validating/recovering LCG state. | 85% arithmetic; source stream must be identified |
| 7 | 78. Linear Congruential Generator Analysis | `Lcg48` | Shared exact Java RNG model used by structure, slime, pillar and output recovery. | 85% arithmetic; no separate signal |
| 8 | 50. Random Sequence Recovery | `Lcg48OutputRecovery` | Directly recovers legacy LCG state when exact outputs/gaps are observable. | 82% supported forms |
| 9 | 14. Stronghold Analysis | `ConcentricRings263`, `StrongholdFilter` | Additional low-48 constraints from observed ring positions. | 75%; final biome search remains conditional |
| 10 | 20. Multi-Structure Correlation | `SeedCrackingPipeline`, `IncrementalCandidateSet` | Combines independent observations and reports contradictions. | 80% mechanics; input quality governs result |

The practical sequence is #1 to recover low-48 candidates, #51 and #50 only
when their exact observations exist, then #72 with #53 and #54 as full-seed
filters. #76/#78 are shared arithmetic, not extra entropy; #20 coordinates
observations, and #14 is a conditional extra placement check. Biome and terrain
contracts are omitted from this focused list until backed by real Minecraft
oracles. Methods 53 and 54 are included as promising inexpensive filters, but
their current confidence remains below 85%; the original completion threshold
still applies before either is treated as validated.

## Preliminary exclusions and overlaps

The remaining methods are still researched and evaluated, but are either
redundant, weaker, or blocked by the same missing oracle:

- 2, 3, 20, 71, 80, 81, 88, 98, 100-102, and 106 support the selected
  placement/coordination pipeline but add no independent seed signal.
- 4, 5, 6-19, 35, 55, 59, 64-69, 95, and 97 are useful structure/variant or
  loot specializations, but their start, terrain, or observation oracle is
  less general than the selected filters.
- 22-24, 26-34, 39-49, 57-60, 89-96, and 103-105 remain valuable research
  directions but lack a verified 26.1-26.3 simulator, data set, or observation
  adapter in this isolated module.
- 52 duplicates the End pillar shuffle already used by method 51.
- 50 is conditional: it is useful only when exact legacy LCG output values and
  call gaps are observable from a relevant world-generation stream.
- 70 is not a direct world-seed method: fishing uses player/bobber RNG, luck,
  and timing rather than a deterministic world-seed stream.
- 74, 79, 83, and 84 require GPU/LLL/SAT/SMT dependencies and are throughput or
  solver strategies, not independently verified observations.
- 75-78, 82, 85-86 are useful computational support, but the selected #50 and
  #87 cover the practical RNG and search composition without duplicating the
  same arithmetic.

Every method ID 1-106 now appears in the audit and progress ledgers. The
exclusions below are the reasons they are not in this complementary ten: they
are duplicates, downstream specializations, observation-ingestion layers,
solver/throughput strategies, or blocked by the same missing oracle.

## Required validation before final selection

No candidate can be declared implementation-complete until each eventual top-ten oracle is backed
by independently generated 26.1.2, 26.2, and 26.3 worlds, including positive,
negative, and extreme signed seeds. Java 25 compilation and self-tests pass
using a temporary JDK. Generated-world fixtures or end-to-end recovery results
are still absent.
