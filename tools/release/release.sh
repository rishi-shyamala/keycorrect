#!/usr/bin/env bash
# Test releases for installing on a phone (docs/INSTALL.md, "Test releases").
#
#   tools/release/release.sh bump      next test number in version.properties (0.5.0-test.1 -> .2)
#                                      and versionCode + 1; commit the change yourself
#   tools/release/release.sh publish   build the signed release APK from HEAD and publish it as a
#                                      GitHub pre-release tagged v<versionName>
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
        if gh release view "$tag" >/dev/null 2>&1; then
            echo "Release $tag exists: run '$0 bump', commit and push first." >&2
            exit 1
        fi
        ./gradlew --console=plain -q :app:assembleRelease
        apk=app/build/outputs/apk/release/app-release.apk
        sdk=$(sed -n 's/^sdk.dir=//p' local.properties)
        "$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)/apksigner" verify "$apk"
        out=build/release/keycorrect-$name+$short.apk
        mkdir -p build/release
        cp "$apk" "$out"
        branch=$(git rev-parse --abbrev-ref HEAD)
        notes="Test build **$name+$short** (Settings → About shows this version).

- Commit: $sha (branch \`$branch\`)
- Package \`dev.keycorrect\`, signed with the KeyCorrect release key; updates install over it.
- Install: open this page on the phone, download the APK, allow your browser to install unknown
  apps when asked, then enable KeyCorrect in Settings → Keyboard list and default
  (docs/INSTALL.md)."
        gh release create "$tag" "$out" --target "$sha" --prerelease \
            --title "KeyCorrect $name (test build)" --notes "$notes"
        ;;
    *)
        sed -n '2,10p' "$0"
        exit 1
        ;;
esac
