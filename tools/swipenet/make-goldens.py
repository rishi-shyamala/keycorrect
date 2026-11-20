#!/usr/bin/env python3
"""Writes the reference outputs of FUTO Swipe's encoder for the :core:swipenet golden test.

Runs the model with the official executorch==1.5.1 wheel (docs/DECISIONS.md N-8) on a few fixed
gestures and layouts, and writes core/swipenet/src/test/resources/swipenet-goldens.json: for each
case the raw gesture, the features the reference preprocessing makes of it, the layout inputs, and
the three outputs. Floats are stored as base64 little-endian float32.

The wheel lives in a scratch venv outside the repo, never in it:

  python3.12 -m venv /tmp/et-venv
  /tmp/et-venv/bin/pip install executorch==1.5.1 pyyaml
  /tmp/et-venv/bin/pip install torch==2.14.0 --index-url https://download.pytorch.org/whl/cpu
  /tmp/et-venv/bin/python -I tools/swipenet/make-goldens.py \
      data/raw/swipenet/honorable_sturgeon/model_int8.pte \
      core/swipenet/src/test/resources/swipenet-goldens.json

The preprocessing here is FUTO's published Python reference (the model card's `resample`), plus
the clamp to [0, 1] that their C++ library applies; the Kotlin port is checked against it.
"""

import base64
import json
import sys

import numpy as np
import torch
from executorch.runtime import Runtime

MAX_KEYS = 64
POINTS = 64

# The model card's QWERTY letter centres (FUTO's training frame).
FUTO_QWERTY = {
    "a": (0.10, 0.500), "b": (0.60, 0.833), "c": (0.40, 0.833), "d": (0.30, 0.500),
    "e": (0.25, 0.167), "f": (0.40, 0.500), "g": (0.50, 0.500), "h": (0.60, 0.500),
    "i": (0.75, 0.167), "j": (0.70, 0.500), "k": (0.80, 0.500), "l": (0.90, 0.500),
    "m": (0.80, 0.833), "n": (0.70, 0.833), "o": (0.85, 0.167), "p": (0.95, 0.167),
    "q": (0.05, 0.167), "r": (0.35, 0.167), "s": (0.20, 0.500), "t": (0.45, 0.167),
    "u": (0.65, 0.167), "v": (0.50, 0.833), "w": (0.15, 0.167), "x": (0.30, 0.833),
    "y": (0.55, 0.167), "z": (0.20, 0.833),
}

# KeyCorrect's built-in QWERTY letters in row order, framed like SwipeLayout.of does it: the
# letter-slot bounding box is 10 key widths by 3 rows; the middle row is centred, and the bottom
# row's letters start after the 1.5-wide shift key. The golden test checks that SwipeLayout.of
# gives exactly these centres for our layout.
OUR_ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
OUR_OFFSETS = [0.0, 0.5, 1.5]


def our_qwerty():
    keys = []
    for r, (row, off) in enumerate(zip(OUR_ROWS, OUR_OFFSETS)):
        for i, c in enumerate(row):
            keys.append((c, ((off + i + 0.5) / 10.0, (r + 0.5) / 3.0)))
    return keys


# A real swipe of "computer" from the model card (normalised x, y; ms).
PX = [0.4141, 0.4478, 0.5, 0.5741, 0.6599, 0.7256, 0.7744, 0.8098, 0.8485, 0.867,
      0.8737, 0.8653, 0.8418, 0.8182, 0.8098, 0.7963, 0.7946, 0.8081, 0.8418, 0.8704,
      0.9057, 0.9259, 0.9545, 0.9697, 0.968, 0.9529, 0.9141, 0.8468, 0.7811, 0.7273,
      0.6869, 0.6616, 0.6582, 0.6431, 0.6061, 0.5572, 0.5067, 0.4663, 0.4495, 0.4461,
      0.4411, 0.4192, 0.3872, 0.362, 0.3283, 0.2795, 0.2391, 0.2323, 0.2407, 0.2593,
      0.2879, 0.3249, 0.3468, 0.3569]
PY = [0.8991, 0.858, 0.7876, 0.6702, 0.5352, 0.4237, 0.3357, 0.2653, 0.1655, 0.142,
      0.142, 0.2183, 0.3709, 0.588, 0.7347, 0.8462, 0.8697, 0.811, 0.6115, 0.4707,
      0.3122, 0.2066, 0.1303, 0.1068, 0.1068, 0.1068, 0.1185, 0.1596, 0.1772, 0.1772,
      0.1772, 0.189, 0.189, 0.189, 0.1831, 0.189, 0.189, 0.189, 0.189, 0.189,
      0.1831, 0.1831, 0.1831, 0.1831, 0.1831, 0.1948, 0.189, 0.1948, 0.189, 0.189,
      0.189, 0.1831, 0.1831, 0.1831]
