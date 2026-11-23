#!/usr/bin/env bash
# Test releases for installing on a phone (docs/INSTALL.md, "Test releases").
#
#   tools/release/release.sh bump      next test number in version.properties (0.5.0-test.1 -> .2)
#                                      and versionCode + 1; commit the change yourself
#   tools/release/release.sh publish   build the signed release APK from HEAD and publish it as a
#                                      GitHub pre-release tagged v<versionName>, with the voice
#                                      pack APK as a second asset when its files are downloaded
#                                      (tools/data/fetch-voice.sh; otherwise it is skipped) and
#                                      the neural pack APK likewise (tools/data/fetch-neural.sh)
#                                      and the swipe pack APK (tools/data/fetch-swipe-model.sh and
#                                      tools/swipenet/build.sh android)
#
# publish needs a clean, pushed HEAD (the release points at that exact commit) and the signing
# properties in ~/.gradle/gradle.properties.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

prop() { sed -n "s/^$1=//p" version.properties; }

# The newest build-tools' apksigner under the SDK ($1), found without parsing ls output.
find_apksigner() {
    local newest="" dir
    for dir in "$1"/build-tools/*/; do
        [[ -x ${dir}apksigner ]] || continue
        if [[ -z $newest || $(printf '%s\n%s\n' "$newest" "$dir" | sort -V | tail -1) == "$dir" ]]; then
            newest=$dir
        fi
    done
    if [[ -z $newest ]]; then
        echo "No apksigner under $1/build-tools: install build-tools (docs/SETUP.md)." >&2
        return 1
    fi
    echo "${newest}apksigner"
}

cert() { "$apksigner" verify --print-certs "$1" | sed -n 's/.*certificate SHA-256 digest: //p' | sort; }

# publish_pack <label> <gradle-module> <verify-task> <package> <fetch-script> <note-blurb>
# Builds the optional pack APK when its inputs exist, checks it is signed with the keyboard's key,
# copies it to build/release, appends it to assets and sets <label>_note for the release notes.
publish_pack() {
    local label=$1 module=$2 verify=$3 pkg=$4 fetch=$5 blurb=$6
    local pack_apk=$module/build/outputs/apk/release/$module-release.apk
    local note_var=${label}_note cap=${label^}
    printf -v "$note_var" ''
    if ! ./gradlew --console=plain -q ":$module:$verify" >/dev/null 2>&1; then
        echo "$cap files not downloaded ($fetch): publishing without the $label pack." >&2
        return 0
    fi
    rm -f "$pack_apk"
    ./gradlew --console=plain -q ":$module:assembleRelease"
    if [[ ! -f $pack_apk ]]; then
        echo "The $label pack APK is unsigned or missing: see above." >&2
        exit 1
    fi
    "$apksigner" verify "$pack_apk"
    # The keyboard only loads a pack signed by its own key: refuse to publish a mismatch.
    local kcert pcert pack_out size
    kcert=$(cert "$apk")
    pcert=$(cert "$pack_apk")
    if [[ -z $kcert || $kcert != "$pcert" ]]; then
        echo "The $label pack's signing certificate differs from the keyboard's:" \
            "the keyboard would refuse it." >&2
        echo "  keyboard: ${kcert:-none}" >&2
        echo "  $label pack: ${pcert:-none}" >&2
        exit 1
    fi
    pack_out=build/release/keycorrect-$label-$name.apk
    cp "$pack_apk" "$pack_out"
    assets+=("$pack_out")
    size=$(du -h "$pack_out" | cut -f1)
    printf -v "$note_var" '\n- Optional %s pack `keycorrect-%s-%s.apk` (%s, package `%s`):\n%s' \
        "$label" "$label" "$name" "$size" "$pkg" "$blurb"
}

case "${1:-}" in
    bump)
        code=$(prop versionCode)
        name=$(prop versionName)
        if [[ $name =~ ^(.*-test\.)([0-9]+)$ ]]; then
            next="${BASH_REMATCH[1]}$((BASH_REMATCH[2] + 1))"
        else
            echo "versionName '$name' has no -test.N suffix; edit version.properties by hand" >&2
            exit 1
        fi
        sed -i "s/^versionCode=.*/versionCode=$((code + 1))/; s/^versionName=.*/versionName=$next/" \
            version.properties
        echo "version.properties: $name -> $next (versionCode $((code + 1)))"
        ;;
    publish)
        if [[ -n $(git status --porcelain --untracked-files=no) ]]; then
            echo "Uncommitted changes: commit them first so the release matches a commit." >&2
            exit 1
        fi
        sha=$(git rev-parse HEAD)
        short=$(git rev-parse --short HEAD)
        git fetch -q origin
        if [[ -z $(git branch -r --contains "$sha") ]]; then
            echo "HEAD ($short) is not on origin: push it first." >&2
            exit 1
        fi
        name=$(prop versionName)
        tag="v$name"
        if gh release view "$tag" >/dev/null 2>&1 || [[ -n $(git ls-remote --tags origin "refs/tags/$tag") ]]; then
            echo "Tag $tag exists (release or bare tag): run '$0 bump', commit and push first." >&2
            exit 1
        fi
        # The SDK: local.properties, else the environment (as Gradle itself resolves it).
        sdk=$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null || true)
        sdk=${sdk:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}
        if [[ -z $sdk || ! -d $sdk/build-tools ]]; then
            echo "No Android SDK: set sdk.dir in local.properties, or ANDROID_HOME / ANDROID_SDK_ROOT." >&2
            exit 1
        fi
        rm -f app/build/outputs/apk/release/app-release.apk
        # Phones only: the x86_64 libraries are for emulators (2.2 MB of SQLCipher; decision N-2).
        ./gradlew --console=plain -q -Pkeycorrect.abis=arm64-v8a :app:assembleRelease
        apk=app/build/outputs/apk/release/app-release.apk
        if [[ ! -f $apk ]]; then
            if [[ -f app/build/outputs/apk/release/app-release-unsigned.apk ]]; then
                echo "The APK is unsigned: set the keycorrect.release.* properties" \
                    "(storeFile, storePassword, keyAlias, keyPassword) in ~/.gradle/gradle.properties." >&2
            else
                echo "No release APK was produced." >&2
            fi
            exit 1
        fi
        apksigner=$(find_apksigner "$sdk")
        "$apksigner" verify "$apk"
        out=build/release/keycorrect-$name+$short.apk
        mkdir -p build/release
        cp "$apk" "$out"
        assets=("$out")
        # The optional packs (same signing key): only if their inputs exist.
        publish_pack voice voicepack verifyVoiceFiles dev.keycorrect.voice tools/data/fetch-voice.sh \
            "  the offline speech model for voice typing. Install it too, from the same page; it needs the
  keyboard installed from the same key. Updates install over it."
        publish_pack neural neuralpack verifyNeuralFiles dev.keycorrect.neural tools/data/fetch-neural.sh \
            "  the offline language model and its native library for suggestion reranking. Install it too,
  from the same page; it needs the keyboard installed from the same key. Updates install over it."
        publish_pack swipe swipepack verifySwipeFiles dev.keycorrect.swipe \
            tools/data/fetch-swipe-model.sh \
            "  the optional neural swipe model (FUTO Swipe, about 3.2 MB) that reads swipes more accurately
  than the built-in decoder. Without it swipes still work; it is on by default once installed and
  Settings → Editing → Neural swipe turns it off. Powered by FUTO Swipe, used under the FUTO Model
  Weights License 1.0, whose text ships in the pack and in Settings → About. It needs the keyboard
  installed from the same key. Updates install over it."
        branch=$(git rev-parse --abbrev-ref HEAD)
        notes="Test build **$name+$short** (Settings → About shows this version).

- Commit: $sha (branch \`$branch\`)
- Package \`dev.keycorrect\`, signed with the KeyCorrect release key; updates install over it.
- Install: open this page on the phone, download the APK, allow your browser to install unknown
  apps when asked, then enable KeyCorrect in Settings → Keyboard list and default
  (docs/INSTALL.md).$voice_note$neural_note$swipe_note"
        gh release create "$tag" "${assets[@]}" --target "$sha" --prerelease \
            --title "KeyCorrect $name (test build)" --notes "$notes"
        ;;
    *)
        sed -n '2,14p' "$0"
        exit 1
        ;;
esac
