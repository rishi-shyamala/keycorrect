#!/usr/bin/env bash
# Builds libkcllama (the JNI glue plus llama.cpp, CPU only) for the desktop into build/llama-host/ and
# prints the .so path. The JVM tests and the eval use it:
#
#   export KC_LLAMA_LIB=$(tools/llama/build-host.sh | tail -1)
#   ./gradlew :core:llama:test
#
# Needs the llama.cpp source from tools/data/fetch-neural.sh (data/raw/neural/x/). cmake and ninja:
# the ones on PATH, else the Android SDK's (CMAKE_DIR to override).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# data/ is git-ignored; a worktree may not have its own copy, so also look next to the main checkout.
LLAMA_SRC="${LLAMA_SRC:-}"
if [[ -z "$LLAMA_SRC" ]]; then
  for base in "$ROOT" "$(git -C "$ROOT" rev-parse --path-format=absolute --git-common-dir 2>/dev/null | xargs -r dirname)"; do
    cand="$base/data/raw/neural/x/llama.cpp-v0.7.0/llama.cpp-0.7.0"
    if [[ -f "$cand/CMakeLists.txt" ]]; then LLAMA_SRC="$cand"; break; fi
  done
fi
[[ -f "$LLAMA_SRC/CMakeLists.txt" ]] || { echo "llama.cpp source not found; run tools/data/fetch-neural.sh" >&2; exit 1; }

CMAKE="$(command -v cmake || true)"
NINJA="$(command -v ninja || true)"
if [[ -z "$CMAKE" ]]; then
  CMAKE_DIR="${CMAKE_DIR:-$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/cmake/*/bin 2>/dev/null | sort -V | tail -1)}"
  CMAKE="$CMAKE_DIR/cmake"
  NINJA="$CMAKE_DIR/ninja"
fi
[[ -x "$CMAKE" ]] || { echo "cmake not found (install it or set CMAKE_DIR)" >&2; exit 1; }

# The JDK Gradle uses: JAVA_HOME, else the java on PATH.
JDK="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
[[ -f "$JDK/include/jni.h" ]] || { echo "no JNI headers under $JDK/include (need a JDK, set JAVA_HOME)" >&2; exit 1; }
case "$(uname -s)" in
  Darwin) JNI_OS=darwin; LIB=libkcllama.dylib ;;
  *) JNI_OS=linux; LIB=libkcllama.so ;;
esac

OUT="$ROOT/build/llama-host"
GEN=()
[[ -x "$NINJA" ]] && GEN=(-G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA")

"$CMAKE" -S "$ROOT/core/llama/src/main/cpp" -B "$OUT" "${GEN[@]}" \
  -DCMAKE_BUILD_TYPE=Release \
  -DLLAMA_SRC="$LLAMA_SRC" \
  -DKC_JNI_INCLUDE="$JDK/include;$JDK/include/$JNI_OS" >&2
"$CMAKE" --build "$OUT" --target kcllama -j "$(nproc 2>/dev/null || echo 4)" >&2

echo "$OUT/$LIB"
