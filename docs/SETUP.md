# Toolchain setup

Installing the build toolchain (Linux). Everyday commands, testing and releases are in
[DEVELOPMENT.md](DEVELOPMENT.md). CI installs the same SDK packages (`.github/workflows/ci.yml`).

## 1. Pinned versions

| Tool | Version | Where it's set |
|---|---|---|
| JDK | 21 | system |
| Gradle | 9.8.1 (checksum-pinned wrapper) | `gradle/wrapper/gradle-wrapper.properties` |
| AGP | 9.4.1 | `gradle/libs.versions.toml` |
| Kotlin | 2.4.21 (AGP built-in Kotlin, upgraded via `build-logic`) | `gradle/libs.versions.toml` |
| compileSdk / targetSdk / minSdk | 37 / 37 / 31 | `build-logic/.../KeyCorrectBuild.kt` |
| NDK | 29.0.14206865 | `build-logic/.../KeyCorrectBuild.kt` |

The authoritative values are in those files; this table can lag them.

## 2. System packages

JDK 21 (runs Gradle and AGP), plus `git`, `curl`, `unzip`, `python3` and `adb`
(platform-tools). On Arch: `sudo pacman -S --needed jdk21-openjdk android-udev` and
`sudo archlinux-java set java-21-openjdk` (`android-udev` gives adb USB access without root).

## 3. Android SDK

Install with Google's Android CLI (pass `--no-metrics`) or `sdkmanager`, into `~/Android/Sdk`:

```sh
android --no-metrics sdk install platforms/android-37.0
android --no-metrics sdk install build-tools/37.0.0
android --no-metrics sdk install ndk/29.0.14206865
```

CI installs exactly `platforms;android-37.0`, `build-tools;37.0.0` and `ndk;29.0.14206865`. AGP
fetches anything else it needs on the first build. `local.properties` (git-ignored) holds
`sdk.dir`, so `ANDROID_HOME` and `JAVA_HOME` are optional.

## 4. Emulator

Create an x86_64 AVD named `s23u` (1440x3088) for device checks and baseline profiles; see
"Testing on a device or emulator" in [DEVELOPMENT.md](DEVELOPMENT.md).

## 5. Verify

```sh
java -version
./gradlew assembleDebug testDebugUnitTest lintDebug
```
