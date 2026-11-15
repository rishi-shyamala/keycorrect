# KeyCorrect

A private, fully customizable Android keyboard with on-device autocorrect, swipe typing, optional
offline voice typing and language-model ranking, and learning that never leaves the device.

**Status:** phases 0-9 of the plan are implemented (core keyboard, prediction, learning, editing,
clipboard, emoji, swipe, voice, neural and plugin engines, themes and size profiles, hardening).
Distributed as test builds (`0.5.0-test.N`); see [docs/INSTALL.md](docs/INSTALL.md) to install one.

**Privacy:** the app has no `INTERNET` permission, by design, and a unit test enforces it. Learned
data is encrypted on the device, backup and device transfer are disabled, and the only export is
a file you create yourself. See [docs/SECURITY.md](docs/SECURITY.md).

## Architecture

A Kotlin/Compose app split into pure-JVM core modules (decoder, dictionary, learning, layout) and
Android modules (keyboard UI, settings, storage). Keystrokes flow from the touch layer through
`PredictiveInput` to a decoder thread running `ClassicEngine`, optionally reranked by an on-device
language model in a separate `:engine` process, and learning feeds a personal model that the
engine reads as a snapshot. Optional voice and neural packs are separate APKs that must be signed
with the app's key. Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); decisions:
[docs/DECISIONS.md](docs/DECISIONS.md).

## Build and test

```sh
./gradlew assembleDebug                      # build
./gradlew testDebugUnitTest test             # all unit tests (Android + JVM modules)
./gradlew spotlessApply                      # format
./gradlew :app:installDebug                  # install on a connected device or emulator
```

CI runs `spotlessCheck detektMain testDebugUnitTest test lintDebug assembleDebug
:benchmark:assembleReleaseAndroidTest`. Toolchain: [docs/SETUP.md](docs/SETUP.md). Everything else
(eval, profiling, emulator, releases, PR rules): [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Docs

[ARCHITECTURE](docs/ARCHITECTURE.md), [DEVELOPMENT](docs/DEVELOPMENT.md),
[DECISIONS](docs/DECISIONS.md), [INSTALL](docs/INSTALL.md), [EVAL](docs/EVAL.md),
[PERF](docs/PERF.md), [SECURITY](docs/SECURITY.md), [TRANSFER](docs/TRANSFER.md),
[PLUGINS](docs/PLUGINS.md), [LAYOUT_FORMAT](docs/LAYOUT_FORMAT.md),
[DATA_SOURCES](docs/DATA_SOURCES.md), the original [PLAN](docs/PLAN.md) and the
[archive](docs/archive/README.md).

## License

GPL-3.0-or-later. See [LICENSE](LICENSE). The dictionary data has its own licence
([LICENSE-DATA.md](LICENSE-DATA.md)), and so do the components of the optional voice and neural
packs ([THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)).
