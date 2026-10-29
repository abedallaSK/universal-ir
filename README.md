<div align="center">

# Universal IR

**Every remote, one phone.** Control air conditioners, TVs, TV boxes, LED lights and more with your phone's built-in infrared emitter or over Wi‑Fi.

![Version](https://img.shields.io/badge/version-0.2.0-2F6BFF)
![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4)
![Languages](https://img.shields.io/badge/languages-15-orange)

[**Download the APK**](../../releases/latest) · [Features](#features) · [Supported devices](#supported-devices) · [Build](#build-from-source) · [Licensing](#licensing)

</div>

---

## Features

### Remotes
- **Air conditioners**: a temperature dial you can drag, mode / fan / swing tiles, Turbo, Quiet, Sleep, Eco, I Feel, one‑tap presets and an **off timer** that works even when the app is closed. While the AC is off you can change settings without switching it on; they are sent when you press Power.
- **TVs, TV boxes, projectors, audio**: a smart standard layout with a round D‑pad, VOL/CH rockers, media, colour keys, app keys and a number pad. Switch the D‑pad to a **swipe touchpad** (swipe to move, tap for OK).
- **LED strips and bulbs**: colour swatches, brightness and effects.
- **Five button styles**: Classic, Modern, Minimal, Compact and Vivid, per remote or for all remotes.

### Make it yours
- **Layout editor**: add any button, change its icon (55 icons), label, colour, shape and size, drag to move, drop on another key to swap, undo/redo, 3–5 columns, auto layout.
- **Custom home page**: your own title, tiles or list, tile sizes, colours and icons per device, drag to rearrange, show/hide sections.
- **Long‑press anything** for actions: favourite, rename, icon & colour, room, duplicate, delete.
- **Rooms**, **favourites** and **home‑screen widgets** (a device remote and a favourites widget).

### Wi‑Fi remotes
- **Android TV / Google TV** (e.g. Fujicom smart TVs): pair once with the code shown on the TV.
- **Samsung (Tizen)**, **LG (webOS)** and **Roku** TVs.
- **Air conditioners**: Gree‑based units (including Tadiran), Daikin Wi‑Fi adapters, **Electra Smart** (phone number + SMS code) and **Sensibo** (API key). These show the AC's real state.
- Automatic discovery on your network, or add by IP.
- **Wi‑Fi‑only mode**: on phones without an infrared emitter the app automatically shows only Wi‑Fi devices (Settings → Remote type).

### Everything else
- 470 bundled code sets, about 120 AC brands, and a **code wizard** that tries codes one by one until your device responds.
- Import Flipper Zero `.ir`, IRDB CSV and SmartIR JSON files, or build a remote from raw / Pronto / Broadlink codes.
- Works **offline**. No account, no analytics, no ads.
- **15 languages**: English, العربية, עברית, Français, Español, Deutsch, Português, Italiano, Русский, Türkçe, فارسی, हिन्दी, 简体中文, Bahasa Indonesia, اردو, with full right‑to‑left support.
- **Themes**: light / dark / system, six accent colours, wallpaper colours (Material You) and a pure‑black AMOLED mode.
- **IR repeat**: send each button press 1–5 times for devices that miss single presses.

## Supported devices

| Way | What you need |
|---|---|
| **Infrared** | A phone with an IR emitter (vivo, Xiaomi / Redmi / POCO, Huawei, Honor and others). Point the top of the phone at the device. |
| **Wi‑Fi** | Phone and device on the same Wi‑Fi network (cloud ACs work from anywhere). |

Android 8.0 (API 26) or newer.

## Install

1. Download the latest `universal-ir-*.apk` from [**Releases**](../../releases/latest).
2. Open it on your phone and allow installing from this source.
3. Open **Universal IR**, then tap **Add**.

The current release is a test build signed with a debug key. When a store‑signed version comes out, uninstall the test build first.

## Build from source

Requirements: JDK 17+, Android SDK 36 (Android Studio's bundled JBR works).

```bash
./gradlew :ir-core:test :app:testDebugUnitTest   # unit tests
./gradlew :app:assembleDebug                     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease                   # needs keystore.properties to be signed
```

To sign a release, create `keystore.properties` in the project root with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. It is ignored by git; never commit it.

### Project layout

```
app/        Android app: Compose UI, Room, DataStore, Hilt, Wi‑Fi controllers, widgets
ir-core/    Pure Kotlin IR engine: timing model, validation, protocol encoders, importers (LGPL-2.1-or-later)
tools/      build_catalog.py (bundles code data), strings_source.py + gen_strings.py (en/ar/he strings)
docs/       COMPATIBILITY.md, HARDWARE_TESTING.md, RESEARCH.md, update.json
licenses/   Third-party license texts (see NOTICE.md)
```

UI strings live in `tools/strings_source.py`; regenerate them with `python -I tools/gen_strings.py`. See [`CLAUDE.md`](CLAUDE.md) for the code conventions.

### In‑app updates

**Settings → Check for updates** reads `https://abedallask.netlify.app/universal-ir/update.json` (format in [`docs/update.json`](docs/update.json)). To publish an update, raise `versionCode` / `versionName` in `app/build.gradle.kts`, attach the APK to a GitHub release, and update that JSON file.

## Status

The app builds and its unit tests pass. Codes are labelled by how much evidence exists that they work; most have **not** been confirmed on physical devices by this project yet. See [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md) and [`docs/HARDWARE_TESTING.md`](docs/HARDWARE_TESTING.md). Reports of what works on your devices are very welcome; please open an issue.

## Licensing

- `ir-core/` is **LGPL-2.1-or-later** because it contains ports of LGPL-2.1 code (IRremoteESP8266).
- Bundled code data comes from SmartIR (MIT), Flipper-IRDB (CC0), Linux kernel remote keymaps (GPL-2.0) and others. Every source and its obligations are listed in [`NOTICE.md`](NOTICE.md), and the license texts are in [`licenses/`](licenses/).
- No license has been chosen yet for the `app/` module itself.

## Author

Made by **[Abdullah Sheikh Khalil](https://abedallask.netlify.app/)**, Full‑Stack Developer · Cyber Security.
