# Notices and licenses

Universal IR contains or is derived from the following third-party work. Full license texts are in [`licenses/`](licenses/).

## Code ported into `ir-core`

| Component | Source (commit inspected) | License | What was used |
|---|---|---|---|
| Electra 104-bit, Gree, Coolix encoders (`ElectraAcProtocol.kt`, `GreeProtocol.kt`, `CoolixProtocol.kt`) | [IRremoteESP8266](https://github.com/crankyoldgit/IRremoteESP8266) `1e2f0f3` — © David Conran and contributors | **LGPL-2.1** ([text](licenses/IRremoteESP8266-LGPL-2.1.txt)) | Bit layouts, constants, checksum algorithms, timing constants, test vectors |
| KELON168 encoder (`Kelon168Protocol.kt`) | [kelon168-ir-homeassistant](https://github.com/Aikidyst/kelon168-ir-homeassistant) `5335416` — © 2026 Aikidyst | MIT ([text](licenses/kelon168-ir-homeassistant-MIT.txt)) | Byte tables, checksums, power sequences, self-test vectors |
| Tadiran 64-bit encoder (`TadiranProtocol.kt`) | [IRTadiran](https://github.com/arikfe/IRTadiran) `033ac2c` — © 2018 Arik Felzenstien | MIT ([text](licenses/IRTadiran-MIT.txt)) | Byte layout, timings, empirical checksum |
| Electra 34-bit Manchester encoder (`Electra34Protocol.kt`) | [Electra-AC-Remote](https://github.com/nryhbhue/Electra-AC-Remote) `a11479c` | Unlicense ([text](licenses/Electra-AC-Remote-Unlicense.txt)) | Field layout and Manchester timing |
| Consumer protocols (NEC, Samsung32, SIRC, RC5, RC6) | Protocol timing documented by [Arduino-IRremote](https://github.com/Arduino-IRremote/Arduino-IRremote) (MIT) and IRremoteESP8266 | — | Timing constants only (independent implementation) |

### LGPL-2.1 obligations

Because three encoders are ports of LGPL-2.1 code, the **`ir-core` module is distributed under LGPL-2.1-or-later**. It is a separate Gradle module (`ir-core/`) built into its own JAR, so it can be replaced by a modified version. Anyone distributing the app must:

- ship this NOTICE and `licenses/IRremoteESP8266-LGPL-2.1.txt`,
- provide the `ir-core` source (or a written offer for it), and
- allow users to relink with a modified `ir-core` (keep it a separately built library; do not obfuscate it into a form that prevents replacement — the release build currently minifies everything, so publish the `ir-core` source and build instructions alongside any release).

The rest of the app (`app/`) is licensed under the **GNU General Public License v3.0 or later** ([`LICENSE`](LICENSE)). All bundled data is compatible with it: GPL-2.0-only material (the GeekBox kernel keymap) is deliberately not bundled.

## Data bundled in the APK

| Data | Source | License |
|---|---|---|
| 260 captured code files in `app/src/main/assets/catalog/smartir/` (climate, media_player, fan): every Broadlink Base64/Pronto or ESPHome Raw file of the fork, all AC brands | [SmartIR](https://github.com/zephyrx47/SmartIR) `282d4b1` — © 2019 Vassilis Panos and contributors | MIT ([text](licenses/SmartIR-MIT.txt); also bundled as `assets/catalog/smartir/LICENSE`) |
| 140 captured code files in `app/src/main/assets/catalog/smartir_upstream/` (113 AC, 22 media_player, 2 fan, 3 light) that are not already bundled from the fork above | [SmartIR](https://github.com/smartHomeHub/SmartIR) `e4df295` — © 2019 Vassilis Panos and contributors | MIT (bundled as `assets/catalog/smartir_upstream/LICENSE`) |
| 21 captured code files in `app/src/main/assets/catalog/smartir_litinoveweedle/` (13 AC, 4 media_player, 3 fan, 1 light) that are not already bundled from the two repositories above | [SmartIR, litinoveweedle fork](https://github.com/litinoveweedle/SmartIR) `cbccf9c` — © 2019 Vassilis Panos, 2024 Li Tin O've Weedle | MIT ([text](licenses/SmartIR-litinoveweedle-MIT.txt); also bundled as `assets/catalog/smartir_litinoveweedle/LICENSE`) |
| 25 files in `app/src/main/assets/catalog/flipper/`: TV boxes MAG, Kaleidescape Strato V, Tesla TE-300, Hilton Connected Room; TVs Continental Edison CELD55SQLDV24B6, Grundig TP750C; ACs Daikin ARC480A41, Panasonic CS-UE12RKE; audio Denon DRA-365R, Fosi ZD3 and ZH3, Nakamichi RM-4TA, Philips HTL2060, Ultimea Poseidon M20, Roksan K3 amp and DAC; projectors NEC RU-M124, Sony RM-PJ1000; fans Domo DO8149, Dyson TP7A and PH04, Klarstein Sommerwind, Mitsubishi R30J-HRV, ProBreeze PBF04; light Tween Light Oria. A few button names are renamed and one file's leading comment block is moved below its header (see `FLIPPER_RENAMES` / `header_first` in `tools/build_catalog.py`). | [Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB) `d126fb1`. These are all the files of a supported device type whose **entire** `git log --follow` history descends from the license commit `2319685`; `tools/build_catalog.py` re-checks every commit of each file. | CC0-1.0 ([text](licenses/Flipper-IRDB-CC0-1.0.txt); also bundled as `assets/catalog/flipper/LICENSE-CC0-1.0.txt`) |
| 18 TV-box remote tables in `app/src/main/assets/catalog/generated/kernel-*.json`, converted by `tools/build_catalog.py` from `drivers/media/rc/keymaps/`: `rc-x96max.c`, `rc-tanix-tx3mini.c`, `rc-tanix-tx5max.c`, `rc-beelink-gs1.c`, `rc-beelink-mxiii.c`, `rc-mecool-kii-pro.c`, `rc-mecool-kiii-pro.c`, `rc-videostrong-kii-pro.c`, `rc-minix-neo.c`, `rc-khadas.c`, `rc-odroid.c`, `rc-wetek-hub.c`, `rc-wetek-play2.c`, `rc-vega-s9x.c`, `rc-pine64.c`, `rc-hisi-poplar.c`, `rc-dreambox.c` (split into its two remotes) | [Linux kernel](https://github.com/torvalds/linux/tree/v7.3/drivers/media/rc/keymaps) tag `v7.3`, commit `c03c1a14507d`. Each file's author is named in its header. | GPL-2.0-or-later ([text](licenses/Linux-kernel-GPL-2.0.txt)). Recorded per code set as "GPL-2.0-or-later (Linux kernel, v7.3)". |
| 5 protocol tables in `app/src/main/assets/catalog/generated/led-*.json` (LED strip / bulb remotes: 24-key NECext 0xEF00, 44-key NEC 0x00, three 24-key NEC 0x00 bulb layouts), generated by `tools/build_catalog.py` | Factual NEC address/command values. 24-key 0xEF00: user-supplied table, On/Off/Brightness confirmed by SmartIR (MIT, [litinoveweedle fork](https://github.com/litinoveweedle/SmartIR) `cbccf9c`) `light/1070` Aconic 24-key captures. All tables cross-checked against captures in Flipper-IRDB `d126fb1` `LED_Lighting/` (files added before the CC0 commit, so **no Flipper file or capture is bundled**; only agreeing address/command numbers were used, each table's evidence is stored with it). | No third-party file bundled |
| 15 full-remote protocol tables in `app/src/main/assets/catalog/generated/fullremote-*.json` (TVs: Samsung, LG, Sony, Philips, Toshiba, Hisense, Vizio, Hitachi, Insignia, Roku TV, Fire TV Edition, Vestel-made; Apple TV; LG and Sony soundbars), generated from `TV_REMOTES` in `tools/build_catalog.py` | Factual protocol/address/command values. Each key was kept only when at least two independent captures agree and outvote any other value 2:1, counted across [Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB) `d126fb1` (`TVs/`, `Streaming_Devices/`, `SoundBars/`) and [probonopd/irdb](https://github.com/probonopd/irdb) `11aa5eb` (`codes/<brand>/`, JP1 and LIRC-derived tables). **No Flipper or IRDB file is bundled or downloaded**; only the agreeing numbers are used, and each set stores its sources and per-key agreement counts. | No third-party file bundled |

### GPL-2.0 data in the app module

The project owner accepted the Linux kernel RC keymaps as a TV-box code source. As a result **the `app` module now bundles GPL-2.0 data**: the `kernel-*.json` code sets listed above. They are scancode tables converted to this app's JSON format. Kernel key codes are mapped to the app's command names, and unmapped keys keep their kernel names. No kernel source code is included. Anyone distributing the APK should treat these files as GPL-2.0 works:

- ship this NOTICE and `licenses/Linux-kernel-GPL-2.0.txt`,
- keep each set's source path and kernel tag (stored in every set's `source` field), and
- offer the corresponding source. That source is the kernel files at tag `v7.3` plus `tools/build_catalog.py`, which converts them.

`LicensePolicy` allows exporting and copying these sets, with attribution.
Two keymaps were not converted: `rc-hisi-tv-demo.c` and `rc-zx-irdec.c`. Their scancodes carry no NEC address, so the full IR frame cannot be rebuilt.

### What the catalogue build skipped (for the record)

- SmartIR files of other controllers (Xiaomi, LOOKin, UFOR11, ZHA, MQTT): they store compressed or vendor formats, not timings.
- SmartIR files over the importers' 2 MB cap (`climate/1129` in every fork), invalid JSON (`climate/2680` in zephyrx47 and upstream, upstream `media_player/1440`, `light/1020`), and files whose codes are mostly Broadlink RF or truncated packets (fans `1000`, `1020`, `1040`, `1041`, `1080`, `1081`, `1300`; light `1040`). Six sets listed by catalogue version 3 are therefore gone: `smartir-climate-1129` and `smartir-fan-1000`, `1020`, `1040`, `1080`, `1081` (they never loaded, or loaded a single code).
- 17 distinct SmartIR AC tables in Fahrenheit (35 files across the three repositories, e.g. `climate/1044`, `1105`, `1163`, `3200`): the AC screen treats table temperatures as Celsius.
- A later-fork file with the same commands for the same manufacturer, or with the same file number, manufacturer and models (a later revision of an already bundled capture); the build log lists each one.
- Flipper-IRDB: 110 of the 143 files added after the license commit have at least one commit (or a `--follow` predecessor) that does not descend from `2319685`, so they are not bundled. Among them: `TVs/Crown/Crown_RC647340.ir`, `TVs/Samsung/Samsung_BN59-01330C.ir`, `ACs/Electra/electra_smart.ir`, `ACs/Toshiba/Toshiba_RASB10BKVG-E.ir`. `Projectors/Epson/Epson-EB-X12.ir` is not a Flipper IR file (header "Bruce IR File"). Fireplace, toy, vacuum, VCR and HDMI-switch files are not supported device types.
- IRDB (probonopd), LIRC remote files and universal-remote setup-code lists: not bundled (license terms, or no code data).

### Fujicom

No Fujicom IR capture, code table, remote model number or chassis/ODM identification was found (Fujicom Israel product pages, Flipper-IRDB at `d126fb1`, IRDB, the three SmartIR repositories, Linux keymaps, web searches). Fujicom is therefore listed with **unverified** wizard candidates: the 6 "Generic Android TV" code sets (SmartIR TCL 55EP640, 43S6500FS, 55C845, Xiaomi Mi Box / Mi TV, Kivi 40F600GU; Flipper Continental Edison CELD55SQLDV24B6), each re-listed as `fujicom-candidate-*` with an "UNVERIFIED" evidence string. No extra data is bundled for them.

## Test fixtures (not shipped in the APK)

| Fixture | Source | License |
|---|---|---|
| `ir-core/src/test/resources/smartir/*.json` | SmartIR | MIT |
| `ir-core/src/test/resources/electra_central/*.b64` | [electra-central-ac-ir-codes](https://github.com/danielrosehill/electra-central-ac-ir-codes) `a608761` — © 2026 Daniel Rosehill | MIT ([text](licenses/electra-central-ac-ir-codes-MIT.txt)) |
| `ir-core/src/test/resources/flipper/*.ir` | [Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB) `d126fb1` | CC0-1.0 applies only to commits after `2319685`. These two files' history could not be checked (shallow clone), so they are kept as test fixtures only. |

## Formats supported but not bundled

- **IRDB** ([probonopd/irdb](https://github.com/probonopd/irdb)): its license requires opening an issue with the project, including a notice, and providing free copies on request. The app only imports IRDB CSV files the user supplies; it neither bundles nor downloads IRDB.
- **Flipper-IRDB**: the user imports `.ir` files themselves. Only files whose entire history is after the CC0 license commit `2319685` are bundled (currently 25). Every Chinese Android-box file present at `d126fb1` (`TVs/Android/Android_TV_MXQ.ir`, `Streaming_Devices/Mecool/Mecool_KM6.ir`, `Streaming_Devices/Xiaomi/Xiaomi_MiBox1.ir`, `Xiaomi_MiBox2.ir`, `Streaming_Devices/Bqeel/Bqeel_R1-Plus.ir`, `_Converted_/IR_Plus/B/BEELINK/GT1 Ultimate Android.ir`) predates it and is not bundled.

## Android libraries

AndroidX, Jetpack Compose, Material 3, Hilt/Dagger, Room, DataStore, kotlinx.serialization and kotlinx.coroutines are Apache-2.0.
