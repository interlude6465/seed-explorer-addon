"""Compare corridor RNG traces, relative to each structure's placement entry.

Usage: python docs/prediction-evidence/compare_mineshaft_trace.py <trace.log>
Optional second argument filters by world seed. This diagnoses RNG consumption;
matching draw counts alone do not verify terrain, random values or containers.
"""

import re
import sys
from collections import defaultdict
from pathlib import Path


def compare(path, seed_filter=None):
    entries = defaultdict(lambda: defaultdict(list))
    active = {}
    orphaned = 0
    for line in path.read_text(encoding="utf-8").splitlines():
        identity = re.search(
            r"phase=(start|before|after) source=(live|offline) seed=(-?\d+) chunk=(\[-?\d+, -?\d+\])",
            line,
        )
        if not identity:
            continue
        phase, source, seed, chunk = identity.groups()
        if seed_filter is not None and seed != seed_filter:
            continue
        count = int(re.search(r" count=(-?\d+)", line)[1])
        context = source, seed, chunk
        if phase == "start":
            start = re.search(r" start=(\[-?\d+, -?\d+\])", line)[1]
            record = {"entry_count": count, "events": []}
            entries[seed, chunk, start][source].append(record)
            active[context] = record
        else:
            record = active.get(context)
            if record is None:
                orphaned += 1
                continue
            box = re.search(r" box=(BoundingBox\{[^}]+\})", line)[1]
            state = tuple(re.findall(r"(?:spiderCorridor|hasPlacedSpider)=\w+", line))
            record["events"].append((phase, box, count - record["entry_count"], state))

    paired = differences = unpaired = 0
    for key, sources in entries.items():
        # Start tracing also sees other structure types, with no corridor events.
        live = [record for record in sources["live"] if record["events"]]
        offline = [record for record in sources["offline"] if record["events"]]
        if len(live) != len(offline):
            unpaired += abs(len(live) - len(offline))
            print("UNPAIRED seed/chunk/start=", key, "live/offline=", (len(live), len(offline)))
        for left, right in zip(live, offline):
            paired += 1
            if left["events"] == right["events"]:
                continue
            differences += 1
            print("DIFFERENCE seed/chunk/start=", key,
                  "events live/offline=", (len(left["events"]), len(right["events"])))
            for index, (a, b) in enumerate(zip(left["events"], right["events"])):
                if a != b:
                    print(f"  first difference event={index + 1}\n  live: {a}\n  offline: {b}")
                    break
            else:
                print("  shared events match; trailing events are unpaired")
    print(f"Paired placements={paired}; differences={differences}; unpaired={unpaired}; orphaned events={orphaned}")


if __name__ == "__main__":
    compare(Path(sys.argv[1]), sys.argv[2] if len(sys.argv) > 2 else None)
