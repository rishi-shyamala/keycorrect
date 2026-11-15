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

# English Wikipedia "Lists of common misspellings/For machines" (CC BY-SA 4.0), pinned to a
# revision id; EVAL ONLY (the misspell eval mode), never part of the shipped dictionary.
MISSPELL_REV="1199637275"
MISSPELL_URL="https://en.wikipedia.org/w/index.php?title=Wikipedia:Lists_of_common_misspellings/For_machines&oldid=${MISSPELL_REV}&action=raw"

# Emoji (Phase 4): Unicode emoji-test.txt and CLDR English annotations, Unicode License v3.
# Versions match: CLDR 48 annotates exactly the Unicode 17.0 emoji set.
EMOJI_VERSION="17.0.0"
CLDR_TAG="release-48-2"
CLDR_BASE="https://raw.githubusercontent.com/unicode-org/cldr/${CLDR_TAG}/common"

# name | url | upstream-sha1 (optional, "-" if none) | transport (raw|gitiles)
SOURCES=(
  "en_US_wordlist.combined.gz|${AOSP_BASE}/dictionaries/en_US_wordlist.combined.gz?format=TEXT|-|gitiles"
  "simplewiki-${WIKI_DATE}-pages-articles.xml.bz2|${WIKI_BASE}/simplewiki-${WIKI_DATE}-pages-articles.xml.bz2|f55881650e447c39e8bdeba4b1bb8d5576342d4b|raw"
  # Tatoeba only offers a rolling weekly export; the checksum pins what we used.
  "eng_sentences.tsv.bz2|https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2|-|raw"
  "wikipedia-misspellings-${MISSPELL_REV}.txt|${MISSPELL_URL}|-|raw"
  "emoji-test-${EMOJI_VERSION}.txt|https://unicode.org/Public/${EMOJI_VERSION}/emoji/emoji-test.txt|-|raw"
  "cldr-${CLDR_TAG}-annotations-en.xml|${CLDR_BASE}/annotations/en.xml|-|raw"
  "cldr-${CLDR_TAG}-annotationsDerived-en.xml|${CLDR_BASE}/annotationsDerived/en.xml|-|raw"
)

# shellcheck source=tools/data/lib.sh
source "$ROOT/tools/data/lib.sh"

mkdir -p "$RAW"
record_lines=()

for entry in "${SOURCES[@]}"; do
  IFS='|' read -r name url sha1 transport <<<"$entry"
  fetch_one "$name" "$url" "$sha1" "$transport"
done

if [[ "$RECORD" -eq 1 ]]; then
  {
    echo "# SHA-256 of raw sources in data/raw/. Verified by tools/data/fetch-sources.sh."
    echo "# AOSP LatinIME commit ${AOSP_COMMIT}; Simple English Wikipedia dump ${WIKI_DATE};"
    echo "# Tatoeba eng_sentences.tsv.bz2 is a rolling export, recorded on $(date -u +%Y-%m-%d)."
    echo "# Wikipedia common-misspellings list (eval only): revision ${MISSPELL_REV}."
    echo "# Emoji: Unicode ${EMOJI_VERSION} emoji-test.txt; CLDR ${CLDR_TAG} English annotations."
    for l in "${record_lines[@]}"; do echo "$l"; done
  } >"$SUMS.new"
  mv "$SUMS.new" "$SUMS"
  echo "wrote $SUMS"
fi
echo "all sources present and verified in $RAW"
