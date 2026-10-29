# Protocol compatibility matrix

Levels: **0** source discovered · **1** encoder implemented · **2** waveform validated (reference vectors / real captures) · **3** hardware tested · **4** features tested.
No protocol is at level 3 or 4: none has been confirmed on a physical AC/TV by this project yet. In the app, a profile is promoted to level 3 only when a user answers "Yes, it responded" in the wizard or the Test dialog.

## Air-conditioner encoders (complete state)

| Protocol id | Carrier | Frames | Power | Modes | Temp | Fan | Swing | Options | Level | Evidence |
|---|---|---|---|---|---|---|---|---|---|---|
| `kelon168` | 38 kHz | 3 frames (6/8/7 bytes), 343 entries, ≈0.4 s; on/off = 2 messages | Toggle, forced by TURBO sequence | Cool, Heat, Dry, Fan | 16–30 | Auto/L/M/H (Dry: auto) | — | Turbo, I Feel | 2 | 10 vectors from kelon168.py; IRremoteESP8266 framing; Tornado 1621 framing/checksums |
| `tadiran64` | 38 kHz | 8 bytes ×2, inverted pulse-width | Absolute | Cool, Heat, Fan | 16–30 | L/M/H | Vertical on/off | — | 2 | SmartIR 1340/1341/1560 byte-exact |
| `gree64` | 38 kHz | 4 B + footer + 20 ms + 4 B | Absolute | Auto, Cool, Dry, Fan, Heat | 16–30 | Auto/L/M/H | Vertical auto | Turbo, Light, Sleep | 2 | IRremoteESP8266 vector; SmartIR 1342 113/113 byte-exact |
| `electra_ac104` | 38 kHz (upstream guess) | 13 bytes | Absolute | Auto, Cool, Dry, Heat, Fan | 16–32 | Auto/L/M/H | V & H on/off | Turbo, Quiet, I Feel | 2 | IRremoteESP8266 vector + issue #778 captures; SmartIR 1942 checksums. **Upstream status Alpha.** |
| `electra34` | 38 kHz | 34-bit Manchester ×3, ≈0.23 s | Pure toggle (assumed state, user-correctable) | Cool, Heat | 16–30 | Auto/L/M/H | Vertical on/off | Power-toggle-only command | 2 | 7 central-AC captures bit-exact; two independent sources |
| `coolix24` | 38 kHz | 24 bits + inverse, ×2 | Absolute (fixed OFF code) | Auto, Cool, Dry, Heat, Fan | 17–30 | Auto/L/M/H | Toggle command only | — | 2 | SmartIR 1620/1944 match all correctly labelled codes |

All AC messages are well under Android's 2 s limit. Multi-message sequences (KELON168 power, Electra 34 power-on) are separate `transmit()` calls at protocol message boundaries; nothing is ever cut mid-message.

## Brand ↔ protocol links (with evidence)

| Brand | Protocol | Models / remotes | Level for this brand | Evidence |
|---|---|---|---|---|
| Electra | electra_ac104 | Electra Classic, Electra Smart (YKR-M/003E) | 2 | IRremoteESP8266 list; SmartIR 1942; Flipper electra_smart.ir |
| Electra | electra34 | Split and central units | 2 | Electra-AC-Remote; central captures; SmartIR 1940 |
| Electra | coolix24 | Platinum Plus Inverter | 2 | SmartIR 1944 |
| Electra | kelon168 | Kelon-made units | 2 | kelon168-ir-homeassistant |
| Tadiran | tadiran64 | TAC490, WIND 3P, others | 2 | IRTadiran; SmartIR 1340/1341/1560 |
| Tadiran | gree64 | 10i, 15i, inv220a, Alpha ("Sakal") | 2 | IRTadiranSakal; SmartIR 1342/1343 |
| Tadiran | kelon168 | Kelon-made units | 2 | kelon168-ir-homeassistant |
| Tadiran | electra34 | Central (ducted) | 2 | electra-central-ac-ir-codes (one unit) |
| Tornado | coolix24 | Super Inverter A/i, Super Design, Plasma, Gold i | 2 | SmartIR 1620 |
| Tornado | kelon168 | Super Legend 40 | 1 | Framing only; use the captured SmartIR 1621 set |
| Kelon | kelon168 | DG11R2-01, RCH-R0Y3 | 2 | IRremoteESP8266 list + vectors |
| Hisense | kelon168 | AST-09UW4RVETG00A | 1 | IRremoteESP8266 list; SmartIR 1521/1522 framing |
| Gree | gree64 | YAW1F/YBOFB/YB1F2 | 1 | IRremoteESP8266 list |
| Tosot | gree64 | T09H-SJ | 0 | SmartIR 1200 fingerprint |
| Midea | coolix24 | RG52D/BGE | 1 | IRremoteESP8266 list; SmartIR 1380–1385 fingerprint |
| Beko | coolix24 | RG57K7(B)/BGEF | 1 | IRremoteESP8266 list |
| AUX | electra_ac104 | YKR-T/011 | 1 | IRremoteESP8266 list; SmartIR 1960 fingerprint |
| Electrolux | electra_ac104 | YKR-H/531E | 1 | IRremoteESP8266 list |

Haier, Chigo, TCL, Samsung, LG, Daikin, Mitsubishi Electric and most Midea/Gree models are available only as **captured SmartIR tables (level 0)**. Their native protocols (Haier AC, TCL112, Daikin, Mitsubishi…) are not yet implemented.

