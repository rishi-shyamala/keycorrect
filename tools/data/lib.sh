#!/usr/bin/env bash
# Shared download/verify helpers for fetch-sources.sh, fetch-voice.sh and fetch-neural.sh.
# Sourced, never run. The caller sets RAW (download dir), SUMS (checksum file) and RECORD (0|1)
# before calling fetch_one, and declares record_lines=().
UA="keycorrect-eval-fetch/1.0 (data pipeline; curl)"

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

# fetch_one NAME URL [UPSTREAM_SHA1|-] [raw|gitiles]
# Downloads RAW/NAME unless it is already there with the recorded checksum, then verifies it
# (or, with RECORD=1, appends its checksum to record_lines).
fetch_one() {
  local name="$1" url="$2" sha1="${3:--}" transport="${4:-raw}"
  local dest="$RAW/$name" want part got
  want="$(expected_sha256 "$name")"
  mkdir -p "$(dirname "$dest")"

  if [[ -f "$dest" && -n "$want" && "$(sha256_of "$dest")" == "$want" ]]; then
    echo "ok (cached)  $name  $(size_of "$dest")"
    if [[ "$RECORD" -eq 1 ]]; then record_lines+=("$want  $name"); fi
    return 0
  fi
  if [[ -z "$want" && "$RECORD" -eq 0 ]]; then
    echo "error: no checksum for $name in $SUMS (run with --record to create it)" >&2
    exit 1
  fi
  if [[ -f "$dest" && -n "$want" ]]; then
    echo "checksum mismatch, re-downloading $name" >&2
    rm -f "$dest"
  fi
  if [[ ! -f "$dest" ]]; then
    echo "downloading  $name"
    part="$dest.part"
    if [[ "$transport" == "gitiles" ]]; then
      # gitiles serves raw file content base64-encoded.
      curl -fL -A "$UA" --retry 5 --retry-delay 5 --retry-all-errors -sS "$url" | base64 -d >"$part"
    else
      curl -fL -A "$UA" --retry 5 --retry-delay 5 --retry-all-errors -C - -sS --progress-bar "$url" -o "$part"
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
}

# extract_once NAME SUFFIX TAR_FLAG: unpack RAW/NAME into RAW/x/<name minus suffix>, once.
extract_once() {
  local name="$1" suffix="$2" flag="$3" out
  out="$RAW/x/${name%"$suffix"}"
  if [[ ! -e "$out/.extracted" ]]; then
    rm -rf "$out"
    mkdir -p "$out"
    tar "$flag" "$RAW/$name" -C "$out"
    touch "$out/.extracted"
    echo "extracted    $name -> x/${name%"$suffix"}"
  fi
}
