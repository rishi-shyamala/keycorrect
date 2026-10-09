# Development environment setup (CachyOS / fish)

## 1. System packages (pacman)
```fish
sudo pacman -S --needed jdk21-openjdk android-udev
sudo archlinux-java set java-21-openjdk
```
- `jdk21-openjdk` runs Gradle and the Android Gradle Plugin (AGP 9.x needs JDK 17+; 21 is the safest LTS).
- `android-udev` installs USB rules so `adb` can see the S23 Ultra / Tab S9 without root. Replug the devices afterwards.
- You already have `git`, `curl`, `unzip`, `python3`, and `adb` (`android-tools` 37). Gradle and Kotlin come from the Gradle wrapper in the repo, and CMake from the SDK, so neither needs a system install.

## 2. Android SDK (via Google's Android CLI)
Install the CLI with Google's installer script (`https://dl.google.com/android/cli/latest/linux_x86_64/install.sh`). It installs `~/.local/bin/android` and uses `~/Android/Sdk` as the SDK root. The CLI sends usage metrics by default, so pass `--no-metrics` on every call.

```fish
android --no-metrics sdk install platforms/android-37.0
android --no-metrics sdk install build-tools/37.0.0
android --no-metrics sdk install ndk/29.0.14206865
android --no-metrics sdk install cmake/3.31.6
```
Check the result with `android --no-metrics sdk list` and `android --no-metrics info`.

On its first build, AGP also installs `build-tools/36.0.0` (its default) and `platform-tools` 37.0.1. Their adb is protocol-compatible with the system `android-tools` adb, so the two coexist.

The project points at the SDK through `local.properties`, which is git-ignored, so `ANDROID_HOME` and `JAVA_HOME` are optional. `java` comes from `archlinux-java`.

## 3. Pinned versions (as of 2026-10-09)
| Tool | Version | Where it's set |
|---|---|---|
| JDK | 21 | system |
| Gradle | 9.8.1 (checksum-pinned wrapper) | `gradle/wrapper/gradle-wrapper.properties` |
| AGP | 9.4.1 | `gradle/libs.versions.toml` |
| Kotlin | 2.4.21 (AGP built-in Kotlin, upgraded via build-logic) | `gradle/libs.versions.toml` |
| compileSdk / targetSdk / minSdk | 37 / 37 / 31 | `build-logic/.../KeyCorrectBuild.kt` |
| NDK | 29.0.14206865 | `build-logic/.../KeyCorrectBuild.kt` |

## 4. Devices
On the S23 Ultra (the Tab S9 is deferred until phone testing is complete): **Settings → About → Software information → tap Build number 7×**, then **Developer options → USB debugging** (Wireless debugging works too). Connect, accept the RSA prompt, and run:
```fish
adb devices -l
```

## 5. Verification
```fish
java -version
android --no-metrics sdk list
adb devices -l
./gradlew assembleDebug testDebugUnitTest lintDebug
```
