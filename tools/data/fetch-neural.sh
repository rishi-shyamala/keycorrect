#!/usr/bin/env bash
# Fetches the llama.cpp source and the candidate language models for Phase 7 (neural reranker) into
# data/raw/neural/ (git-ignored), verifies them against tools/data/neural-sources.sha256, and
# extracts the source archive into data/raw/neural/x/<archive-name>/.
#
#   tools/data/fetch-neural.sh            download missing/invalid files, verify all, extract
#   tools/data/fetch-neural.sh --record   also (re)write neural-sources.sha256 from what was
#                                         downloaded (use only when bumping a source)
#
# llama.cpp comes from the official ggml-org/llama.cpp GitHub release tag (MIT); the GGUF models come
# from the model authors' or ggml-org's Hugging Face repos, pinned to a commit. Downloaded files are
# only hashed, extracted and read as data; the llama.cpp source is built (desktop benchmark and,
# later, the NDK build) from tools/, never by this script.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RAW="$ROOT/data/raw/neural"
SUMS="$ROOT/tools/data/neural-sources.sha256"
RECORD=0
UA="keycorrect-eval-fetch/1.0 (data pipeline; curl)"
[[ "${1:-}" == "--record" ]] && RECORD=1

# llama.cpp release tag (pinned).
LLAMA_TAG="v0.7.0"
GH="https://github.com/ggml-org/llama.cpp/archive/refs/tags"
HF="https://huggingface.co"

# name (path under data/raw/neural/) | url | extract (yes|no)
SOURCES=(
  # Runtime: llama.cpp source at the pinned tag.
  "llama.cpp-${LLAMA_TAG}.tar.gz|${GH}/${LLAMA_TAG}.tar.gz|yes"
  # Candidate models (each URL is pinned to the repo commit that was benchmarked).
  # SmolLM2-135M base, Q8_0 (ggml-org, Apache-2.0).
  "SmolLM2-135M-Q8_0.gguf|${HF}/ggml-org/SmolLM2-135M-GGUF/resolve/44686446221a479a9227d7a895cf92930f86de8a/SmolLM2-135M-Q8_0.gguf|no"
  # SmolLM2-360M base, Q4_K_M (QuantFactory conversion of HuggingFaceTB/SmolLM2-360M, Apache-2.0).
  "SmolLM2-360M.Q4_K_M.gguf|${HF}/QuantFactory/SmolLM2-360M-GGUF/resolve/38cd16bc62ab1b1f0d320be8b518ac2fd80faa88/SmolLM2-360M.Q4_K_M.gguf|no"
  # Granite 4.0 350M, Q4_K_M (ibm-granite, Apache-2.0).
  "granite-4.0-350m-Q4_K_M.gguf|${HF}/ibm-granite/granite-4.0-350m-GGUF/resolve/b8208a86a58427e1739265318028eb5895b74bf2/granite-4.0-350m-Q4_K_M.gguf|no"
  # Qwen3-0.6B, Q8_0 (Qwen, Apache-2.0). Over the pack-size budget; kept as the quality reference.
  "Qwen3-0.6B-Q8_0.gguf|${HF}/Qwen/Qwen3-0.6B-GGUF/resolve/23749fefcc72300e3a2ad315e1317431b06b590a/Qwen3-0.6B-Q8_0.gguf|no"
  # Gemma 3 270M base, Q8_0 (ggml-org conversion of google/gemma-3-270m; Gemma Terms of Use, see
  # docs/PHASE7.md). Benchmark only; not a shipping candidate.
  "gemma-3-270m-Q8_0.gguf|${HF}/ggml-org/gemma-3-270m-GGUF/resolve/360e988e4c99209d62d171ca1a46aceb4b224791/gemma-3-270m-Q8_0.gguf|no"
)

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}
expected_sha256() { # name -> hash or empty
  [[ -f "$SUMS" ]] || return 0
  awk -v n="$1" '!/^#/ && $2 == n { print $1 }' "$SUMS"
}
size_of() { du -h "$1" | cut -f1; }

mkdir -p "$RAW"
record_lines=()

for entry in "${SOURCES[@]}"; do
  IFS='|' read -r name url extract <<<"$entry"
  dest="$RAW/$name"
  want="$(expected_sha256 "$name")"
  mkdir -p "$(dirname "$dest")"

  if [[ -f "$dest" && -n "$want" && "$(sha256_of "$dest")" == "$want" ]]; then
    echo "ok (cached)  $name  $(size_of "$dest")"
    [[ "$RECORD" -eq 1 ]] && record_lines+=("$want  $name")
  else
    if [[ -z "$want" && "$RECORD" -eq 0 ]]; then
      echo "error: no checksum for $name in $SUMS (run with --record to create it)" >&2
      exit 1
    fi
    [[ -f "$dest" && -n "$want" ]] && { echo "checksum mismatch, re-downloading $name" >&2; rm -f "$dest"; }
    if [[ ! -f "$dest" ]]; then
      echo "downloading  $name"
      part="$dest.part"
      curl -fL -A "$UA" --retry 5 --retry-delay 5 --retry-all-errors -C - -sS --progress-bar "$url" -o "$part"
      mv "$part" "$dest"
    fi
    got="$(sha256_of "$dest")"
    if [[ "$RECORD" -eq 1 ]]; then
      record_lines+=("$got  $name")
    elif [[ "$got" != "$want" ]]; then
      echo "error: SHA-256 mismatch for $name: got $got, expected $want" >&2
      rm -f "$dest"
      exit 1
    fi
    echo "ok           $name  $(size_of "$dest")"
  fi

  # Extract archives once, each into its own directory (never into the data root).
  if [[ "$extract" == "yes" ]]; then
    out="$RAW/x/${name%.tar.gz}"
    if [[ ! -e "$out/.extracted" ]]; then
      rm -rf "$out"; mkdir -p "$out"
      tar -xzf "$dest" -C "$out"
      touch "$out/.extracted"
      echo "extracted    $name -> x/${name%.tar.gz}"
    fi
  fi
done

if [[ "$RECORD" -eq 1 ]]; then
  {
    echo "# SHA-256 of the neural (Phase 7) sources in data/raw/neural/. Verified by tools/data/fetch-neural.sh."
    echo "# llama.cpp ${LLAMA_TAG} source (MIT); candidate GGUF models pinned to the HF commits in fetch-neural.sh;"
    echo "# recorded on $(date -u +%Y-%m-%d)."
    for l in "${record_lines[@]}"; do echo "$l"; done
  } >"$SUMS.new"
  mv "$SUMS.new" "$SUMS"
  echo "wrote $SUMS"
fi
echo "all neural sources present and verified in $RAW"