Catalogue version 4 bundles 343 captured SmartIR AC tables (level 0) from three MIT repositories (zephyrx47 fork, smartHomeHub upstream, litinoveweedle fork) covering about 120 brands, including Fujitsu, Hitachi, Panasonic, Mitsubishi Heavy, Carrier, Toshiba, Sharp, Hyundai, Beko, Electrolux. Their timing fingerprints are listed per set (gree64 31, coolix24 34, electra_ac104 26, kelon168 12, tadiran64 5, electra34 3). Fahrenheit tables are not bundled. Swing-aware tables that nest `mode/fan/swing/temp` load the first swing setting of each temperature (importer fix in `SmartIrClimateImporter`; before it, 47 such tables loaded only "off"). Two Flipper-IRDB AC remotes (Daikin ARC480A41, Panasonic CS-UE12RKE) are button remotes and open on the command screen, not the AC screen.

## Consumer protocols (TV, audio, projector, fan)

| Protocol | Carrier | Level | Notes |
|---|---|---|---|
| NEC, NECext, Samsung32 | 38 kHz | 2 | Payload layout unit-tested; Flipper Samsung file imports and encodes |
| Sony SIRC 12/15/20 | 40 kHz | 2 | 3 frames per press, 45 ms period |
| RC5 / RC5X | 36 kHz | 2 | Manchester 889 µs |
| RC6 mode 0 | 36 kHz | 2 | |
| Kaseikyo, RCA, Pioneer, NEC42 | — | not implemented | Importer reports these as unsupported |

## TV boxes from Linux kernel keymaps

There are 19 code sets converted from `drivers/media/rc/keymaps` (Linux v7.3, GPL-2.0), all at level 0.

- NEC: X96 Max, Tanix TX3 mini, Beelink GS1 and Mini MXIII, Mecool KII Pro and KIII Pro, Videostrong KII Pro, Minix NEO, Khadas VIM/Edge, ODROID, WeTek Hub, Tronsmart Vega S95/S96, GeekBox, HiSilicon Poplar, and Dreambox RC10 and RC20.
- NECext: Tanix TX5 max, WeTek Play 2, Pine64.

Every set except the two Dreambox ones is also listed under the brand "Unknown / generic Chinese box".
The kernel's NEC scancode `0xAACC` becomes NEC address `0xAA`, command `0xCC`. The NECX scancode `0xAABBCC` becomes NECext address `0xBBAA`, command `0xCC | ~0xCC << 8`.

## Android TVs, "Generic Android TV" and Fujicom

| Code set | Source | Protocol | Level | Evidence |
|---|---|---|---|---|
| TCL 55EP640, 43S6500FS | SmartIR zephyrx47 `media_player/1180`, `1181` | captured | 0 | Android TV models |
| TCL 55C845 | SmartIR litinoveweedle `media_player/1182` | captured | 0 | Google TV model |
| Xiaomi Mi Box / Mi TV | SmartIR zephyrx47 `media_player/1380` | captured | 0 | Mi remote |
| Kivi 40F600GU | SmartIR upstream `media_player/1600` | captured | 0 | Android TV model |
| Continental Edison CELD55SQLDV24B6 | Flipper-IRDB (CC0) | NECext 0x7F00 | 0 | Android-TV style remote (Netflix, YouTube, Prime Video, Disney+, pair key) |

These six sets are also listed under the brand **Generic Android TV**, so the wizard can try them on an unlisted or rebadged Android TV.

**Fujicom** (Israeli budget brand; e.g. FJ-65F Android 9, FJ-43ZX650 Android 11): no IR capture, code table, remote model number or chassis/ODM was found for any Fujicom TV. Searched: Fujicom Israel product pages (no remote or SoC listed; the FJ-65F ships with a wireless air mouse), Flipper-IRDB `d126fb1` (no Fujicom file), IRDB, the three SmartIR repositories, Linux kernel keymaps, LIRC and web/forum searches (only universal-remote setup-code numbers, which identify no protocol). Fujicom is therefore linked to the six Generic Android TV sets as **unverified candidates** (`fujicom-candidate-*`, evidence string starting "UNVERIFIED"). Level 0, brand link unverified. Replace them with a real code set once a Fujicom remote is captured (e.g. with a Flipper Zero or IR receiver). Wi-Fi control (Android TV remote protocol) is the more reliable path for these TVs.

## Lights / LED strips (category LIGHT)

| Code set | Protocol | Evidence | Level |
|---|---|---|---|
| 24-key RGB remote | NECext 0xEF00, cmd 0x00–0x17 | SmartIR Aconic 24-key captures (on/off/brightness) + 43 Flipper captures agree | 1 |
| 44-key RGB strip remote | NEC 0x00, cmd 0x04–0x5D | Published table + 9 Flipper captures (play/pause 0x41 from one capture) | 1 |
| 24-key bulb (keys 0x04–0x1B), Osram/Varilux, Sylvania/Panasonic/Calex | NEC 0x00 | 2–3 agreeing Flipper captures each | 1 |
| SmartIR Iris Ohyama, NEC, Takizumi ceiling lights | captured | SmartIR (MIT) | 0 |
| SmartIR NEC RE0201-CH1 ceiling light; Flipper Tween Light Oria | captured / NEC | SmartIR (MIT), Flipper-IRDB (CC0) | 0 |

The wizard tests lights with `power_on` (then `power`, `brightness_up`).

## Not supported (by design)

- IR learning: the phone has no IR receiver.
- RF (433 MHz) Broadlink packets: rejected by the importer.
- Reading AC state: IR is one-way.
