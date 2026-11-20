#!/usr/bin/env bash
# Builds libkcswipenet (the JNI glue plus the ExecuTorch runtime, statically linked; see
# core/swipenet/src/main/cpp/CMakeLists.txt) from the ExecuTorch v1.5.1 source.
#
#   tools/swipenet/build.sh host       -> build/swipenet-host/libkcswipenet.so (last line printed)
#   tools/swipenet/build.sh android    -> build/swipenet-android/<abi>/libkcswipenet.so, stripped,
#                                         for arm64-v8a and x86_64 (what :swipepack packages)
#
#   export KC_SWIPENET_LIB=$(tools/swipenet/build.sh host | tail -1)
#   ./gradlew :core:swipenet:test
#
# Needs the source from tools/data/fetch-swipe-model.sh (data/raw/swipenet/x/), cmake and ninja
# (the ones on PATH, else the Android SDK's; CMAKE_DIR to override), the NDK for android
# (ANDROID_NDK_HOME, else the newest under the SDK), and a Python whose `torchgen` and `yaml`
# import (ExecuTorch generates its kernel registration with them): KC_ET_PYTHON, else python3.
# A venv with `pip install pyyaml torch==2.14.0 --index-url https://download.pytorch.org/whl/cpu`
# is enough (torch 2.14.0 is what ExecuTorch v1.5.1 pins).
set -euo pipefail

TARGET="${1:-}"
[[ "$TARGET" == host || "$TARGET" == android ]] || { echo "usage: $0 host|android" >&2; exit 2; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# data/ is git-ignored; a worktree may not have its own copy, so also look next to the main checkout.
ET_SRC="${ET_SRC:-}"
if [[ -z "$ET_SRC" ]]; then
  for base in "$ROOT" "$(git -C "$ROOT" rev-parse --path-format=absolute --git-common-dir 2>/dev/null | xargs -r dirname)"; do
    cand="$base/data/raw/swipenet/x/executorch-v1.5.1-src/executorch"
    if [[ -f "$cand/CMakeLists.txt" ]]; then ET_SRC="$cand"; break; fi
  done
fi
[[ -f "$ET_SRC/CMakeLists.txt" ]] || { echo "ExecuTorch source not found; run tools/data/fetch-swipe-model.sh" >&2; exit 1; }

PYTHON="${KC_ET_PYTHON:-$(command -v python3 || true)}"
"$PYTHON" -c "import torchgen, yaml" 2>/dev/null || {
  echo "$PYTHON cannot import torchgen and yaml; set KC_ET_PYTHON (see the header of $0)" >&2
  exit 1
}

CMAKE="$(command -v cmake || true)"
NINJA="$(command -v ninja || true)"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
if [[ -z "$CMAKE" ]]; then
  CMAKE_DIR="${CMAKE_DIR:-$(ls -d "$SDK"/cmake/*/bin 2>/dev/null | sort -V | tail -1)}"
  CMAKE="$CMAKE_DIR/cmake"
  NINJA="$CMAKE_DIR/ninja"
fi
[[ -x "$CMAKE" ]] || { echo "cmake not found (install it or set CMAKE_DIR)" >&2; exit 1; }
GEN=()
[[ -x "$NINJA" ]] && GEN=(-G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA")

OPS="$(grep -v -e '^#' -e '^$' "$ROOT/tools/swipenet/ops.txt" | paste -sd, -)"
SRC="$ROOT/core/swipenet/src/main/cpp"
JOBS="$(nproc 2>/dev/null || echo 4)"
COMMON=(
  -DCMAKE_BUILD_TYPE=Release
  -DET_SRC="$ET_SRC"
  -DKC_SWIPENET_OPS="$OPS"
  -DPYTHON_EXECUTABLE="$PYTHON"
)

if [[ "$TARGET" == host ]]; then
  # The JDK Gradle uses: JAVA_HOME, else the java on PATH.
  JDK="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
  [[ -f "$JDK/include/jni.h" ]] || { echo "no JNI headers under $JDK/include (need a JDK, set JAVA_HOME)" >&2; exit 1; }
  case "$(uname -s)" in
    Darwin) JNI_OS=darwin; LIB=libkcswipenet.dylib ;;
    *) JNI_OS=linux; LIB=libkcswipenet.so ;;
  esac
  OUT="$ROOT/build/swipenet-host"
  "$CMAKE" -S "$SRC" -B "$OUT" "${GEN[@]}" "${COMMON[@]}" \
    -DKC_JNI_INCLUDE="$JDK/include;$JDK/include/$JNI_OS" >&2
  "$CMAKE" --build "$OUT" --target kcswipenet -j "$JOBS" >&2
  echo "$OUT/$LIB"
  exit 0
fi

NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)}"
[[ -f "$NDK/build/cmake/android.toolchain.cmake" ]] || { echo "NDK not found (set ANDROID_NDK_HOME)" >&2; exit 1; }
STRIP="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip | head -1)"
for abi in arm64-v8a x86_64; do
  work="$ROOT/build/swipenet-android-work/$abi"
  "$CMAKE" -S "$SRC" -B "$work" "${GEN[@]}" "${COMMON[@]}" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM=android-31 \
    -DANDROID_STL=c++_static >&2
  "$CMAKE" --build "$work" --target kcswipenet -j "$JOBS" >&2
  mkdir -p "$ROOT/build/swipenet-android/$abi"
  "$STRIP" --strip-unneeded -o "$ROOT/build/swipenet-android/$abi/libkcswipenet.so" "$work/libkcswipenet.so"
  echo "$abi $(stat -c %s "$ROOT/build/swipenet-android/$abi/libkcswipenet.so") bytes" >&2
done
echo "$ROOT/build/swipenet-android"
