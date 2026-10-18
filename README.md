# KeyCorrect

A private, fully customizable Android keyboard with pluggable on-device autocorrect and
speech-to-text engines, and learning that never leaves the device.

- Design & roadmap: [docs/PLAN.md](docs/PLAN.md)
- Dev environment: [docs/SETUP.md](docs/SETUP.md)

## Build

```sh
./gradlew assembleDebug        # build
./gradlew testDebugUnitTest    # unit tests
./gradlew spotlessApply        # format
./gradlew :app:installDebug    # install on a connected device
```

## License

GPL-3.0-or-later. See [LICENSE](LICENSE). The dictionary data has its own licence
([LICENSE-DATA.md](LICENSE-DATA.md)), and so do the components of the optional voice pack
([THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)).
