"""Evidence-retention regressions; generated fixtures are not Minecraft oracle evidence."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("update_matrix.py")


def report(created, types, *, mismatch=0, skip=False, metadata=True, incomplete=False):
    properties = " modeledLootMatch=3 metadataUnverified=0" if metadata else ""
    return f"""created={created}
requestedDimensions=0 includeTypes={types} omitTypes=<none>
queryAttempts=1 predictedSamples=1 dungeonRadiusChunks=2 skipLoot={str(skip).lower()}
SEED 1
  INVENTORY minecraft:mineshaft_mesa type=MINESHAFT_MESA
  minecraft:mineshaft_mesa actual=1 predicted=1 missed=0 extra=0 actualChests=3 predictedChests=3 missedChests=0 extraChests=0 lootMismatch={mismatch}{properties}
PROGRESS seed=1 type=DUNGEON actual=1 predicted=1
STATUS {'CHECKPOINT_INCOMPLETE' if incomplete else 'COMPLETE'}
FAILURES {mismatch}
UNVERIFIED 0
"""


class MatrixEvidenceTest(unittest.TestCase):
    def generate(self, reports):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, contents in reports.items():
                (root / (name + ".txt")).write_text(contents, encoding="utf-8")
            subprocess.run([sys.executable, str(SCRIPT), str(root)], check=True,
                           capture_output=True, text=True)
            return (root / "AUDIT-MATRIX.md").read_text(encoding="utf-8")

    def test_narrow_retest_preserves_broad_failure(self):
        matrix = self.generate({
            "broad": report("2026-01-01", "mineshaft,ancient_city", mismatch=1),
            "focused": report("2026-01-02", "mineshaft_mesa"),
        })
        self.assertIn("1 recorded discrepancies", matrix)
        self.assertIn("[broad.txt]", matrix)
        self.assertIn("[focused.txt]", matrix)
        # Dungeon evidence must also remain separate for different sampling histories.
        dungeon = matrix.split("## Dungeon room evidence")[1].split("## Sampling scopes")[0]
        self.assertIn("[broad.txt]", dungeon)
        self.assertIn("[focused.txt]", dungeon)

    def test_completed_same_scope_retest_supersedes_old_failure(self):
        matrix = self.generate({
            "old": report("2026-01-01", "mineshaft_mesa", mismatch=1),
            "new": report("2026-01-02", "mineshaft_mesa"),
            "unfinished": report("2026-01-03", "mineshaft_mesa", mismatch=4, incomplete=True),
        })
        self.assertNotIn("[old.txt]", matrix)
        self.assertNotIn("[unfinished.txt]", matrix)
        self.assertIn("[new.txt]", matrix)
        self.assertIn("Bounded samples matched", matrix)

    def test_location_only_and_legacy_metadata_cannot_certify_loot(self):
        matrix = self.generate({
            "loot": report("2026-01-01", "mineshaft_mesa", metadata=False),
            "locations": report("2026-01-02", "mineshaft_mesa", skip=True),
        })
        self.assertIn("[loot.txt]", matrix)
        self.assertIn("Legacy comparisons; item-property coverage not recorded", matrix)
        self.assertNotIn("Bounded samples matched", matrix)

    def test_unavailable_comparison_is_neither_a_pass_nor_an_item_difference(self):
        unavailable = report("2026-01-01", "mineshaft_mesa", mismatch=1).replace(
            "FAILURES 1\n", "FAILURES 1\n  loot mismatch seed=1 structure=minecraft:mineshaft_mesa "
            "chest=[0,64,0] inventory-fill simulation failed at [0,64,0]\n")
        matrix = self.generate({"unavailable": unavailable})
        self.assertIn("1 comparisons unavailable", matrix)
        self.assertNotIn("recorded discrepancies", matrix)
        self.assertNotIn("Bounded samples matched", matrix)

    def test_failed_simulation_cannot_certify_partial_sample(self):
        failed = report("2026-01-02", "mineshaft_mesa").replace(
            "FAILURES 0\n", "FAILURES 1\n  loot simulator failed seed=1 id=minecraft:mineshaft_mesa "
            "chunk=[0, 0] error=fixture exception\n")
        matrix = self.generate({"old": report("2026-01-01", "mineshaft_mesa"), "failed": failed})
        self.assertIn("1 loot simulations failed", matrix)
        self.assertIn("3 / 3 (partial)", matrix)
        self.assertNotIn("Bounded samples matched", matrix)
        self.assertNotIn("[old.txt]", matrix)

    def test_failed_simulation_zero_counters_are_not_an_empty_scan(self):
        failed = report("2026-01-01", "mineshaft_mesa").replace(
            "actualChests=3 predictedChests=3", "actualChests=0 predictedChests=0").replace(
            "modeledLootMatch=3", "modeledLootMatch=0").replace(
            "FAILURES 0\n", "FAILURES 1\n  loot simulator failed seed=1 id=minecraft:mineshaft_mesa "
            "chunk=[0, 0] error=timeout\n")
        matrix = self.generate({"failed": failed})
        self.assertIn("Unavailable (simulation failed)", matrix)
        self.assertIn("NOT VERIFIED (no nonempty loot sample); 1 loot simulations failed", matrix)
        self.assertNotIn("Bounded samples matched", matrix)


if __name__ == "__main__":
    unittest.main()
