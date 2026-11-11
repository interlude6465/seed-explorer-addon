"""Compare opt-in live/offline pool-feature traces, normalizing RNG counters.

Usage: python docs/prediction-evidence/compare_village_trace.py <trace.log>
Only paired features are compared. Missing pairs are reported, not passes.
"""

import re
import sys
from collections import defaultdict
from pathlib import Path


def field(line, name):
    match = re.search(rf"\b{name}=(-?\d+)", line)
    return int(match[1]) if match else None


def compare(path):
    features = defaultdict(lambda: defaultdict(list))
    active = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        source = re.search(r"source=(live|offline)", line)
        if not source or " origin=" not in line:
            continue
        identity = re.search(r"seed=(-?\d+) chunk=(-?\d+,-?\d+).*?origin=(-?\d+, -?\d+, -?\d+)", line)
        if not identity:
            continue
        key = identity.groups()
        source = source[1]
        if line.startswith("phase=feature-before "):
            record = {"before": field(line, "count"), "predicates": [], "pile_attempts": []}
            features[key][source].append(record)
            active[source, key] = record
        elif (record := active.get((source, key))) is not None:
            if line.startswith("phase=feature-after "):
                record["draws"] = field(line, "count") - record["before"]
                del active[source, key]
            elif line.startswith("phase=feature-predicate "):
                predicate = line.split(" pos=", 1)[1]
                predicate = re.sub(r"count=\d+", "count=" + str(field(line, "count") - record["before"]), predicate)
                record["predicates"].append(predicate)
            elif line.startswith("phase=pile-attempt "):
                attempt = line.split(" pos=", 1)[1]
                attempt = re.sub(r"count=\d+", "count=" + str(field(line, "count") - record["before"]), attempt)
                record["pile_attempts"].append(attempt)

    pairs = mismatches = unpaired = 0
    for key, sources in features.items():
        live, offline = sources["live"], sources["offline"]
        unpaired += abs(len(live) - len(offline))
        for left, right in zip(live, offline):
            pairs += 1
            if "draws" not in left or "draws" not in right:
                print("INCOMPLETE", key)
                continue
            if (left["draws"] == right["draws"] and left["predicates"] == right["predicates"]
                    and left["pile_attempts"] == right["pile_attempts"]):
                continue
            mismatches += 1
            print("DIFFERENCE seed/chunk/origin=", key,
                  "draws live/offline=", (left["draws"], right["draws"]),
                  "predicates=", (len(left["predicates"]), len(right["predicates"])))
            if left["pile_attempts"] or right["pile_attempts"]:
                print("  pile attempts live/offline=", (len(left["pile_attempts"]), len(right["pile_attempts"])))
                for index, (a, b) in enumerate(zip(left["pile_attempts"], right["pile_attempts"])):
                    if a != b:
                        print(f"  first pile input/RNG difference at attempt {index + 1}\n  live: {a}\n  offline: {b}")
                        break
                else:
                    print("  common pile inputs agree; inspect unmatched attempts or later calls")
                continue
            for index, (a, b) in enumerate(zip(left["predicates"], right["predicates"])):
                # State-only differences can precede the first RNG divergence.
                if a.split(" state=")[0] != b.split(" state=")[0]:
                    print(f"  first predicate/RNG difference at attempt {index + 1}\n  live: {a}\n  offline: {b}")
                    break
            else:
                if left["draws"] != right["draws"]:
                    print("  RNG draw totals differ without a captured predicate divergence")
                else:
                    print("  predicate positions/results agree; environment states differ")
    print(f"Paired features={pairs}; differences={mismatches}; unpaired={unpaired}")


if __name__ == "__main__":
    compare(Path(sys.argv[1]))
