#!/usr/bin/env bash
# Pre-PR performance check (docs/PERF.md). Profiles the hot paths and prints a summary to compare
# with the reference numbers in docs/PERF.md.
#
#   tools/profile/profile.sh            desktop JVM profiles (relative: finds hotspots/regressions)
#   tools/profile/profile.sh --device   also the on-device microbenchmarks (ARM, the real numbers;
#                                       needs ANDROID_SERIAL and an attached, authorised device)
#
# Desktop numbers come from x86 HotSpot and only mean something relative to the previous run on
# the same machine. The phones are ARM running ART: judge absolute latency on the device.
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT=$PWD
OUT=${OUT:-$ROOT/build/profile/$(date +%Y%m%d-%H%M%S)}
SENTENCES=${SENTENCES:-1500}
LEARN_WORDS=${LEARN_WORDS:-20000}
DEVICE=0
for arg in "$@"; do
    case $arg in
        --device) DEVICE=1 ;;
        -h | --help) sed -n '2,11p' "$0"; exit 0 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done
mkdir -p "$OUT"

JAVA_BIN=$(dirname "$(readlink -f "$(command -v java)")")
JFR=${JFR:-$JAVA_BIN/jfr}
[ -x "$JFR" ] || { echo "jfr not found (JDK 21+ needed); set JFR=/path/to/jfr" >&2; exit 1; }

summarise() { # $1 = recording, $2 = title
    {
        echo "### $2: hot methods"
        "$JFR" view --width 160 hot-methods "$1" | sed -n '5,20p'
        echo
        echo "### $2: allocation by site"
        "$JFR" view --width 160 allocation-by-site "$1" | sed -n '5,17p'
        echo
    } >> "$OUT/summary.txt"
}

echo "== building" >&2
./gradlew --console=plain -q :tools:eval:installDist
EVAL=tools/eval/build/install/eval/bin/eval
: > "$OUT/summary.txt"

if [ -f data/processed/heldout.txt ]; then
    echo "== decoder: spatial eval, $SENTENCES sentences, 1 thread" >&2
    JAVA_OPTS="-XX:StartFlightRecording=filename=$OUT/spatial.jfr,settings=profile" \
        "$EVAL" --sentences "$SENTENCES" --sigma 0.25 --seed 7 --threads 1 --out "$OUT/spatial" \
        > "$OUT/spatial.log" 2>&1
    { echo "## Decoder (desktop JVM)"; grep -A5 "Latency of predict" "$OUT/spatial/report.txt"
      grep -E "word accuracy|FALSE CORRECTIONS" "$OUT/spatial/report.txt"; echo; } >> "$OUT/summary.txt"
    summarise "$OUT/spatial.jfr" "decoder"

    echo "== learning: learn eval, $LEARN_WORDS words" >&2
    JAVA_OPTS="-XX:StartFlightRecording=filename=$OUT/learn.jfr,settings=profile" \
        "$EVAL" --mode learn --words "$LEARN_WORDS" --seed 1 --out "$OUT/learn" > "$OUT/learn.log" 2>&1
    { echo "## Learning (desktop JVM)"; grep "runtime:" "$OUT/learn.log"; echo; } >> "$OUT/summary.txt"
    summarise "$OUT/learn.jfr" "learning"
else
    echo "data/processed is missing: skipping the eval profiles (tools/data/fetch-sources.sh, then" \
        "dictbuild count; see docs/EVAL.md)" | tee -a "$OUT/summary.txt" >&2
fi

echo "== keyboard: keystroke benchmark" >&2
./gradlew --console=plain :feature:keyboard-ui:testDebugUnitTest --tests '*KeystrokeBenchmarkTest' \
    -Pkeycorrect.bench=true -Pkeycorrect.jfr="$OUT/keys.jfr" --rerun -i 2>&1 \
    | grep "KEYSTROKE" | sed 's/^ *//' > "$OUT/keys.txt"
{ echo "## Keystroke path (desktop JVM)"; cat "$OUT/keys.txt"; echo; } >> "$OUT/summary.txt"
summarise "$OUT/keys.jfr" "keystroke"

if [ "$DEVICE" = 1 ]; then
    : "${ANDROID_SERIAL:?set ANDROID_SERIAL to the test device}"
    echo "== device ($ANDROID_SERIAL): microbenchmarks" >&2
    touch "$OUT/device.start"
    ./gradlew --console=plain :benchmark:connectedReleaseAndroidTest > "$OUT/device.log" 2>&1 \
        || { tail -30 "$OUT/device.log" >&2; exit 1; }
    {
        echo "## Device microbenchmarks ($ANDROID_SERIAL)"
        find benchmark/build -name '*benchmarkData.json' -newer "$OUT/device.start" -print0 \
            | xargs -0 python3 -I tools/profile/bench_summary.py
        echo
    } >> "$OUT/summary.txt"
fi

cat "$OUT/summary.txt"
echo "(full output in $OUT)" >&2
