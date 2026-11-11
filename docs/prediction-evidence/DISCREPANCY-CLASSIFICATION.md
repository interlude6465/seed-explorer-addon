# Prediction discrepancy classification

The [audit matrix](AUDIT-MATRIX.md) retains failed, historical, alternate-history,
and incomplete comparisons. A passing narrower run does not erase them. These
classifications describe what the evidence establishes; they do not certify
all seeds or promote approximate inventories to exact predictions.

| Family / issue | Classification and product behavior | Independent evidence |
|---|---|---|
| Plains village, seed `123456789`, chest `(-4226,117,10527)` | **Generation-history dependency.** The same seed/layout/chest has two live loot seeds depending on first generated chunk. Neighboring short grass changes flower RNG. Show predicted items, retain approximate confidence. | [Default history](26.3-village-current-source-audit.txt), [east approach](26.3-village-east-approach.txt); live/offline feature trace comparison in README. |
| Savanna village, seed `1`, chest `(-271,91,-6956)` | **Generation-history dependency.** Neighboring grass versus dirt path changes the pile feature's RNG. Both live histories are retained; seed-only output remains approximate. | [Default history](26.3-savanna-pile-input-trace.txt), [west approach](26.3-savanna-west-approach.txt). |
| Mesa mineshaft, seed `Long.MIN_VALUE`, start `(-636,59)` | **Generation-history dependency.** Three versus four carts depends on placement order and the corridor spider-spawner state. Replaying all recorded chunks reproduces four independently observed inventories; it does not identify an unknown world's order from its seed. | [Broad history](history-retests/26.3-overworld-broad-mineshaft-retrace.txt), [near-south history](26.3-mineshaft-mesa-south-near-history.txt), README's four-cart trace replay. |
| Desert pyramid / beached shipwreck positions | **Generation-history dependency with bounded observed refinement.** First placement can change height and RNG. Loaded block observations may identify a unique replay, or leave several candidates. Ambiguous candidates remain approximate. | Separate default/approach/refinement rows in the matrix; independent saved-region comparisons and replay details in README. |
| Spruce-taiga camp phantom barrel | **Post-placement overwrite.** The live trace records structure placement followed by lake carving replacing the barrel with cave air. Canonical structure replay does not promise survival after later decoration; loaded observation cleanup removes stale coordinates. | `20261111-camp-lake-overwrite/reports/camp-placement-trace.txt` in the oracle archive; README camp checkpoint. |
| Ancient City, seed `Long.MIN_VALUE`, start `(-429,-521)` | **Unresolved surrounding-terrain/structure interaction.** All 20 positions match, but two loot seeds do not. Sculk consumes different RNG because capture-world blocks differ. Lazy terrain and recorded chunk-order experiments do not solve both cases. All city inventories remain approximate. | [Broad history](history-retests/26.3-overworld-broad-mineshaft-retrace.txt); `20261111-ancient-city-feature-terrain-baseline` and `20261111-ancient-city-lazy-terrain` archives. |
| Older ocean-ruin / trail-ruin item differences and unavailable comparisons | **Missing-resource bug corrected and retested.** The missing 26.3 archaeology tables were restored; actual brushable-block inventories are compared separately from chests. Older failed reports remain historical evidence. | [Restored-table checks](26.3-extreme-archaeology-layout-retest.txt), [broad rerun](history-retests/26.3-overworld-broad-mineshaft-retrace.txt). |
| Ordinary mineshaft extra carts, seed `1`, start `(-58,-339)` | **Overlapping-structure RNG bug corrected.** Replay now includes overlapping mineshaft references sharing the decoration chunk RNG. All eight independently generated carts match after the change. | README's overlap baseline/fixed archives and `StandaloneLootProbe` fixture. |
| Exploration-map metadata | **World-saved-data limit.** Independently captured target decorations are modeled. Saved map IDs, rendered preview data, and already-claimed destination history are not invented. Those components remain unverified. | [Two-seed map targets](26.3-map-targets-two-seeds.txt), `StandaloneLootProbe`. |
| Outpost goat-horn metadata | **Unrepresented instrument component.** Item counts and loot seeds agree, but horn instrument variants are outside the compact loot model. These inventories remain unverified, including a default-instrument stack whose live component patch is empty. | [Two-seed snowy/outpost audit](26.3-snowy-outpost-coverage.txt): six outpost inventories, zero sampled count/seed/position mismatches, four instrument-property comparisons unverified. |
| Older suspicious-stew metadata | **Representation gap corrected.** Effect IDs and durations now survive prediction and inventory comparison. This does not retroactively certify old reports which did not record those properties. | [Independent live stew fixture](26.3-stew-properties-live-shipwreck.txt), README's four saved inventory fixtures. |

The original plains-village oak RNG bug is corrected: the recorded seed-1 case
now matches all 13 inventories. This does not remove the different, independently
proven village history dependencies above.

Trial Chamber loot remains deferred by request. End Gateway remains skipped.
Nonempty multi-seed coverage, live search and rendering checks, and the final
build/review/push remain separate gates; this document does not mark them complete.
