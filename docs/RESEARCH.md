# Research and provenance report

Inspected 2026-10-26. Every repository below was cloned and its source read; claims come from the code or real captures, not READMEs alone.

## 1. Repositories inspected

| Repository | Commit / last update | License | Reusable? | Needs ext. hardware? | What it is | Used for |
|---|---|---|---|---|---|---|
| [crankyoldgit/IRremoteESP8266](https://github.com/crankyoldgit/IRremoteESP8266) | `1e2f0f3`, 2026-08-23 | LGPL-2.1 | Yes, with LGPL obligations | ESP8266/ESP32 C++ (algorithms are portable) | ~100 protocols incl. Electra (Alpha), Gree, Coolix, Kelon/Kelon168, Midea, Haier | Ported Electra 104-bit, Gree, Coolix; test vectors; brand list |
| [Aikidyst/kelon168-ir-homeassistant](https://github.com/Aikidyst/kelon168-ir-homeassistant) | `5335416`, 2026-06-05 | MIT | Yes | Tuya blaster (Python generator is portable) | Reverse-engineered KELON168 (Kelon OEM sold as Electra/Tadiran) | KELON168 encoder and its 10 byte vectors |
| [arikfe/IRTadiran](https://github.com/arikfe/IRTadiran) | `033ac2c`, 2025-09-08 | MIT | Yes | ESP8266 (Sming) | `IRTadiran` (64-bit) and `IRTadiranSakal` | Tadiran 64-bit encoder; showed Sakal = Gree |
| [nryhbhue/Electra-AC-Remote](https://github.com/nryhbhue/Electra-AC-Remote) | `a11479c`, 2017-10-18 | Unlicense | Yes | Raspberry Pi / ESP8266 | 34-bit Manchester Electra encoder | Electra 34-bit encoder |
| [danielrosehill/electra-central-ac-ir-codes](https://github.com/danielrosehill/electra-central-ac-ir-codes) | `a608761`, 2026-07-21 | MIT | Yes | Broadlink | Decoded protocol + 8 real captures of one ducted Electra/Tadiran unit, verified by author on hardware | Reference vectors for Electra 34-bit |
| [zephyrx47/SmartIR](https://github.com/zephyrx47/SmartIR) | `282d4b1`, 2022-02-19 | MIT | Yes | Broadlink/Xiaomi/ESPHome (codes are portable) | 237 climate, 34 media, 14 fan captured code tables | Bundled 124 files; reference captures for 6 protocols |
| [probonopd/irdb](https://github.com/probonopd/irdb) | `11aa5eb`, 2026-01-15 | Custom (notify project, notice, free copies) | Not without complying | No | protocol/device/subdevice/function CSVs | Importer for user-supplied files only |
| [Lucaslhm/Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB) | `d126fb1`, 2026-04-11 | CC0-1.0 only after commit `2319685` | Per-file, uncertain for older files | Flipper Zero (files portable) | ~9000 `.ir` files (parsed + raw) | Importer; 2 test fixtures |
| [Arduino-IRremote/Arduino-IRremote](https://github.com/Arduino-IRremote/Arduino-IRremote) | `c4ec014`, 2026-10-10 | MIT | Yes | Arduino | Consumer protocols | Timing reference |

### Chinese-language search (GitHub + Gitee)

**Gitee:** search pages are JavaScript-only and the API v5 search returned nothing, so Gitee could not be searched. Only named Gitee repos were readable. **GitHub:** searches for 万能遥控器 Kotlin, 海尔 and 志高 returned 0 hits.

| Repository | License | Finding |
|---|---|---|
| github.com/irext (core, database) | MIT | Only large offline Chinese AC code library (Gree, Midea, Haier, Chigo, AUX, Kelon…). Data provenance undocumented; author claims "well verified" without captures. **Candidate for a future native (JNI) importer**, not integrated. |
| anadav/tadiran-remote | MIT | Android app for Tadiran TAC 297 (KELON168-like); tested on one unit. Not used. |
| tony-greel/UniversalRemoteControl | MIT | TCL112AC encoder only. Not used. |
| huangqiabc4/android-AC-remote | MIT file, README says derived from IRremoteESP8266 | License conflict risk; not used. |
| iodn/android-ir-blaster | GPL-3.0 | Not compatible unless the app becomes GPL. |
| Genesys225/esphome-components (Tornado), pkomisar/esphome-amcor-tadiran | GPL-3.0 | Spec reference only; not used. |
| ysard/mi_remote_database, halifox/android_xiaomi_ir_lab | AGPL / none | Xiaomi-derived proprietary data. **Excluded.** |
| ViserionT/IRRemote, yzzenga/AnyRemote | No LICENSE file | **Excluded** (not reusable). |

## 2. Key findings from the code and captures

1. **IRTadiranSakal is the Gree protocol.** Its 9-byte buffer is the Gree 8-byte state with the 3-bit `010` block footer embedded after bit 32, and its 35-bit / ~20 ms split is Gree's block structure. SmartIR 1342 (Tadiran 10i/15i/inv220a) frames are reproduced byte-exactly by the Gree encoder (113/113).
2. **The IRTadiran empirical checksum is right for "on" frames.** It matches 135/136 SmartIR 1340 frames, 89/91 of 1341 (TAC490) and 135/136 of 1560 (WIND 3P). Off frames (byte5 = `0xC0`) need **formula − 0x87**, a rule derived here that reproduces IRTadiran's hard-coded off frame and both captured off frames.
3. **Electra-AC-Remote (2017) and electra-central-ac-ir-codes (2026) are the same 34-bit Manchester protocol**, found independently. The encoder matches all 7 central-AC state captures bit-for-bit.
4. **KELON168:** Aikidyst's byte tables reproduce their own vectors, and the framing matches IRremoteESP8266's independent `IRKelon168Ac`. SmartIR 1621 (Tornado Super Legend 40) has the same framing and both checksums on 118/118 clean frames, **but bytes 6/7 differ**, so the tables are not confirmed for Tornado.
5. **Fingerprints of SmartIR Israeli files:**

| SmartIR file | Brand / model | Protocol (evidence) |
|---|---|---|
| 1340, 1560 | Tadiran Unknown, WIND 3P | Tadiran 64-bit (bytes + checksum match) |
| 1341 | Tadiran TAC490 | Tadiran 64-bit |
| 1342, 1343 | Tadiran 10i/15i/inv220a, Alpha | Gree |
| 1620 | Tornado Super Inverter A/i… | Coolix (all correctly labelled codes match) |
| 1621 | Tornado Super Legend 40 | KELON168 framing (bytes 6/7 differ) |
| 1940 | Electra Unknown | Electra 34-bit Manchester (3000 µs header) |
| 1941, 1943 | Electra Unknown / iGo | Coolix-like header, **not verified** |
| 1942 | Electra Classic | Electra 104-bit (199/203 checksums valid) |
| 1944 | Electra Platinum Plus Inverter | Coolix (all correctly labelled codes match) |

6. **SmartIR data errors found** (handled explicitly in tests, not hidden): 1620 stores the AUTO fan code under "low"; 1944 swaps its heat/auto 17 °C and 18 °C codes; 1340 has one "17 °C" entry holding an 18 °C code; 1341 has one heat entry with dry-mode bytes; 1942 has 4 corrupt captures.
7. **The real Tadiran Gree remote keeps the YAW1F ModelA bit set in OFF frames.** IRremoteESP8266 clears it; the app follows the capture (the bit is outside the checksum).

## 3. Decisions

- Kotlin port of each protocol (no NDK): every protocol is small and deterministic.
- The `ir-core` module is LGPL-2.1-or-later (see NOTICE.md).
- Bundle only SmartIR (MIT). IRDB and Flipper are import-only.
- Captured community codes are labelled **Level 0** until a user confirms them; encoders get Level 1/2 according to the evidence above. Nothing is Level 3/4 until a user confirms it on hardware.
