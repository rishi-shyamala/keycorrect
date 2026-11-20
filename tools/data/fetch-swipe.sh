#!/usr/bin/env bash
# Fetches the real-swipe eval data for Phase 10 (neural swipe) into data/raw/swipe/ (git-ignored)
# and verifies it against tools/data/swipe-sources.sha256.
#
#   tools/data/fetch-swipe.sh            download missing/invalid files, verify all
#   tools/data/fetch-swipe.sh --record   also (re)write swipe-sources.sha256 from what was
#                                        downloaded (use only when bumping the commit)
#
# Source: the swipe.futo.org dataset (MIT, FUTO), pinned to one Hugging Face commit. Only the
# swipe-1 dev and test splits (304 MB + 270 MB of JSON Lines) and the layout files are fetched; the
# 5.2 GB train split is not needed for evaluation. The big files' SHA-256 equal their upstream Git
# LFS object ids. Everything is read as data only.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RAW="$ROOT/data/raw/swipe"
SUMS="$ROOT/tools/data/swipe-sources.sha256"
RECORD=0
[[ "${1:-}" == "--record" ]] && RECORD=1

COMMIT="d71bf5fd7f45b3e7c2ed2d76a21b0dbd3b4ba566"
HF="https://huggingface.co/datasets/futo-org/swipe.futo.org/resolve/${COMMIT}"

# name (path under data/raw/swipe/), fetched from the same path in the dataset repo
SOURCES=(
  "LICENSE"
  "README.md"
  "dev.jsonl"
  "test.jsonl"
  "swipe-5/README.md"
  "swipe-5/layouts/qwerty.json"
)

# shellcheck source=tools/data/lib.sh
source "$ROOT/tools/data/lib.sh"

mkdir -p "$RAW"
record_lines=()

for name in "${SOURCES[@]}"; do
  fetch_one "$name" "${HF}/${name}"
done

if [[ "$RECORD" -eq 1 ]]; then
  {
    echo "# SHA-256 of the real-swipe eval data (Phase 10) in data/raw/swipe/. Verified by"
    echo "# tools/data/fetch-swipe.sh. swipe.futo.org dataset (MIT, FUTO), Hugging Face commit"
    echo "# ${COMMIT}; recorded on $(date -u +%Y-%m-%d)."
    for l in "${record_lines[@]}"; do echo "$l"; done
  } >"$SUMS.new"
  mv "$SUMS.new" "$SUMS"
  echo "wrote $SUMS"
fi
echo "all swipe sources present and verified in $RAW"
