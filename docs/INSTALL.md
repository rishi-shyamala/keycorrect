# KeyCorrect — Build and install on a device

How to build the keyboard on this machine and put it on an Android phone or tablet. Toolchain
setup (JDK 21, Android SDK) is in `docs/SETUP.md`.

## Requirements

- Android 12 or newer (minSdk 31), 64-bit ARM (arm64-v8a). x86_64 emulators also work.
- `adb` on the computer (`/usr/bin/adb` here).

## 1. Prepare the device (once)

1. Settings → About phone (tablet) → Software information → tap **Build number** 7 times.
2. Settings → Developer options → turn on **USB debugging**.
3. Connect by USB, unlock the device and accept **Allow USB debugging** (tick "Always allow").
4. Check it is visible:

```bash
adb devices -l
```

If it says `unauthorized`, run `adb kill-server`, reconnect, and accept the prompt again.

**Wireless instead of USB** (same Wi-Fi): Developer options → Wireless debugging → Pair device
with pairing code, then on the computer:

```bash
adb pair <ip>:<pairing-port>
```

```bash
adb connect <ip>:<port>
```

**Several devices attached:** pick one for every command with `export ANDROID_SERIAL=<serial>`
(fish: `set -x ANDROID_SERIAL <serial>`), or pass `-s <serial>` to `adb`.

## 2. Build and install

### Debug build (simplest; includes the field lab)

```bash
./gradlew :app:installDebug
```

This builds `app/build/outputs/apk/debug/app-debug.apk` and installs it as package
`dev.keycorrect.debug`. To install the APK file on another device later:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds are slower (Compose without optimisation). Use them for testing, not for judging
smoothness.

### Release build (fast; for daily use)

There is no release signing key yet, so sign the release APK with the local debug key:

```bash
./gradlew :app:assembleRelease
```

```bash
~/Android/Sdk/build-tools/37.0.0/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out app/build/outputs/apk/release/keycorrect-release.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

```bash
adb install -r app/build/outputs/apk/release/keycorrect-release.apk
```

It installs as package `dev.keycorrect`, next to the debug build. Updating a release install
needs the same signing key (the debug keystore of the machine that built it); with a different
key, uninstall first, which deletes the keyboard's learned data.

## 3. Turn the keyboard on

On the device: Settings → General management → Keyboard list and default → enable
**KeyCorrect**, then choose it as the default keyboard. Or from the computer:

| Build | Input method id |
|---|---|
| debug | `dev.keycorrect.debug/dev.keycorrect.ime.KeyCorrectImeService` |
| release | `dev.keycorrect/.ime.KeyCorrectImeService` |

```bash
adb shell ime enable dev.keycorrect/.ime.KeyCorrectImeService
```

```bash
adb shell ime set dev.keycorrect/.ime.KeyCorrectImeService
```

Right after an install, `ime` may answer "Unknown input method": wait a few seconds and retry.
Open the KeyCorrect app from the launcher for its settings.

## 4. Switch back or remove

Back to the Samsung keyboard:

```bash
adb shell ime set com.samsung.android.honeyboard/.service.HoneyBoardService
```

Uninstall (deletes the learned words, clipboard history and settings):

```bash
adb uninstall dev.keycorrect
```

```bash
adb uninstall dev.keycorrect.debug
```

## Notes

- **Data does not move between devices.** Learned words and clipboard history are encrypted with
  a key held in the device's Keystore, and backup/device transfer is disabled on purpose. A new
  device starts fresh.
- **No internet permission.** The keyboard never needs the network; everything is in the APK.
- **Force-stopping** the app (or reinstalling) can make Android switch to another keyboard;
  select KeyCorrect again.
