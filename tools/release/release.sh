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
#
# publish needs a clean, pushed HEAD (the release points at that exact commit) and the signing
# properties in ~/.gradle/gradle.properties.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

prop() { sed -n "s/^$1=//p" version.properties; }

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
        ./gradlew --console=plain -q :app:assembleRelease
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
        "$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)/apksigner" verify "$apk"
        out=build/release/keycorrect-$name+$short.apk
        mkdir -p build/release
        cp "$apk" "$out"
        assets=("$out")
        # The optional voice pack (package dev.keycorrect.voice, same key): only if its inputs exist.
        voice_note=""
        if ./gradlew --console=plain -q :voicepack:verifyVoiceFiles >/dev/null 2>&1; then
            rm -f voicepack/build/outputs/apk/release/voicepack-release.apk
            ./gradlew --console=plain -q :voicepack:assembleRelease
            vapk=voicepack/build/outputs/apk/release/voicepack-release.apk
            if [[ ! -f $vapk ]]; then
                echo "The voice pack APK is unsigned or missing: see above." >&2
                exit 1
            fi
            apksigner="$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)/apksigner"
            "$apksigner" verify "$vapk"
            # The keyboard only loads a pack signed by its own key: refuse to publish a mismatch.
            cert() { "$apksigner" verify --print-certs "$1" | sed -n 's/.*certificate SHA-256 digest: //p' | sort; }
            kcert=$(cert "$apk")
            vcert=$(cert "$vapk")
            if [[ -z $kcert || $kcert != "$vcert" ]]; then
                echo "The voice pack's signing certificate differs from the keyboard's:" \
                    "the keyboard would refuse it." >&2
                echo "  keyboard: ${kcert:-none}" >&2
                echo "  voice pack: ${vcert:-none}" >&2
                exit 1
            fi
            vout=build/release/keycorrect-voice-$name.apk
            cp "$vapk" "$vout"
            assets+=("$vout")
            voice_size=$(du -h "$vout" | cut -f1)
            voice_note="
- Optional voice pack \`keycorrect-voice-$name.apk\` ($voice_size, package \`dev.keycorrect.voice\`):
  the offline speech model for voice typing. Install it too, from the same page; it needs the
  keyboard installed from the same key. Updates install over it."
        else
            echo "Voice files not downloaded (tools/data/fetch-voice.sh): publishing without the voice pack." >&2
        fi
        # The optional neural pack (package dev.keycorrect.neural, same key): only if its inputs exist.
        neural_note=""
        if ./gradlew --console=plain -q :neuralpack:verifyNeuralFiles >/dev/null 2>&1; then
            rm -f neuralpack/build/outputs/apk/release/neuralpack-release.apk
            ./gradlew --console=plain -q :neuralpack:assembleRelease
            napk=neuralpack/build/outputs/apk/release/neuralpack-release.apk
            if [[ ! -f $napk ]]; then
                echo "The neural pack APK is unsigned or missing: see above." >&2
                exit 1
            fi
            apksigner="$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)/apksigner"
            "$apksigner" verify "$napk"
            # The keyboard only loads a pack signed by its own key: refuse to publish a mismatch.
            cert() { "$apksigner" verify --print-certs "$1" | sed -n 's/.*certificate SHA-256 digest: //p' | sort; }
            kcert=$(cert "$apk")
            ncert=$(cert "$napk")
            if [[ -z $kcert || $kcert != "$ncert" ]]; then
                echo "The neural pack's signing certificate differs from the keyboard's:" \
                    "the keyboard would refuse it." >&2
                echo "  keyboard: ${kcert:-none}" >&2
                echo "  neural pack: ${ncert:-none}" >&2
                exit 1
            fi
            nout=build/release/keycorrect-neural-$name.apk
            cp "$napk" "$nout"
            assets+=("$nout")
            neural_size=$(du -h "$nout" | cut -f1)
            neural_note="
- Optional neural pack \`keycorrect-neural-$name.apk\` ($neural_size, package \`dev.keycorrect.neural\`):
  the offline language model and its native library for suggestion reranking. Install it too,
  from the same page; it needs the keyboard installed from the same key. Updates install over it."
        else
            echo "Neural files not downloaded (tools/data/fetch-neural.sh): publishing without the neural pack." >&2
        fi
        branch=$(git rev-parse --abbrev-ref HEAD)
        notes="Test build **$name+$short** (Settings → About shows this version).

- Commit: $sha (branch \`$branch\`)
- Package \`dev.keycorrect\`, signed with the KeyCorrect release key; updates install over it.
- Install: open this page on the phone, download the APK, allow your browser to install unknown
  apps when asked, then enable KeyCorrect in Settings → Keyboard list and default
  (docs/INSTALL.md).$voice_note$neural_note"
        gh release create "$tag" "${assets[@]}" --target "$sha" --prerelease \
            --title "KeyCorrect $name (test build)" --notes "$notes"
        ;;
    *)
        sed -n '2,14p' "$0"
        exit 1
        ;;
esac
