#!/usr/bin/env bash
# Fetches the neural swipe encoder (Phase 10a-2) and the ExecuTorch source it runs on into
# data/raw/swipenet/ (git-ignored), and verifies them.
#
#   tools/data/fetch-swipe-model.sh            download missing/invalid files, verify all
#   tools/data/fetch-swipe-model.sh --record   also (re)write swipe-model-sources.sha256 from what
#                                              was downloaded (use only when bumping a source)
#
# Model: FUTO Swipe's encoder `honorable_sturgeon/model_int8.pte` (FUTO Model Weights License 1.0,
# LICENSE.md, which must ship with the weights) from huggingface.co/futo-org/futo-swipe, pinned to
# one commit and checked by SHA-256 (tools/data/swipe-model-sources.sha256).
#
# Runtime: the ExecuTorch source (BSD-3-Clause) at tag v1.5.1, the version the model was exported
# with, cloned at the tag's commit and checked against it; only the submodules the XNNPACK build
# needs are fetched, at the commits the tag pins (git verifies them by hash). The source is built by
# tools/swipenet/build.sh, never by this script. Everything else is read as data only.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RAW="$ROOT/data/raw/swipenet"
SUMS="$ROOT/tools/data/swipe-model-sources.sha256"
RECORD=0
[[ "${1:-}" == "--record" ]] && RECORD=1

MODEL_COMMIT="0a9a44bbe4d5b8aa8d1ff579ffc40d4861ae2467"
HF="https://huggingface.co/futo-org/futo-swipe/resolve/${MODEL_COMMIT}"

ET_TAG="v1.5.1"
ET_COMMIT="3b60683923245cf472b7323426920e15623ba361"
ET_URL="https://github.com/pytorch/executorch.git"
# ExecuTorch's CMake insists that its directory is named exactly `executorch`.
ET_DIR="$RAW/x/executorch-${ET_TAG}-src/executorch"
# What the XNNPACK backend and the runtime need, plus gflags and json (ExecuTorch's CMake always
# adds them); the rest (Vulkan, MLX, tests, ...) is not fetched.
ET_SUBMODULES=(
  backends/xnnpack/third-party/FP16
  backends/xnnpack/third-party/FXdiv
  backends/xnnpack/third-party/XNNPACK
  backends/xnnpack/third-party/cpuinfo
  backends/xnnpack/third-party/pthreadpool
  third-party/flatbuffers
  third-party/flatcc
  third-party/gflags
  third-party/json
)

# name (path under data/raw/swipenet/), fetched from the same path in the model repo
SOURCES=(
  "LICENSE.md"
  "README.md"
  "honorable_sturgeon/model_int8.pte"
  "honorable_sturgeon/metadata.json"
)

# shellcheck source=tools/data/lib.sh
source "$ROOT/tools/data/lib.sh"

mkdir -p "$RAW"
record_lines=()

for name in "${SOURCES[@]}"; do
  fetch_one "$name" "${HF}/${name}"
done

# ExecuTorch: a shallow clone of exactly the pinned commit (a git hash pins the whole tree).
et() { git -C "$ET_DIR" "$@"; }
if [[ "$(et rev-parse HEAD 2>/dev/null || true)" != "$ET_COMMIT" ]]; then
  echo "cloning      executorch ${ET_TAG} (${ET_COMMIT:0:12})"
  rm -rf "$ET_DIR"
  mkdir -p "$ET_DIR"
  et init -q
  et remote add origin "$ET_URL"
  et fetch -q --depth 1 origin "$ET_COMMIT"
  et -c advice.detachedHead=false checkout -q FETCH_HEAD
fi
if [[ "$(et rev-parse HEAD)" != "$ET_COMMIT" ]]; then
  echo "error: executorch is not at $ET_COMMIT" >&2
  exit 1
fi
et submodule update -q --init --depth 1 --recommend-shallow -- "${ET_SUBMODULES[@]}"
if [[ -n "$(et status --porcelain --ignore-submodules=none)" ]]; then
  echo "error: the executorch checkout has local changes; delete $ET_DIR and run again" >&2
  exit 1
fi
echo "ok           executorch ${ET_TAG} at ${ET_COMMIT:0:12} (+${#ET_SUBMODULES[@]} submodules)"

if [[ "$RECORD" -eq 1 ]]; then
  {
    echo "# SHA-256 of the neural swipe encoder (Phase 10a-2) in data/raw/swipenet/. Verified by"
    echo "# tools/data/fetch-swipe-model.sh. FUTO Swipe (FUTO Model Weights License 1.0), Hugging Face"
    echo "# commit ${MODEL_COMMIT}; recorded on $(date -u +%Y-%m-%d). ExecuTorch is pinned by"
    echo "# its git commit in the script."
    for l in "${record_lines[@]}"; do echo "$l"; done
  } >"$SUMS.new"
  mv "$SUMS.new" "$SUMS"
  echo "wrote $SUMS"
fi
echo "all swipe model sources present and verified in $RAW"
