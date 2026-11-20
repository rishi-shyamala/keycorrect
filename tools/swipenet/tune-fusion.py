#!/usr/bin/env python3
"""Grid-searches the neural swipe fusion weights on the dev split (docs/EVAL.md, "Neural fusion").

Reads the CSV that `--neural-dump` writes for the dev real-swipe set (never the test set) and
scores every candidate the way GestureRescorer does:

  score = max(channel, channel_floor) + neural_weight * max(neural, neural_floor)
          + lm_weight * lm + letters_weight * letters

`lm` in the dump already has the right-context term folded in at the shipped 0.3, so that weight
is not searched, and the dump's `neural` is already floored at the shipped -60, so a neural floor
can only be raised, not lowered. Prints the top-1 and top-3 of every grid point best first, then
the best one. Standard library only:

  ./gradlew -q :tools:eval:run --args="--mode swipe --real data/raw/swipe/dev.jsonl --neural \
      --neural-dump /tmp/dev-dump.csv"
  python3 -I tools/swipenet/tune-fusion.py /tmp/dev-dump.csv [--quick] [--max-swipes N]
"""

import argparse
import csv
import itertools
import sys

# The shipped weights (NeuralSwipeConfig and GestureConfig.maxChannelCost), and the grid around them.
SHIPPED = {"channel_floor": -40.0, "neural_weight": 0.8, "lm_weight": 1.0, "letters": 2.0, "neural_floor": -60.0}
GRID = {
    "channel_floor": [-30.0, -40.0, -50.0],
    "neural_weight": [0.5, 0.65, 0.8, 1.0, 1.2],
    "lm_weight": [0.8, 1.0, 1.2],
    "letters": [1.5, 2.0, 2.5],
    "neural_floor": [-60.0, -50.0, -40.0],
}
QUICK = {
    "channel_floor": [-40.0],
    "neural_weight": [0.65, 0.8, 1.0],
    "lm_weight": [1.0],
    "letters": [1.5, 2.0, 2.5],
    "neural_floor": [-60.0],
}


def load(file, max_swipes):
    """Per swipe: a list of (channel, neural, lm, letters, is_target)."""
    swipes = {}
    with open(file, newline="") as f:
        for row in csv.DictReader(f):
            i = int(row["swipe"])
            if max_swipes and i >= max_swipes:
                continue
            neural = float(row["neural"])
            if neural != neural:
                sys.exit("the dump has no neural scores: run the eval with --neural")
            swipes.setdefault(i, []).append(
                (float(row["channel"]), neural, float(row["lm"]), int(row["len"]), row["is_target"] == "1")
            )
    total = (max(swipes) + 1) if swipes else 0
    return [swipes.get(i, []) for i in range(total)]


def evaluate(swipes, w):
    cf, nw, lw, lb, nf = w
    top1 = top3 = 0
    for cands in swipes:
        scored = sorted(
            (-(max(c, cf) + nw * max(n, nf) + lw * lm + lb * k), not hit) for c, n, lm, k, hit in cands
        )[:3]
        if scored and not scored[0][1]:
            top1 += 1
        if any(not miss for _, miss in scored):
            top3 += 1
    return top1, top3


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dump", help="the --neural-dump CSV of the dev split")
    ap.add_argument("--quick", action="store_true", help="a small grid (neural weight and letters only)")
    ap.add_argument("--max-swipes", type=int, default=0, help="use only the first N swipes")
    args = ap.parse_args()

    swipes = load(args.dump, args.max_swipes)
    grid = QUICK if args.quick else GRID
    names = list(GRID)
    points = list(itertools.product(*(grid[n] for n in names)))
    shipped = tuple(SHIPPED[n] for n in names)
    if shipped not in points:
        points.append(shipped)
    print(f"{len(swipes)} swipes, {len(points)} grid points", file=sys.stderr)
    results = []
    for p in points:
        top1, top3 = evaluate(swipes, p)
        results.append((top1, top3, p))
    results.sort(key=lambda r: (-r[0], -r[1]))
    n = len(swipes)
    print(" ".join(names) + "  top1  top3")
    for top1, top3, p in results[:15]:
        mark = "  <- shipped" if p == shipped else ""
        print(" ".join(f"{v:g}" for v in p) + f"  {100 * top1 / n:.2f}  {100 * top3 / n:.2f}{mark}")
    top1, top3, p = next(r for r in results if r[2] == shipped)
    rank = 1 + sum(1 for r in results if r[0] > top1)
    print(f"shipped weights: top-1 {100 * top1 / n:.2f}%, top-3 {100 * top3 / n:.2f}%, rank {rank} of {len(results)}")
    best = results[0]
    print("best:", dict(zip(names, best[2])), f"top-1 {100 * best[0] / n:.2f}%")


if __name__ == "__main__":
    main()
