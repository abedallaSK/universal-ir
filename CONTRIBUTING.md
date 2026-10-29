# Contributing to Universal IR

Thanks for helping! Bug reports, device test results, translations and code are all welcome.

## Ways to help

- **Report what works on your devices.** Use the *Device compatibility report* issue form. Results from real TVs, ACs and boxes are the most valuable contribution, because most codes have not been confirmed on hardware yet.
- **Report a bug** with the *Bug report* form. Include your phone model, Android version and the app version (Settings → top card).
- **Suggest a feature** with the *Feature request* form.
- **Improve a translation.** Strings live in [`tools/strings_source.py`](tools/strings_source.py) (English, Arabic, Hebrew) and [`tools/strings_extra/`](tools/strings_extra) (other languages).
- **Add IR codes** through the catalog pipeline ([`tools/build_catalog.py`](tools/build_catalog.py)). Only redistributable sources are accepted; every source must be listed in [`NOTICE.md`](NOTICE.md).

## Development setup

Requirements: JDK 17+, Android SDK 36 (Android Studio works out of the box).

```bash
./gradlew :ir-core:test :app:testDebugUnitTest   # unit tests
./gradlew :app:assembleDebug                     # debug APK
```

Read [`CLAUDE.md`](CLAUDE.md) for the code conventions: project layout, how strings, icons and per-device settings work.

### Rules of thumb
- **Strings:** never edit `res/values*/strings.xml` by hand. Edit `tools/strings_source.py` and run `python -I tools/gen_strings.py`. Placeholders such as `%1$s` must match across languages.
- **RTL:** test Arabic or Hebrew when you change layouts. Model numbers, hex and timings use `LtrText`.
- **Honesty in code:** never show "sent" unless Android accepted the IR pattern; never claim a code works without evidence.
- **Licensing:** `ir-core/` is LGPL-2.1-or-later. Bundled code data must be redistributable and recorded in `NOTICE.md`.
- **No secrets:** never commit `keystore.properties`, keystores, API keys or personal data.

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Keep each pull request focused on one change; add or update tests where it makes sense.
3. Make sure `./gradlew :ir-core:test :app:testDebugUnitTest` passes. CI runs it on every pull request.
4. Fill in the pull request template.

By contributing you agree that your contribution is provided under the license of the part of the project you change (see [`LICENSE`](LICENSE) and [`NOTICE.md`](NOTICE.md)).

## Code of conduct

This project follows the [Code of Conduct](CODE_OF_CONDUCT.md). Please be kind and respectful.
