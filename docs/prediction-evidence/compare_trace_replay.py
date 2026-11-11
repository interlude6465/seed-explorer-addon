"""Compare a mineshaft trace-order replay with an independent completed oracle.

Usage: python compare_trace_replay.py <replay.log> <oracle.txt>
Checks positions, tables, loot seeds and recorded inventory/property signatures.
It does not certify seed-only prediction or unrecorded item properties.
"""

import re
import sys
from pathlib import Path


def compare(replay_path, oracle_path):
    raw = replay_path.read_bytes()
    replay = raw.decode("utf-16" if raw.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig").replace("\r\n", "\n")
    oracle = oracle_path.read_text(encoding="utf-8-sig")
    header = re.search(r"TRACE_REPLAY seed=(-?\d+) structure=(\S+) start=(-?\d+),(-?\d+) ", replay)
    complete = re.search(r"TRACE_REPLAY_COMPLETE canonical=(\d+) replay=(\d+)", replay)
    if not header or not complete or "STATUS COMPLETE" not in oracle:
        raise AssertionError("Both the replay and independent oracle must be complete")
    seed, structure, start_x, start_z = header.groups()
    prefix = f"OBSERVATION container ACTUAL seed={seed} structure={structure} start=[{start_x}, {start_z}] "
    actual = {}
    for line in oracle.splitlines():
        if not line.startswith(prefix):
            continue
        row = re.fullmatch(
            r"positions=\[BlockPos\{x=(-?\d+), y=(-?\d+), z=(-?\d+)\}\] tables=\[(\S+)\] "
            r"lootSeeds=\[(-?\d+)\] stacks=(.+) unrepresented=\[\]", line[len(prefix):])
        if not row:
            raise AssertionError("Unsupported or unrepresented actual inventory: " + line)
        x, y, z, table, loot_seed, stacks = row.groups()
        position = (x, y, z)
        if position in actual:
            raise AssertionError("Duplicate actual position: " + str(position))
        actual[position] = table, loot_seed, stacks
    predicted = {}
    for row in re.finditer(
        r"^REPLAY SimulatedContainer\[x=(-?\d+), y=(-?\d+), z=(-?\d+), lootTableId=(\S+), "
        r"lootSeed=(-?\d+), pairedChest=false\]$", replay, re.M):
        x, y, z, table, loot_seed = row.groups()
        position = (x, y, z)
        if position in predicted:
            raise AssertionError("Duplicate replay position: " + str(position))
        stack_rows = re.findall(r"^REPLAY_STACKS pos=" + re.escape(",".join(position)) + r" stacks=(.+)$", replay, re.M)
        if len(stack_rows) != 1:
            raise AssertionError("Expected one replay inventory: " + str(position))
        predicted[position] = table, loot_seed, stack_rows[0]
    if not actual or len(predicted) != int(complete[2]):
        raise AssertionError("Missing actual evidence or incomplete replay containers")
    if actual.keys() != predicted.keys():
        raise AssertionError(f"Positions differ: missing={actual.keys() - predicted.keys()}; extra={predicted.keys() - actual.keys()}")
    for position, expected in actual.items():
        if predicted[position] != expected:
            raise AssertionError(f"Table/seed/inventory differs at {position}: actual={expected}; replay={predicted[position]}")
        print("MATCH position=" + ",".join(position) + " lootSeed=" + expected[1])
    print(f"TRACE_ORACLE_MATCH containers={len(actual)}; positions/tables/seeds/recorded stack properties matched")
    print("Recorded generation order was supplied; this is not a seed-only exactness claim.")


if __name__ == "__main__":
    compare(Path(sys.argv[1]), Path(sys.argv[2]))