PT = [0.0, 100, 149, 197, 246, 297, 348, 399, 449, 498, 548, 598, 648, 698, 749, 799,
      849, 949, 999, 1047, 1100, 1152, 1197, 1248, 1314, 1364, 1414, 1465, 1515, 1565,
      1614, 1666, 1715, 1851, 1898, 1951, 1998, 2049, 2097, 2165, 2231, 2279, 2331,
      2382, 2431, 2481, 2532, 2584, 2649, 2700, 2751, 2798, 2848, 2899]


def resample(px, py, pt):
    """The model card's reference: 60 Hz in time, then 64 points by index; then clamp."""
    x, y, t = (np.asarray(v, dtype=np.float64) for v in (px, py, pt))
    t = t - t[0]
    if len(x) >= 2 and t[-1] > 1e-3:
        n60 = max(2, round(t[-1] / (1000.0 / 60.0)) + 1)
        tt = np.linspace(0.0, t[-1], n60)
        x, y = np.interp(tt, t, x), np.interp(tt, t, y)
    idx = np.linspace(0, len(x) - 1, POINTS)
    rx = np.interp(idx, np.arange(len(x)), x)
    ry = np.interp(idx, np.arange(len(y)), y)
    return np.clip(np.stack([rx, ry], axis=0), 0.0, 1.0).astype(np.float32)


def b64(a):
    return base64.b64encode(np.ascontiguousarray(a, dtype="<f4").tobytes()).decode("ascii")


def cases():
    rng = np.random.default_rng(7)
    futo = sorted(FUTO_QWERTY.items())
    yield "computer, FUTO QWERTY", futo, PX, PY, PT
    yield "computer, our QWERTY", our_qwerty(), PX, PY, PT
    # A 120 Hz wobbly stroke with jittered timestamps, leaving the frame at both ends (clamped).
    n = 90
    t = np.cumsum(rng.uniform(5.0, 12.0, n)) - 5.0
    s = np.linspace(-0.1, 1.1, n)
    yield ("wobbly 120 Hz stroke, our QWERTY", our_qwerty(),
           list(s), list(0.5 + 0.3 * np.sin(6 * s) + rng.normal(0, 0.01, n)), list(t))
    # 40 keys at random places (a layout the encoder never saw) and a random walk.
    keys = [(chr(ord("a") + i % 26), tuple(rng.uniform(0.02, 0.98, 2))) for i in range(40)]
    walk = np.clip(np.cumsum(rng.normal(0, 0.04, (30, 2)), axis=0) + 0.5, 0, 1)
    yield ("random walk, 40 random keys", keys, list(walk[:, 0]), list(walk[:, 1]),
           list(np.arange(30) * 16.0))
    # Three keys only (mostly padding) and a two-point gesture with no duration.
    yield ("two points, no duration, 3 keys", [("a", (0.1, 0.5)), ("b", (0.5, 0.5)),
                                                 ("c", (0.9, 0.5))],
           [0.1, 0.9], [0.5, 0.5], [0.0, 0.0])


def main(model, out):
    method = Runtime.get().load_program(model).load_method("forward")
    result = []
    for name, keys, px, py, pt in cases():
        features = resample(px, py, pt)
        layout = np.zeros((MAX_KEYS, 2), dtype=np.float32)
        mask = np.zeros(MAX_KEYS, dtype=bool)
        for i, (_, centre) in enumerate(keys):
            layout[i] = centre
            mask[i] = True
        emissions, coefficients, lam = method.execute(
            (torch.from_numpy(features[None]), torch.from_numpy(layout[None]),
             torch.from_numpy(mask[None])))
        result.append({
            "name": name,
            "chars": "".join(c for c, _ in keys),
            "x": b64(np.asarray(px)), "y": b64(np.asarray(py)), "t": b64(np.asarray(pt)),
            "features": b64(features),
            "keys": b64(layout),
            "keyCount": len(keys),
            "logEmissions": b64(emissions.numpy()),
            "coefficients": b64(coefficients.numpy()),
            "lambda": b64(lam.numpy()),
        })
        print(f"{name}: emissions {tuple(emissions.shape)}", file=sys.stderr)
    with open(out, "w") as f:
        json.dump({"model": "futo-swipe honorable_sturgeon/model_int8.pte "
                            "(sha256 3480b200...5045511)",
                   "runtime": "executorch==1.5.1 (pip wheel)",
                   "cases": result}, f, indent=1)
        f.write("\n")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
