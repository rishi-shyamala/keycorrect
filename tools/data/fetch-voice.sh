#!/usr/bin/env bash
# Fetches the sherpa-onnx runtime and the candidate speech models for Phase 6 (voice) into
# data/raw/voice/ (git-ignored), verifies them against tools/data/voice-sources.sha256, and
# extracts the archives into data/raw/voice/x/<archive-name>/.
#
#   tools/data/fetch-voice.sh            download missing/invalid files, verify all, extract
#   tools/data/fetch-voice.sh --record   also (re)write voice-sources.sha256 from what was
#                                        downloaded (use only when bumping a source)
#
# Everything comes from the official k2-fsa/sherpa-onnx GitHub releases (Apache-2.0). Downloaded
# files are only hashed, extracted and read as data; the Linux CLI binaries are executed only by the
# desktop benchmark (tools/), never by this script.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RAW="$ROOT/data/raw/voice"
SUMS="$ROOT/tools/data/voice-sources.sha256"
RECORD=0
UA="keycorrect-eval-fetch/1.0 (data pipeline; curl)"
[[ "${1:-}" == "--record" ]] && RECORD=1

# sherpa-onnx runtime release (pinned) and the rolling "asr-models" release tag (the checksum pins
# what we used; the asset names carry their own dates/versions).
SHERPA_VERSION="1.13.8"
GH="https://github.com/k2-fsa/sherpa-onnx/releases/download"
REL="${GH}/v${SHERPA_VERSION}"
MODELS="${GH}/asr-models"
RAWGH="https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v${SHERPA_VERSION}/sherpa-onnx/kotlin-api"

# name (path under data/raw/voice/) | url | extract (yes|no)
SOURCES=(
  # Runtime: Android native libs (jniLibs for arm64-v8a, x86_64, armeabi-v7a, x86) and the Linux
  # x86_64 CLI binaries (desktop benchmark).
  "sherpa-onnx-v${SHERPA_VERSION}-android.tar.bz2|${REL}/sherpa-onnx-v${SHERPA_VERSION}-android.tar.bz2|yes"
  "sherpa-onnx-v${SHERPA_VERSION}-linux-x64-shared-no-tts.tar.bz2|${REL}/sherpa-onnx-v${SHERPA_VERSION}-linux-x64-shared-no-tts.tar.bz2|yes"
  # Candidate models (English).
  "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2|${MODELS}/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2|yes"
  "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2|${MODELS}/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2|yes"
  "sherpa-onnx-whisper-tiny.en.tar.bz2|${MODELS}/sherpa-onnx-whisper-tiny.en.tar.bz2|yes"
  "silero_vad.onnx|${MODELS}/silero_vad.onnx|no"
  # Kotlin API sources (Apache-2.0, "Copyright (c) Xiaomi Corporation" headers), pinned to the tag.
  "kotlin-api-v${SHERPA_VERSION}/FeatureConfig.kt|${RAWGH}/FeatureConfig.kt|no"
  "kotlin-api-v${SHERPA_VERSION}/HomophoneReplacerConfig.kt|${RAWGH}/HomophoneReplacerConfig.kt|no"
  "kotlin-api-v${SHERPA_VERSION}/OfflineRecognizer.kt|${RAWGH}/OfflineRecognizer.kt|no"
  "kotlin-api-v${SHERPA_VERSION}/OfflineStream.kt|${RAWGH}/OfflineStream.kt|no"
  "kotlin-api-v${SHERPA_VERSION}/OnlineRecognizer.kt|${RAWGH}/OnlineRecognizer.kt|no"
  "kotlin-api-v${SHERPA_VERSION}/OnlineStream.kt|${RAWGH}/OnlineStream.kt|no"
  "kotlin-api-v${SHERPA_VERSION}/QnnConfig.kt|${RAWGH}/QnnConfig.kt|no"
  "kotlin-api-v${SHERPA_VERSION}/Vad.kt|${RAWGH}/Vad.kt|no"
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
    out="$RAW/x/${name%.tar.bz2}"
    if [[ ! -e "$out/.extracted" ]]; then
      rm -rf "$out"; mkdir -p "$out"
      tar -xjf "$dest" -C "$out"
      touch "$out/.extracted"
      echo "extracted    $name -> x/${name%.tar.bz2}"
    fi
  fi
done

if [[ "$RECORD" -eq 1 ]]; then
  {
    echo "# SHA-256 of the voice (Phase 6) sources in data/raw/voice/. Verified by tools/data/fetch-voice.sh."
    echo "# sherpa-onnx ${SHERPA_VERSION} (Apache-2.0): Android jniLibs, Linux x86_64 CLI, Kotlin API sources."
    echo "# Models come from the rolling sherpa-onnx 'asr-models' release; recorded on $(date -u +%Y-%m-%d)."
    echo "# Moonshine tiny-en (v2, offline) MIT; streaming Zipformer en-20M Apache-2.0; Whisper tiny.en MIT;"
    echo "# silero_vad.onnx MIT."
    for l in "${record_lines[@]}"; do echo "$l"; done
  } >"$SUMS.new"
  mv "$SUMS.new" "$SUMS"
  echo "wrote $SUMS"
fi
echo "all voice sources present and verified in $RAW"
