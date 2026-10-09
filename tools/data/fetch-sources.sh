#!/usr/bin/env bash
# Fetches the raw data sources for the English dictionary build into data/raw/
# (git-ignored) and verifies them against tools/data/sources.sha256.
#
#   tools/data/fetch-sources.sh            download missing/invalid files, verify all
#   tools/data/fetch-sources.sh --record   also (re)write sources.sha256 from what was
#                                          downloaded (use only when bumping a source)
#
# Downloaded files are only ever hashed and handed to the dictbuild tool as data;
# nothing downloaded is executed or sourced.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RAW="$ROOT/data/raw"
SUMS="$ROOT/tools/data/sources.sha256"
RECORD=0
[[ "${1:-}" == "--record" ]] && RECORD=1

# AOSP LatinIME, pinned to an exact commit (the branch tip moves).
AOSP_COMMIT="127336e9f29d69607eab55982324b210279ae8c5"
AOSP_BASE="https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/${AOSP_COMMIT}"
# Simple English Wikipedia: a specific dated dump, never "latest".
WIKI_DATE="20261001"
WIKI_BASE="https://dumps.wikimedia.org/simplewiki/${WIKI_DATE}"

# name | url | upstream-sha1 (optional, "-" if none) | transport (raw|gitiles)
SOURCES=(
  "en_US_wordlist.combined.gz|${AOSP_BASE}/dictionaries/en_US_wordlist.combined.gz?format=TEXT|-|gitiles"
  "simplewiki-${WIKI_DATE}-pages-articles.xml.bz2|${WIKI_BASE}/simplewiki-${WIKI_DATE}-pages-articles.xml.bz2|f55881650e447c39e8bdeba4b1bb8d5576342d4b|raw"
  # Tatoeba only offers a rolling weekly export; the checksum pins what we used.
  "eng_sentences.tsv.bz2|https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2|-|raw"
)

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}
sha1_of() {
  if command -v sha1sum >/dev/null 2>&1; then sha1sum "$1" | cut -d' ' -f1
  else shasum -a 1 "$1" | cut -d' ' -f1; fi
}
expected_sha256() { # name -> hash or empty
  [[ -f "$SUMS" ]] || return 0
  awk -v n="$1" '!/^#/ && $2 == n { print $1 }' "$SUMS"
}
size_of() { du -h "$1" | cut -f1; }

mkdir -p "$RAW"
record_lines=()

for entry in "${SOURCES[@]}"; do
  IFS='|' read -r name url sha1 transport <<<"$entry"
  dest="$RAW/$name"
  want="$(expected_sha256 "$name")"

  if [[ -f "$dest" && -n "$want" && "$(sha256_of "$dest")" == "$want" ]]; then
    echo "ok (cached)  $name  $(size_of "$dest")"
    [[ "$RECORD" -eq 1 ]] && record_lines+=("$want  $name")
    continue
  fi
  if [[ -z "$want" && "$RECORD" -eq 0 ]]; then
    echo "error: no checksum for $name in $SUMS (run with --record to create it)" >&2
    exit 1
  fi

  [[ -f "$dest" && -n "$want" ]] && { echo "checksum mismatch, re-downloading $name" >&2; rm -f "$dest"; }
  if [[ ! -f "$dest" ]]; then
    echo "downloading  $name"
    part="$dest.part"
    if [[ "$transport" == "gitiles" ]]; then
      # gitiles serves raw file content base64-encoded.
      curl -fL --retry 5 --retry-delay 5 --retry-all-errors -sS "$url" | base64 -d >"$part"
    else
      curl -fL --retry 5 --retry-delay 5 --retry-all-errors -C - -sS --progress-bar "$url" -o "$part"
    fi
    mv "$part" "$dest"
  fi

  if [[ "$sha1" != "-" && "$(sha1_of "$dest")" != "$sha1" ]]; then
    echo "error: $name does not match the upstream-published SHA-1 $sha1" >&2
    rm -f "$dest"
    exit 1
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
done

if [[ "$RECORD" -eq 1 ]]; then
  {
    echo "# SHA-256 of raw sources in data/raw/. Verified by tools/data/fetch-sources.sh."
    echo "# AOSP LatinIME commit ${AOSP_COMMIT}; Simple English Wikipedia dump ${WIKI_DATE};"
    echo "# Tatoeba eng_sentences.tsv.bz2 is a rolling export, recorded on $(date -u +%Y-%m-%d)."
    for l in "${record_lines[@]}"; do echo "$l"; done
  } >"$SUMS.new"
  mv "$SUMS.new" "$SUMS"
  echo "wrote $SUMS"
fi
echo "all sources present and verified in $RAW"
