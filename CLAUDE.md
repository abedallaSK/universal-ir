# CLAUDE.md — Universal IR (Android)

Native Kotlin / Jetpack Compose universal remote. Controls devices two ways:
**infrared** (phone's built-in IR emitter, offline) and **Wi-Fi** (TVs and Gree-protocol ACs on the same network).

## Build & test (bash, from the repo root)

```bash
./gradlew :ir-core:test :app:testDebugUnitTest   # JVM tests
./gradlew :app:compileDebugKotlin                # fast compile check
./gradlew :app:assembleDebug                     # APK → app/build/outputs/apk/debug/app-debug.apk
```

- JDK 17+, Android SDK 36 (`local.properties` points at the SDK). minSdk 26, target/compile 36.
- Never run two Gradle builds at once in this checkout: they race on `ir-core/build` and produce bogus "Unresolved reference" errors.

## Layout

```
ir-core/              Pure Kotlin/JVM (LGPL-2.1-or-later). Timing model, validation, protocol encoders
                      (AC: kelon168, tadiran64, gree64, electra_ac104, electra34, coolix24; consumer: NEC,
                      NECext, Samsung32, Sony, RC5, RC6), importers, canonical format, DeviceCategory.
app/src/main/java/com/universalir/
  MainActivity.kt     Theme bootstrap: reads stored theme before first frame, syncs AppCompat night
                      mode + system-bar icon style with the in-app Light/Dark/System choice.
  app/navigation/     Routes + NavHost (single activity).
  core/theme/         Palette, full M3 light/dark color schemes (every role set — missing roles fall
                      back to M3's purple defaults, which is what broke dark mode before), spacing, remote metrics.
  core/ui/            AppIcons.kt (hand-drawn SVG-path icon set + device avatars/gradients),
                      Components.kt (AppScaffold, RemoteButton, StatusPanel, dialogs), Messages.kt.
  core/ir/            ConsumerIrManager wrapper (sealed results, never reports false success).
  data/               Room DB (schema exported, explicit migrations only), DataStore settings,
                      catalog seeder, repositories. data/wifi/ = LAN discovery + Wi-Fi controllers.
  domain/             Models (DeviceSettings JSON per device), RemoteController, CodeTester.
  feature/            One package per screen: home, devices, remote, acremote, wifi, devicebrowser,
                      codewizard, customremote, rooms, favorites, settings, database, codeexplorer, diagnostics, about.
tools/                build_catalog.py (bundles code sets), strings_source.py + gen_strings.py.
docs/                 RESEARCH.md, COMPATIBILITY.md, HARDWARE_TESTING.md.
```

## Conventions that matter

- **Strings:** never edit `res/values*/strings.xml` by hand. Add `key: (English, Arabic, Hebrew)` to
  `tools/strings_source.py`, then run `python -I tools/gen_strings.py`. Format args must match across languages.
  RTL (ar/he) must work: model numbers/hex/timings use `LtrText`; D-pad left/right and media transport are not mirrored.
  Exception: the Wi-Fi feature keeps its own text in `feature/wifi/WifiStrings.kt`.
- **Icons:** add new glyphs to `AppIcons` as 24×24 SVG path strings (stroke, round caps; tinted by `Icon`).
  Device icon/color choices live in `deviceIconChoices` / `deviceGradients`; per-device picks are stored
  in `DeviceSettings.icon` / `.color` / `.tileSize` / `.showOnHome` (JSON in `saved_devices.customSettings`,
  so no Room migration is needed for appearance fields).
- **Per-device settings:** change them with `DeviceRepository.updateSettings(id) { it.copy(...) }`.
- **Device management UX:** long-press any device tile/row → `DeviceActionsHost` bottom sheet
  (favorite, icon/color/size, rename, room, arrange, show/hide on home, duplicate, delete). There is no
  overflow menu on tiles. Reordering is drag-and-drop in `ArrangeGrid` (hold, then drag).
- **Home page** is user-customizable (title, tiles/list, sections, which devices) via the Tune button;
  prefs live in `AppSettings.home*` (DataStore).
- **Remote screens** pick a layout from the code set's command names (`CommandNames`). Any command a
  layout doesn't place appears under "All other buttons", so nothing is ever unreachable.
  LED lights: commands `color:<name>`, `effect:<name>`, `brightness_up/down`, `power_on/off`.
- **User-facing copy:** keep it short. No explanatory "this isn't tested / last requested state" notes,
  no source/license lines in everyday screens. Technical detail (sources, licenses, evidence levels,
  protocol diagnostics, code explorer, database) lives under Settings › Advanced and About.
- **Licensing:** `ir-core` is LGPL-2.1-or-later. Bundled code data must have a redistribution-friendly
  license (`LicensePolicy`); record every source in `NOTICE.md`.
- **Hardware honesty stays in code:** transmit results are sealed types; never show success unless Android accepted the pattern.

## Wi-Fi remotes (data/wifi, feature/wifi)

- Discovery: SSDP (with MulticastLock), mDNS (NsdManager), Gree UDP scan on port 7000; ~6 s; dedupe by IP.
- Control: Roku ECP (HTTP 8060), Samsung Tizen (wss 8002 token / ws 8001), LG webOS (ws 3000 / wss 3001,
  client-key pairing), Gree-protocol ACs (AES, reads the real state), Android TV (see below). Cast is listed but not controllable.
- Self-signed TV certs are trusted only on the Wi-Fi OkHttp client and only for private IPv4 hosts.
- `usesCleartextTraffic="true"` is required for LAN HTTP/ws.
- **Android TV / Google TV** (`AndroidTvRemote.kt`, pure protocol in `AtvProtocol.kt`/`AtvCertificate.kt`): Remote v2 —
  pairing on 6467 (6-hex code typed in-app, SHA-256 secret as androidtvremote2), remote on 6466 (configure/set_active,
  ping replies, key inject, app links, volume/power state). Client identity = software RSA-2048 + hand-built self-signed
  X.509, stored sealed in `WifiDeviceStore` (`atv_identity`). `SavedWifiDevice.paired` gates connecting.
- **ACs share one UI** via `WifiAcController` (`WifiAc.kt`): Gree/Tadiran (LAN), Daikin BRP069/BRP072A local HTTP
  (`DaikinController.kt`, UDP 30050 discovery), Electra Smart cloud (phone → SMS OTP) and Sensibo (user API key)
  in `CloudAcControllers.kt`. Cloud protocols have `WifiProtocol.cloud = true` and no IP.
- **Secrets at rest:** tokens/keys in `SavedWifiDevice` are moved into `sealedSecrets` (AES-GCM, KeyStore key,
  `SecretVault`) on write and restored on read; never write account credentials in plain text.
- Wi-Fi screen: top-bar "+" → Add by IP (`WifiDiscovery.probe`) or Add Wi-Fi AC by account (`WifiAccountSheet.kt`);
  long-press a saved Wi-Fi device → options sheet. Tests: `AndroidTvProtocolTest`, `WifiAcCodecTest`.

## Status

Builds and JVM tests pass. IR and Wi-Fi control have not been confirmed on physical devices by this project
— see docs/HARDWARE_TESTING.md before claiming something works.

## Widgets (app/src/main/java/com/universalir/widget, Jetpack Glance)

- **Device remote** (`DeviceRemoteWidget`, resizable 2×2 → ~4×3): bound to one saved device chosen in
  `WidgetConfigActivity` (stored as `DeviceRemoteWidget.DEVICE_ID` in Glance per-widget prefs; reconfigurable).
  Command sets show buttons from `WidgetButtons` (only commands the set has; D-pad on tall widgets; LED colour dots).
  ACs show target temp/mode and Power, Temp −/+ via `AcAction`, reading/saving `DeviceSettings.acState` /
  `lastSentAcState` with the same `domain/usecase/AcStateRules` (capabilities, coerce, temperature step) as the AC screen.
- **Favorites** (`FavoritesWidget`): favorite commands / AC presets as one-tap chips + favorite devices as avatar tiles.
- Sends go through `RemoteController` in `ActionCallback`s (deps via the Hilt `WidgetEntryPoint`); failures show a Toast
  with `describeOutcome`. Tapping a header/tile opens `MainActivity` with Long extra `Widgets.EXTRA_DEVICE_ID`.
- Call `Widgets.refreshAll(context)` after devices/favorites/settings change so placed widgets redraw.
- Glance can't draw `ImageVector`: icons are `res/drawable/widget_ic_*.xml`, generated from the `AppIcons` paths
  (keep them in sync when icons change); avatar gradients are `widget_grad_0..7` (mirror `deviceGradients`).
- Colours come from `WidgetTheme` (app palette, follows the in-app Light/Dark/System choice).
