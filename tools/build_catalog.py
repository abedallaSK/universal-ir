"""Builds app/src/main/assets/catalog from local SmartIR checkouts plus optional extra sources.

Usage: python -I tools/build_catalog.py <zephyrx47/SmartIR checkout> <out-dir>
           [--smartir-upstream <smartHomeHub/SmartIR checkout>]
           [--smartir-litinoveweedle <litinoveweedle/SmartIR checkout>]
           [--flipper-irdb <Flipper-IRDB checkout>] [--linux <Linux kernel checkout>]

Sources (each optional except the first; each entry records its own source and license):
  SmartIR forks (MIT)  climate / media_player / fan / light files from zephyrx47 (primary, ids
                      "smartir-*"), smartHomeHub upstream ("smartir-up-*") and litinoveweedle
                      ("smartir-lw-*"). A file is skipped when its commands equal a file already taken
                      from an earlier fork, or when an earlier fork has the same file number for the
                      same manufacturer and models (a later revision of the same capture).
  --linux             Linux kernel checkout (tag v7.3): TV-box remote keymaps in LINUX_KEYMAPS
                      (GPL-2.0), converted to canonical JSON.
  --flipper-irdb      Lucaslhm/Flipper-IRDB: CC0-1.0 applies only to files added after commit 2319685,
                      so only FLIPPER_FILES are copied and every commit of each is re-checked with git.
Protocol-defined LED remotes (LED_REMOTES) are always generated as canonical JSON.

Only Broadlink Base64/Pronto and ESPHome Raw files are copied (a phone cannot use other controller
formats). Files over the importers' 2 MB cap are skipped, and a file is bundled only when its codes
decode to valid IR patterns (Broadlink RF packets are rejected). Each AC file is fingerprinted by its
timing structure so the catalogue can state which protocol family the captured remote uses; the
fingerprint is evidence, not a guarantee.
"""
import base64
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys

# Bump when catalogue content changes; CatalogSeeder re-seeds when this (or its own logic) changes.
CATALOG_VERSION = 4

# Importer / validator limits (ir-core Importers.kt and PatternValidator.kt).
MAX_FILE_CHARS = 2_000_000
MAX_ENTRIES = 2048
MAX_PATTERN_US = 2_000_000

AUDIO_WORDS = ("yamaha", "pioneer", "jbl", "rme", "onkyo", "denon", "sound", "edifier", "kanto", "cambridge audio",
               "dialog", "logitech", "receiver", "speaker", "amplifier")
PROJECTOR_WORDS = ("optoma", "epson", "benq", "projector")
BOX_WORDS = ("sky", "pace", "xiaomi", "box")
# media_player files whose brand/model words do not reveal the device type: (manufacturer, first model) -> category.
MEDIA_CATEGORY = {
    ("samsung", "HT-J5500"): "AUDIO",       # home-theatre system
    ("jvc", "RX-5022R"): "AUDIO",           # AV receiver
    ("i-can", "4000S"): "TV_BOX",           # satellite receiver
}
# Obvious manufacturer typos / shouting in the source files -> display brand.
BRAND_FIXES = {
    "Grunding": "Grundig", "Ggeneral Electric": "General Electric", "Dalkin": "Daikin", "Hyndai": "Hyundai",
    "SHARP": "Sharp", "ROYAL": "Royal", "BAXI": "Baxi", "KOREL": "Korel", "ELGIN": "Elgin", "PARKAIR": "Parkair",
    "OPTOMA": "Optoma", "FUJIKO": "Fujiko", "Mitsubishi Heavy Industries": "Mitsubishi Heavy",
}

# Brand that groups every Chinese / generic Android or IPTV box set, so the code wizard can cycle
# through all of them when the user does not know the box's real make.
GENERIC_BOX_BRAND = "Unknown / generic Chinese box"
# Primary-source (zephyrx47) TV-box brands that are Chinese / generic Android boxes.
GENERIC_BOX_SOURCES = {"xiaomi"}
GENERIC_LED_BRAND = "Generic LED strip / bulb"
# Brand that groups the remotes of Android / Google TV sets (plus the Mi Box / Mi TV remote), so the
# wizard can try all of them for an unlisted or rebadged Android TV.
GENERIC_ANDROID_TV_BRAND = "Generic Android TV"
# (fork, sub-directory, file) of SmartIR sets for a TV that runs Android TV / Google TV.
ANDROID_TV_SMARTIR = {
    ("zephyrx47", "media_player", "1180.json"),       # TCL 55EP640 (Android TV)
    ("zephyrx47", "media_player", "1181.json"),       # TCL 43S6500FS (Android TV)
    ("zephyrx47", "media_player", "1380.json"),       # Xiaomi Mi Box / Mi TV
    ("upstream", "media_player", "1600.json"),        # Kivi 40F600GU (Android TV)
    ("litinoveweedle", "media_player", "1182.json"),  # TCL 55C845 (Google TV)
}

# Fujicom (Israeli brand) Android TVs. No capture, IR code table, remote model number or chassis/ODM
# identification was found for any Fujicom TV (see docs/COMPATIBILITY.md), so Fujicom gets one
# UNVERIFIED wizard candidate per Generic Android TV set. Replace with a real link once a Fujicom
# remote has been captured.
FUJICOM_BRAND = "Fujicom"
FUJICOM_EVIDENCE = (
    "UNVERIFIED candidate for Fujicom. No Fujicom remote capture, code table, remote model number or chassis/ODM "
    "was found (Fujicom Israel product pages, Flipper-IRDB, IRDB, SmartIR, Linux keymaps and web searches; "
    "universal-remote code lists only give vendor setup numbers). Fujicom sells budget Android TVs (e.g. FJ-65F "
    "with Android 9, FJ-43ZX650 with Android 11) on unnamed Chinese boards, so this Android-TV remote of another "
    "brand is offered for the code wizard to try. Not tested on a Fujicom TV."
)

SMARTIR_FORKS = {
    # fork -> (repository URL, id prefix, asset directory, display name)
    "zephyrx47": ("https://github.com/zephyrx47/SmartIR", "smartir", "smartir", "SmartIR (zephyrx47 fork)"),
    "upstream": ("https://github.com/smartHomeHub/SmartIR", "smartir-up", "smartir_upstream", "SmartIR (smartHomeHub)"),
    "litinoveweedle": ("https://github.com/litinoveweedle/SmartIR", "smartir-lw", "smartir_litinoveweedle",
                       "SmartIR (litinoveweedle fork)"),
}
SMARTIR_SUBS = (("climate", "AIR_CONDITIONER"), ("media_player", "TELEVISION"), ("fan", "FAN"), ("light", "LIGHT"))
# media_player files from a non-primary fork that are TV boxes: (fork, file) -> (brand, models).
SMARTIR_BOXES = {
    ("upstream", "7460.json"): ("ZTE", ["ZXV10 B860H (Android IPTV box)"]),
}

FLIPPER_URL = "https://github.com/Lucaslhm/Flipper-IRDB"
FLIPPER_LICENSE_COMMIT = "2319685f"
# Every file at d126fb1 whose whole history (git log --follow) is after the license commit, limited to
# device types this app has screens for. path -> (category, brand, models, extra brands).
# After the license but not bundled (unsupported device types): Fireplaces/Dimplex/Dimplex_Optiflame_Fireplace,
# Miscellaneous/OREI HDMI Switcher, Toys/Uncle_Miltons, Toys/WowWee-RoboSapien, VCR/LG/LG DVX7900,
# Vacuum_Cleaners/Tesla, Vacuum_Cleaners/iLife; Projectors/Epson/Epson-EB-X12.ir is not a Flipper IR file
# ("Filetype: Bruce IR File" header).
FLIPPER_FILES = {
    "Streaming_Devices/MAG/MAG_TV_Box.ir": ("TV_BOX", "MAG (Infomir)", ["MAG IPTV box"], [GENERIC_BOX_BRAND]),
    "Streaming_Devices/Kaleidescape/Kaleidescape_Strato_V.ir": ("TV_BOX", "Kaleidescape", ["Strato V"], []),
    "DVB-T/Tesla/Tesla_TE-300.ir": ("TV_BOX", "Tesla", ["TE-300 (DVB-T2 receiver)"], []),
    "TVs/Hotels/Hilton_Connected_Room_Remote.ir": ("TV_BOX", "Hilton", ["Connected Room remote (room media box)"], []),
    "TVs/ContinentalEdison/ContinentalEdison_CELD55SQLDV24B6.ir":
        ("TELEVISION", "Continental Edison", ["CELD55SQLDV24B6 (Android-TV style remote)"], [GENERIC_ANDROID_TV_BRAND]),
    "TVs/Grundig/Grundig_TP750C.ir": ("TELEVISION", "Grundig", ["TP750C remote"], []),
    "ACs/Daikin/Daikin_ARC480A41.ir": ("AIR_CONDITIONER", "Daikin", ["ARC480A41 remote (buttons)"], []),
    "ACs/Panasonic/Panasonic_CS-UE12RKE.ir": ("AIR_CONDITIONER", "Panasonic", ["CS-UE12RKE remote (buttons)"], []),
    "Audio_and_Video_Receivers/Denon/Denon_DRA_365R.ir": ("AUDIO", "Denon", ["DRA-365R"], []),
    "Audio_and_Video_Receivers/Fosi_Audio/Fosi_Audio_ZD3.ir": ("AUDIO", "Fosi Audio", ["ZD3"], []),
    "Audio_and_Video_Receivers/Fossi Audio/Fossi_Audio_ZH3.ir": ("AUDIO", "Fosi Audio", ["ZH3"], []),
    "Audio_and_Video_Receivers/Nakamichi/NAKAMICHI_RM_4TA.ir": ("AUDIO", "Nakamichi", ["RM-4TA remote"], []),
    "Audio_and_Video_Receivers/Philips/Philips Soundbar-HTL2060_94.ir": ("AUDIO", "Philips", ["HTL2060/94 soundbar"], []),
    "SoundBars/Ultimea/Ultimea_Poseidon_M20.ir": ("AUDIO", "Ultimea", ["Poseidon M20 soundbar"], []),
    "Miscellaneous/Roksan/Roksan_K3_Amp.ir": ("AUDIO", "Roksan", ["K3 integrated amplifier"], []),
    "Miscellaneous/Roksan/Roksan_K3_DAC.ir": ("AUDIO", "Roksan", ["K3 DAC"], []),
    "Projectors/NEC/NEC_RU_M124.ir": ("PROJECTOR", "NEC", ["RU-M124 remote"], []),
    "Projectors/Sony/Sony_RM_PJ1000.ir": ("PROJECTOR", "Sony", ["RM-PJ1000 remote"], []),
    "Fans/Domo/DOMO_DO8149.ir": ("FAN", "Domo", ["DO8149"], []),
    "Fans/Dyson/Dyson_TP7A.ir": ("FAN", "Dyson", ["TP7A purifier fan"], []),
    "Air_Purifiers/Dyson/Dyson_PurifiyHumidify_PH04.ir": ("FAN", "Dyson", ["PH04 Purifier Humidify+Cool"], []),
    "Fans/Klarstein/Klarstein_Sommwerwind.ir": ("FAN", "Klarstein", ["Sommerwind"], []),
    "Fans/Mitsubishi/Mitsubishi_R30J-HRV.ir": ("FAN", "Mitsubishi", ["R30J-HRV"], []),
    "Fans/ProBreeze/ProBreeze_PBF04.ir": ("FAN", "ProBreeze", ["PBF04"], []),
    "LED_Lighting/Tween/Tween_Light_Oria.ir": ("LIGHT", "Tween Light", ["Oria"], []),
}


# Button names in bundled Flipper files that the app would not recognise, renamed to a name that
# CommandNames.normalise() maps (the label shown is the new name). path -> {old name: new name}.
_PLAY = {"Play_pause": "Play/Pause", "Play_Pause": "Play/Pause"}
FLIPPER_RENAMES = {
    "ACs/Panasonic/Panasonic_CS-UE12RKE.ir": {"On_off": "Power", "Air_Swing": "Swing"},
    "Air_Purifiers/Dyson/Dyson_PurifiyHumidify_PH04.ir": {"FanPower+": "Speed+", "FanPower-": "Speed_down"},
    "Audio_and_Video_Receivers/Denon/Denon_DRA_365R.ir": {"V_UP": "Vol_up", "V_down": "Vol_dn", "Denon_power": "Power", "RR": "Rewind"},
    "Audio_and_Video_Receivers/Fosi_Audio/Fosi_Audio_ZD3.ir": _PLAY,
    "Audio_and_Video_Receivers/Fossi Audio/Fossi_Audio_ZH3.ir": {"Change_Input": "Input"},
    "Audio_and_Video_Receivers/Philips/Philips Soundbar-HTL2060_94.ir": _PLAY,
    "SoundBars/Ultimea/Ultimea_Poseidon_M20.ir": _PLAY,
    "Miscellaneous/Roksan/Roksan_K3_Amp.ir": {"Next_input": "Input"},
    "Miscellaneous/Roksan/Roksan_K3_DAC.ir": {"Next_input": "Input"},
    "Streaming_Devices/Kaleidescape/Kaleidescape_Strato_V.ir": _PLAY,
    "DVB-T/Tesla/Tesla_TE-300.ir": {"Backward": "Rewind", "Skip_forward": "Next", "Skip_backward": "Prev"},
    "TVs/ContinentalEdison/ContinentalEdison_CELD55SQLDV24B6.ir": {"App_Menu": "Apps"},
    "TVs/Hotels/Hilton_Connected_Room_Remote.ir": {"Captions": "Subtitle"},
    "Projectors/NEC/NEC_RU_M124.ir": {"CH +": "Ch+", "CH -": "Ch-"},
    "Projectors/Sony/Sony_RM_PJ1000.ir": {"Muting_AUDIO": "Mute"},
    "Fans/Domo/DOMO_DO8149.ir": {"Rotation": "Oscillate", "Speed_dn": "Speed_down"},
    "Fans/Dyson/Dyson_TP7A.ir": {"Oscillation": "Oscillate"},
    "Fans/Klarstein/Klarstein_Sommwerwind.ir": {"Oscillation": "Oscillate"},
    "Fans/ProBreeze/ProBreeze_PBF04.ir": {"Oscillation": "Oscillate"},
}


def necext(cmd):
    """NECext command as stored by Flipper and this app: low byte command, high byte its inverse."""
    return cmd | ((cmd ^ 0xFF) << 8)


# Protocol-defined LED remotes. Values are 8-bit NEC commands (expanded with necext() for NECext).
# Only factual address/command values are used; no third-party file is bundled for these.
_CROSS = "Flipper-IRDB d126fb1 LED_Lighting captures (cross-check of factual values only, files not bundled)"
_SHADES_24 = [
    ("color:orange", "Orange", 0x08), ("color:light_green", "Light green", 0x09), ("color:indigo", "Indigo", 0x0A),
    ("color:light_orange", "Light orange", 0x0C), ("color:cyan", "Cyan", 0x0D), ("color:purple", "Purple", 0x0E),
    ("color:gold", "Gold", 0x10), ("color:light_blue", "Light blue", 0x11), ("color:magenta", "Magenta", 0x12),
    ("color:yellow", "Yellow", 0x14), ("color:sky_blue", "Sky blue", 0x15), ("color:pink", "Pink", 0x16),
]
LED_REMOTES = [
    {
        "id": "led-24key-necext-ef00",
        "models": ["24-key RGB remote (NECext 0xEF00)", "RGB LED strip / bulb controller"],
        "protocol": "NECext", "address": 0xEF00,
        "commands": [
            ("brightness_up", "Brightness +", 0x00), ("brightness_down", "Brightness -", 0x01),
            ("power_off", "Off", 0x02), ("power_on", "On", 0x03),
            ("color:red", "Red", 0x04), ("color:green", "Green", 0x05), ("color:blue", "Blue", 0x06), ("color:white", "White", 0x07),
            *_SHADES_24,
            ("effect:flash", "Flash", 0x0B), ("effect:strobe", "Strobe", 0x0F), ("effect:fade", "Fade", 0x13), ("effect:smooth", "Smooth", 0x17),
        ],
        "evidence": "On, Off, Brightness +/- equal SmartIR (MIT) light/1070 'Aconic LED Controller 24Key' captures, decoded to NECext "
                    "0xEF00 0x03/0x02/0x00/0x01. All 24 values agree with captures of 43 independent remotes in Flipper-IRDB.",
        "sources": "SmartIR light/1070.json (litinoveweedle fork cbccf9c, MIT); " + _CROSS,
    },
    {
        "id": "led-44key-nec-00",
        "models": ["44-key RGB strip remote (NEC 0x00)", "RGB LED strip controller with DIY keys"],
        "protocol": "NEC", "address": 0x00,
        "commands": [
            ("brightness_up", "Brightness +", 0x5C), ("brightness_down", "Brightness -", 0x5D),
            ("play", "Play / Pause", 0x41), ("power", "Power", 0x40),
            ("color:red", "Red", 0x58), ("color:green", "Green", 0x59), ("color:blue", "Blue", 0x45), ("color:white", "White", 0x44),
            ("color:dark_orange", "Dark orange", 0x54), ("color:light_green", "Light green", 0x55),
            ("color:dark_blue", "Dark blue", 0x49), ("color:pink", "Pink", 0x48),
            ("color:orange", "Orange", 0x50), ("color:cyan", "Cyan", 0x51), ("color:violet", "Violet", 0x4D), ("color:light_pink", "Light pink", 0x4C),
            ("color:light_orange", "Light orange", 0x1C), ("color:light_cyan", "Light cyan", 0x1D),
            ("color:purple", "Purple", 0x1E), ("color:ice_blue", "Ice blue", 0x1F),
            ("color:yellow", "Yellow", 0x18), ("color:sky_blue", "Sky blue", 0x19),
            ("color:magenta", "Magenta", 0x1A), ("color:light_ice_blue", "Light ice blue", 0x1B),
            ("red_up", "Red +", 0x14), ("green_up", "Green +", 0x15), ("blue_up", "Blue +", 0x16), ("speed_up", "Quick", 0x17),
            ("red_down", "Red -", 0x10), ("green_down", "Green -", 0x11), ("blue_down", "Blue -", 0x12), ("speed_down", "Slow", 0x13),
            ("diy_1", "DIY 1", 0x0C), ("diy_2", "DIY 2", 0x0D), ("diy_3", "DIY 3", 0x0E), ("effect:auto", "Auto", 0x0F),
            ("diy_4", "DIY 4", 0x08), ("diy_5", "DIY 5", 0x09), ("diy_6", "DIY 6", 0x0A), ("effect:flash", "Flash", 0x0B),
            ("effect:jump3", "Jump 3", 0x04), ("effect:jump7", "Jump 7", 0x05), ("effect:fade3", "Fade 3", 0x06), ("effect:fade7", "Fade 7", 0x07),
        ],
        "evidence": "Widely published 44-key table (NEC 0x00/0xFF, e.g. 0xFF3AC5 = brightness up). Cross-checked against 9 captured "
                    "44-key remotes in Flipper-IRDB: 42 of 44 keys decoded from one complete capture; DIY 5 (0x09) confirmed by 4 "
                    "captures; Play/Pause (0x41) by one capture only.",
        "sources": "Published 44-key NEC table; " + _CROSS,
    },
    {
        "id": "led-24key-nec-00-bulb",
        "models": ["24-key RGBW bulb remote (NEC 0x00, keys 0x04-0x1B)"],
        "protocol": "NEC", "address": 0x00,
        "commands": [
            ("brightness_up", "Brightness +", 0x05), ("brightness_down", "Brightness -", 0x04),
            ("power_off", "Off", 0x06), ("power_on", "On", 0x07),
            ("color:red", "Red", 0x09), ("color:green", "Green", 0x08), ("color:blue", "Blue", 0x0A), ("color:white", "White", 0x0B),
            ("color:light_red", "Light red", 0x0D), ("color:light_green", "Light green", 0x0C), ("color:light_blue", "Light blue", 0x0E),
            ("color:orange", "Orange", 0x15), ("color:light_cyan", "Light cyan", 0x14), ("color:dark_purple", "Dark purple", 0x16),
            ("color:light_orange", "Light orange", 0x19), ("color:cyan", "Cyan", 0x18), ("color:purple", "Purple", 0x1A),
            ("color:yellow", "Yellow", 0x11), ("color:dark_cyan", "Dark cyan", 0x10), ("color:pink", "Pink", 0x12),
            ("effect:flash", "Flash", 0x0F), ("effect:strobe", "Strobe", 0x17), ("effect:fade", "Fade", 0x1B), ("effect:smooth", "Smooth", 0x13),
        ],
        "evidence": "Layout agreed by 3 independent captured remotes in Flipper-IRDB (ISY, Warmoon, unbranded RGB bulb); a 4th "
                    "(Kikilive) swaps colour keys. Colour names follow the ISY capture.",
        "sources": _CROSS,
    },
    {
        "id": "led-24key-nec-00-osram",
        "models": ["24-key RGBW bulb remote (NEC 0x00, Osram / Varilux style)"],
        "protocol": "NEC", "address": 0x00,
        "commands": [
            ("brightness_up", "Brightness +", 0x00), ("brightness_down", "Brightness -", 0x02),
            ("power_off", "Off", 0x06), ("power_on", "On", 0x07),
            ("color:red", "Red", 0x08), ("color:green", "Green", 0x09), ("color:blue", "Blue", 0x0A), ("color:white", "White", 0x03),
            ("color:red_1", "Red 1", 0x0C), ("color:green_1", "Green 1", 0x0D), ("color:blue_1", "Blue 1", 0x0E),
            ("color:red_2", "Red 2", 0x10), ("color:green_2", "Green 2", 0x11), ("color:blue_2", "Blue 2", 0x12),
            ("color:red_3", "Red 3", 0x14), ("color:green_3", "Green 3", 0x15), ("color:blue_3", "Blue 3", 0x16),
            ("color:red_4", "Red 4", 0x18), ("color:green_4", "Green 4", 0x19), ("color:blue_4", "Blue 4", 0x1A),
            ("effect:flash", "Flash", 0x0F), ("effect:strobe", "Strobe", 0x13), ("effect:smooth", "Smooth", 0x17), ("mode", "Mode", 0x1B),
        ],
        "evidence": "Two independent captured remotes in Flipper-IRDB (Osram 24-button, Varilux Lightme) agree on every shared key.",
        "sources": _CROSS,
    },
    {
        "id": "led-24key-nec-00-sylvania",
        "models": ["24-key RGB bulb remote (NEC 0x00, Sylvania / Panasonic / Calex style)"],
        "protocol": "NEC", "address": 0x00,
        "commands": [
            ("brightness_up", "Brightness +", 0x09), ("brightness_down", "Brightness -", 0x1D),
            ("power_on", "On", 0x0D), ("power_off", "Off", 0x1F),
            ("color:red", "Red", 0x19), ("color:green", "Green", 0x1B), ("color:blue", "Blue", 0x11), ("color:white", "White", 0x15),
            ("color:dark_orange", "Dark orange", 0x17), ("color:light_orange", "Light orange", 0x0A), ("color:orange", "Orange", 0x40),
            ("color:yellow", "Yellow", 0x1C), ("color:purple", "Purple", 0x16), ("color:indigo", "Indigo", 0x04), ("color:aqua", "Aqua", 0x1E),
            ("effect:flash", "Flash", 0x4D), ("effect:strobe", "Strobe", 0x00), ("effect:fade", "Fade", 0x1A), ("effect:smooth", "Smooth", 0x0C),
        ],
        "evidence": "Sylvania 24-button, Panasonic PBUM11070 and Calex strip captures in Flipper-IRDB agree on power, brightness, RGBW "
                    "and effects; only colour keys that two captures name alike are included.",
        "sources": _CROSS,
    },
]


def broadlink_us(b64):
    b64 = "".join(b64.split())
    if b64.startswith("b64:"):
        b64 = b64[4:]
    b64 = b64.rstrip("=")
    if len(b64) % 4 == 1:
        b64 = b64[:-1]
    raw = base64.b64decode(b64 + "=" * (-len(b64) % 4))
    if raw[0] != 0x26:
        return None
    n = raw[2] | (raw[3] << 8)
    body, out, i = raw[4:4 + n], [], 0
    while i < len(body):
        if body[i] == 0:
            if i + 2 >= len(body):
                break
            out.append((body[i + 1] << 8) | body[i + 2])
            i += 3
        else:
            out.append(body[i])
            i += 1
    return [round(t * 8192 / 269) for t in out]


def first_code(cmds):
    for key, value in cmds.items():
        if key == "off":
            continue
        while isinstance(value, dict):
            value = next(iter(value.values()))
        return value
    return cmds.get("off")


def fingerprint(us):
    """Returns the protocol family suggested by the timing structure, or None."""
    if not us or len(us) < 20:
        return None
    hm, hs, n = us[0], us[1], len(us)
    if 7500 <= hm <= 9500 and 4000 <= hs <= 4800 and n in (343, 344):
        return "kelon168"
    if 7300 <= hm <= 8300 and 3800 <= hs <= 4400 and n in (263, 264, 265, 266):
        return "tadiran64"
    if 8500 <= hm <= 9500 and 4200 <= hs <= 4800 and n in (139, 140, 279, 280) and us[73] > 15000:
        return "gree64"
    if 8500 <= hm <= 9500 and 4200 <= hs <= 4800 and n in (211, 212):
        return "electra_ac104"
    if 2700 <= hm <= 3300 and 2700 <= hs <= 4300:
        # Manchester with ~1000 us half-bits: every early timing is ~1000 or ~2000 us.
        body = us[2:40]
        manchester = sum(1 for v in body if 800 <= v <= 1200 or 1700 <= v <= 2300)
        if manchester >= 0.9 * len(body):
            return "electra34"
    if 4200 <= hm <= 4800 and 4200 <= hs <= 4600 and n in (199, 200):
        bits = [1 if us[3 + 2 * k] > 1000 else 0 for k in range(8)]
        if sum(b << (7 - j) for j, b in enumerate(bits)) == 0xB2:
            return "coolix24"
    return None


def classify_media(brand, models):
    override = MEDIA_CATEGORY.get((brand.lower(), models[0] if models else ""))
    if override:
        return override
    low = (brand + " " + " ".join(models)).lower()
    if any(w in low for w in AUDIO_WORDS):
        return "AUDIO"
    if any(w in low for w in PROJECTOR_WORDS):
        return "PROJECTOR"
    if any(w in low for w in BOX_WORDS):
        return "TV_BOX"
    return "TELEVISION"


def _valid_pattern(us):
    """Mirrors PatternValidator: entry count, 5..500000 us per entry, under 2 s in total."""
    return 0 < len(us) <= MAX_ENTRIES and all(5 <= v <= 500_000 for v in us) and sum(us) < MAX_PATTERN_US


def _timings(value, enc):
    """Decodes one SmartIR code like the app's importers; None when it is not a usable IR pattern."""
    try:
        if isinstance(value, list):  # a short sequence of codes, sent 50 ms apart
            if not value or len(value) > 4:
                return None
            parts = [_timings(v, enc) for v in value]
            if any(p is None for p in parts):
                return None
            return [t for p in parts for t in p] + [50_000] * (len(parts) - 1)
        if not isinstance(value, str) or not value.strip():
            return None
        if enc == "Base64":
            b64 = "".join(value.split())
            if b64.startswith("b64:"):
                b64 = b64[4:]
            b64 = b64.rstrip("=")
            if len(b64) % 4 == 1:
                b64 = b64[:-1]
            raw = base64.b64decode(b64 + "=" * (-len(b64) % 4))
            if len(raw) < 6 or raw[0] != 0x26 or 4 + (raw[2] | raw[3] << 8) > len(raw):
                return None  # RF packet or truncated
            us = broadlink_us(value)
        elif enc == "Pronto":
            w = [int(x, 16) for x in value.split()]
            if len(w) < 6 or w[0] != 0 or w[1] == 0 or 4 + 2 * (w[2] + w[3]) > len(w):
                return None
            us = [round(x * w[1] * 0.241246) for x in w[4:4 + 2 * (w[2] + w[3])]]
        else:  # Raw (ESPHome signed list, or a string holding one)
            toks = value.strip().lstrip("[").rstrip("]").replace(",", " ").split()
            us = [abs(int(t)) for t in toks]
        us = [t for t in us if t]
        return us if _valid_pattern(us) else None
    except (ValueError, IndexError, TypeError):
        return None


def _leaves(node, path=()):
    if isinstance(node, dict):
        for k, v in node.items():
            yield from _leaves(v, path + (k,))
    else:
        yield path, node


def usable_codes(d):
    """(usable, total) code counts of a SmartIR file."""
    enc = d.get("commandsEncoding", "")
    leaves = list(_leaves(d.get("commands", {})))
    return sum(1 for _, v in leaves if _timings(v, enc) is not None), len(leaves)


def smartir_supported(d):
    controller, enc = d.get("supportedController"), d.get("commandsEncoding", "")
    return (controller == "Broadlink" and enc in ("Base64", "Pronto")) or (controller == "ESPHome" and enc == "Raw")


def git_rev(repo):
    return subprocess.run(["git", "-C", repo, "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()


def write_led_remotes(out, entries):
    os.makedirs(os.path.join(out, "generated"), exist_ok=True)
    url = "https://github.com/Lucaslhm/Flipper-IRDB/tree/main/LED_Lighting"
    for r in LED_REMOTES:
        cmds = [{"name": n, "label": label, "kind": "parsed", "protocol": r["protocol"], "address": r["address"],
                 "command": necext(v) if r["protocol"] == "NECext" else v} for n, label, v in r["commands"]]
        names = [c["name"] for c in cmds]
        values = [c["command"] for c in cmds]
        assert len(names) == len(set(names)) and len(values) == len(set(values)), r["id"] + " has duplicates"
        name = r["id"] + ".json"
        with open(os.path.join(out, "generated", name), "w", encoding="utf-8", newline="\n") as f:
            json.dump({
                "formatVersion": 1, "id": r["id"], "protocolId": r["protocol"], "category": "LIGHT",
                "brand": GENERIC_LED_BRAND, "models": r["models"], "commands": cmds,
                "source": {"repository": "Universal IR protocol table", "url": url,
                           "license": "Factual protocol values", "attribution": r["sources"]},
                "verification": "SOURCE_DISCOVERED", "notes": [r["evidence"]],
            }, f, ensure_ascii=False, indent=1)
        entries.append({
            "id": r["id"], "category": "LIGHT", "brand": GENERIC_LED_BRAND, "models": r["models"],
            "asset": f"catalog/generated/{name}", "format": "canonical", "fingerprintProtocol": r["protocol"],
            "sourceUrl": url, "title": r["models"][0], "repository": r["sources"],
            "license": "Factual protocol values", "evidence": r["evidence"],
        })


def add_smartir(fork, src, out, entries, taken, report):
    """Copies one SmartIR fork's usable files. `taken` (shared across forks) maps (command hash, manufacturer)
    and (sub, file, manufacturer, models) of files bundled from earlier forks to their fork and id, so later
    forks add only new captures. Files within one fork are never deduplicated (identical codes listed under
    another brand or model still add that brand / model to the catalogue)."""
    url, prefix, asset_dir, display = SMARTIR_FORKS[fork]
    primary = fork == "zephyrx47"
    rev = None if primary else git_rev(src)
    dest = os.path.join(out, asset_dir)
    for sub, base_category in SMARTIR_SUBS:
        for path in sorted(glob.glob(os.path.join(src, "codes", sub, "*.json"))):
            name = os.path.basename(path)
            where = f"{fork}/{sub}/{name}"
            if os.path.getsize(path) > MAX_FILE_CHARS:
                report.append(f"skip {where}: over the 2 MB import cap"); continue
            try:
                d = json.load(open(path, encoding="utf-8"))
            except ValueError:
                report.append(f"skip {where}: invalid JSON"); continue
            if not smartir_supported(d):
                continue  # other controllers (Xiaomi, LOOKin, UFOR11, ZHA, MQTT) store non-timing formats
            if sub == "climate" and (d.get("temperatureUnit", "C") != "C" or float(d.get("maxTemperature") or 30) > 40):
                # The AC screen treats table temperatures as Celsius; a Fahrenheit table would show 61-86 "°C".
                report.append(f"skip {where}: Fahrenheit temperature table"); continue
            raw_brand = d.get("manufacturer", "").strip()
            models = [m.strip() for m in d.get("supportedModels", []) if m and m.strip()]
            digest = (hashlib.sha1(json.dumps(d.get("commands"), sort_keys=True).encode()).hexdigest(), raw_brand.lower())
            key = (sub, name, raw_brand.lower(), tuple(m.lower() for m in models))
            if digest in taken and taken[digest][0] != fork:
                continue  # identical commands for this brand already bundled from an earlier fork
            if key in taken and taken[key][0] != fork:
                report.append(f"skip {where}: later revision of {taken[key][1]}"); continue
            ok, total = usable_codes(d)
            if ok == 0 or ok * 2 < total:
                report.append(f"skip {where}: only {ok}/{total} codes are valid IR patterns"); continue
            set_id = f"{prefix}-{sub}-{name[:-5]}"
            taken.setdefault(digest, (fork, set_id))
            taken.setdefault(key, (fork, set_id))
            brand = BRAND_FIXES.get(raw_brand, raw_brand)
            category, extra = base_category, []
            if sub == "media_player":
                if (fork, name) in SMARTIR_BOXES:
                    category = "TV_BOX"
                    brand, models = SMARTIR_BOXES[(fork, name)]
                    extra.append(GENERIC_BOX_BRAND)
                else:
                    category = classify_media(raw_brand, models)
                    if category == "TV_BOX" and raw_brand.lower() in GENERIC_BOX_SOURCES:
                        extra.append(GENERIC_BOX_BRAND)
                if (fork, sub, name) in ANDROID_TV_SMARTIR:
                    extra.append(GENERIC_ANDROID_TV_BRAND)
            protocol = None
            if sub == "climate" and d.get("commandsEncoding") == "Base64":
                try:
                    protocol = fingerprint(broadlink_us(first_code(d["commands"])))
                except Exception:
                    protocol = None
            os.makedirs(os.path.join(dest, sub), exist_ok=True)
            shutil.copyfile(path, os.path.join(dest, sub, name))
            entry = {
                "id": set_id, "category": category, "brand": brand, "models": models or ["Unknown model"],
                "asset": f"catalog/{asset_dir}/{sub}/{name}",
                "format": "smartir_climate" if sub == "climate" else "smartir_device",
                "fingerprintProtocol": protocol,
                "sourceUrl": f"{url}/blob/{'master' if primary else rev}/codes/{sub}/{name}",
            }
            if not primary:
                entry.update({"repository": f"{display} {rev[:7]}", "license": "MIT"})
            if extra:
                entry["extraBrands"] = extra
            entries.append(entry)
            if ok < total:
                report.append(f"{set_id}: {total - ok} of {total} codes are not valid IR patterns (dropped on import)")
    if os.path.isdir(dest):
        shutil.copyfile(os.path.join(src, "LICENSE"), os.path.join(dest, "LICENSE"))


def header_first(text, rel):
    """The app (like the Flipper firmware) expects the "Filetype" header on the first line; a few files put
    their comment block above it. Moves those leading comment lines below the header/version lines."""
    lines = text.splitlines(keepends=True)
    if lines and lines[0].strip() == "Filetype: IR signals file":
        return text
    k = next((i for i, l in enumerate(lines) if l.strip() == "Filetype: IR signals file"), None)
    if k is None or any(l.strip() and not l.lstrip().startswith("#") for l in lines[:k]):
        raise SystemExit(f"{rel}: not a Flipper IR signals file")
    v = k + 1 if k + 1 < len(lines) and lines[k + 1].startswith("Version:") else k
    return "".join(lines[k:v + 1] + lines[:k] + lines[v + 1:])


def add_flipper(src, out, entries):
    rev = git_rev(src)
    dest = os.path.join(out, "flipper")
    os.makedirs(dest, exist_ok=True)
    for rel, (category, brand, models, extra) in FLIPPER_FILES.items():
        commits = subprocess.run(["git", "-C", src, "log", "--follow", "--format=%H", "--", rel],
                                 capture_output=True, text=True, check=True).stdout.split()
        if not commits:
            raise SystemExit(f"{rel}: not found in Flipper-IRDB")
        for c in commits:
            if subprocess.run(["git", "-C", src, "merge-base", "--is-ancestor", FLIPPER_LICENSE_COMMIT, c]).returncode != 0:
                raise SystemExit(f"{rel}: commit {c} predates the CC0 license commit; not bundling")
        text = subprocess.run(["git", "-C", src, "show", f"HEAD:{rel}"], capture_output=True, check=True).stdout.decode("utf-8")
        text = header_first(text, rel)
        renames = FLIPPER_RENAMES.get(rel, {})
        text = re.sub(r"^name:[ \t]*(.*?)([ \t\r]*)$", lambda m: "name: " + renames.get(m.group(1), m.group(1)) + m.group(2),
                      text, flags=re.M)
        names = re.findall(r"^name: (.*?)\r?$", text, flags=re.M)
        assert len(names) == len(set(names)), f"{rel}: duplicate button names after renaming"
        name = rel.replace("/", "_").replace(" ", "_")
        with open(os.path.join(dest, name), "w", encoding="utf-8", newline="") as f:
            f.write(text)
        entries.append({
            "id": "flipper-" + name[:-3].lower(), "category": category, "brand": brand, "models": models,
            "asset": f"catalog/flipper/{name}", "format": "flipper_ir", "fingerprintProtocol": None,
            "sourceUrl": f"{FLIPPER_URL}/blob/{rev}/{rel.replace(' ', '%20')}",
            "repository": f"Flipper-IRDB {rev[:7]}", "license": "CC0-1.0", "extraBrands": list(extra),
        })
    with open(os.path.join(dest, "LICENSE-CC0-1.0.txt"), "w", encoding="utf-8", newline="") as f:
        f.write(subprocess.run(["git", "-C", src, "show", "HEAD:LICENSE"], capture_output=True, check=True).stdout.decode("utf-8"))


def add_fujicom_candidates(entries):
    """One unverified Fujicom entry per Generic Android TV set (same asset, its own id and evidence)."""
    for e in [e for e in entries if GENERIC_ANDROID_TV_BRAND in e.get("extraBrands", [])]:
        candidate = {k: v for k, v in e.items() if k != "extraBrands"}
        candidate.update({
            "id": "fujicom-candidate-" + e["id"], "category": "TELEVISION", "brand": FUJICOM_BRAND,
            "models": [f"Android TV - try {e['brand']} {e['models'][0]} remote (unverified)"],
            "title": f"Unverified: {e['brand']} {e['models'][0]} remote",
            "evidence": FUJICOM_EVIDENCE,
        })
        candidate.setdefault("repository", SMARTIR_FORKS["zephyrx47"][3])
        candidate.setdefault("license", "MIT")
        entries.append(candidate)


# Linux kernel RC keymaps for TV boxes (drivers/media/rc/keymaps). GPL-2.0; data only (scancode
# tables converted to canonical JSON). file -> (brand, models, in generic Chinese box list,
# per-file key overrides). Box names come from each file's header and its adding commit.
LINUX_URL = "https://github.com/torvalds/linux"
LINUX_KEYMAPS = {
    "rc-x96max.c": ("X96 (Amediatech)", ["X96 Max"], True, {}),
    "rc-tanix-tx3mini.c": ("Tanix", ["TX3 mini"], True, {}),
    "rc-tanix-tx5max.c": ("Tanix", ["TX5 max"], True, {}),
    "rc-beelink-gs1.c": ("Beelink", ["GS1"], True, {}),
    "rc-beelink-mxiii.c": ("Beelink", ["Mini MXIII"], True, {}),
    "rc-mecool-kii-pro.c": ("Mecool", ["KII Pro"], True, {}),
    "rc-mecool-kiii-pro.c": ("Mecool", ["KIII Pro"], True, {}),
    "rc-videostrong-kii-pro.c": ("Videostrong", ["KII Pro", "Other Videostrong boxes (same remote)"], True, {}),
    "rc-minix-neo.c": ("Minix", ["NEO series"], True, {}),
    # Commit a53dee7b: "The remote includes a mouse button ... mapped to KEY_MUTE".
    "rc-khadas.c": ("Khadas", ["VIM", "Edge"], True, {"KEY_MUTE": ("mouse", "Mouse")}),
    "rc-odroid.c": ("Hardkernel", ["ODROID (C/N range)"], True, {}),
    "rc-wetek-hub.c": ("WeTek", ["Hub"], True, {}),
    "rc-wetek-play2.c": ("WeTek", ["Play 2"], True, {}),
    "rc-vega-s9x.c": ("Tronsmart", ["Vega S95", "Vega S96"], True, {}),
    "rc-geekbox.c": ("GeekBox", ["GeekBox"], True, {}),
    # Commit d5da31f9: "The mouse key has been mapped to KEY_EPG".
    "rc-pine64.c": ("Pine64", ["Pine A64 remote"], True, {"KEY_EPG": ("mouse", "Mouse")}),
    "rc-hisi-poplar.c": ("HiSilicon", ["Poplar board remote (96Boards)"], True, {}),
    "rc-dreambox.c": ("Dreambox", ["One", "Two", "RC10/RC0 and RC20/RC-BT remotes"], False, {}),
}
# Keymaps that combine several remotes: split by NEC address -> (id suffix, models).
LINUX_SPLITS = {
    "rc-dreambox.c": {0x32: ("rc10", ["One / Two (RC10, RC0, RCU-BT remote)"]), 0x34: ("rc20", ["One / Two (RC20, RC-BT remote)"])},
}
# Considered and skipped (scancodes carry no NEC address, so the full frame cannot be rebuilt):
#   rc-hisi-tv-demo.c, rc-zx-irdec.c.

# Primary KEY_* -> command name. Secondary names are used only when the primary name is free.
_KEY_PRIMARY = {
    "KEY_POWER": "power", "KEY_POWER2": "power", "KEY_SLEEP": "sleep", "KEY_MUTE": "mute",
    "KEY_UP": "up", "KEY_DOWN": "down", "KEY_LEFT": "left", "KEY_RIGHT": "right", "KEY_OK": "ok",
    "KEY_BACK": "back", "KEY_EXIT": "exit", "KEY_HOME": "home", "KEY_MENU": "menu",
    "KEY_VOLUMEUP": "vol_up", "KEY_VOLUMEDOWN": "vol_down", "KEY_CHANNELUP": "ch_up", "KEY_CHANNELDOWN": "ch_down",
    "KEY_PLAYPAUSE": "play", "KEY_PAUSE": "pause", "KEY_STOP": "stop",
    "KEY_NEXT": "next", "KEY_NEXTSONG": "next", "KEY_PREVIOUS": "prev", "KEY_PREVIOUSSONG": "prev",
    "KEY_FASTFORWARD": "ff", "KEY_FORWARD": "ff", "KEY_REWIND": "rew", "KEY_RECORD": "record",
    "KEY_INFO": "info", "KEY_SETUP": "settings", "KEY_SETTINGS": "settings", "KEY_EPG": "guide", "KEY_PROGRAM": "guide",
    "KEY_SUBTITLE": "subtitle", "KEY_AUDIO": "audio", "KEY_APPSELECT": "apps",
    "KEY_RED": "red", "KEY_GREEN": "green", "KEY_YELLOW": "yellow", "KEY_BLUE": "blue",
    "KEY_PAGEUP": "page_up", "KEY_PAGEDOWN": "page_down", "KEY_SCREEN": "aspect", "KEY_ASPECT_RATIO": "aspect",
    "KEY_BUTTONCONFIG": "mouse", "KEY_LAST": "last", "KEY_LIST": "list", "KEY_VIDEO": "input",
}
_KEY_SECONDARY = {
    "KEY_ENTER": "ok", "KEY_SELECT": "ok", "KEY_ESC": "back", "KEY_HOMEPAGE": "home", "KEY_CONTEXT_MENU": "menu",
    "KEY_PLAY": "play", "KEY_CONFIG": "settings", "KEY_LANGUAGE": "audio", "KEY_TV": "input",
}
for _d in range(10):
    _KEY_PRIMARY[f"KEY_{_d}"] = _KEY_PRIMARY[f"KEY_NUMERIC_{_d}"] = f"digit_{_d}"
_KEY_LABELS = {
    "power": "Power", "mute": "Mute", "ok": "OK", "back": "Back", "home": "Home", "menu": "Menu", "vol_up": "Volume +",
    "vol_down": "Volume -", "ch_up": "Channel +", "ch_down": "Channel -", "play": "Play / Pause", "settings": "Settings",
    "guide": "Guide", "apps": "Apps", "mouse": "Mouse", "page_up": "Page +", "page_down": "Page -", "aspect": "Aspect",
    "input": "Input", "prev": "Previous", "ff": "Fast forward", "rew": "Rewind",
}


def _strip_block_comments(text):
    return re.sub(r"/\*.*?\*/", "", text, flags=re.S)


def nec_frame(scancode):
    """Kernel NEC/NECX scancode -> (protocol id, address, command) in this app's convention, or None."""
    if scancode <= 0xFFFF:
        return "NEC", scancode >> 8, scancode & 0xFF
    if scancode <= 0xFFFFFF:
        a, na, c = (scancode >> 16) & 0xFF, (scancode >> 8) & 0xFF, scancode & 0xFF
        if na == a ^ 0xFF:
            return "NEC", a, c
        return "NECext", a | (na << 8), necext(c)
    return None  # NEC32 / full 32-bit codes: not produced by the selected keymaps


def parse_keymap(path):
    """Returns (SPDX id, kernel protocol, [(frame, KEY_*, inline comment)], [unconvertible keys])."""
    raw = open(path, encoding="utf-8").read()
    spdx = re.search(r"SPDX-License-Identifier:\s*(\S+)", raw).group(1)
    proto = re.search(r"\.rc_proto\s*=\s*RC_PROTO_(\w+)", raw).group(1)
    if proto not in ("NEC", "NECX"):
        raise SystemExit(f"{path}: unsupported protocol {proto}")
    body = _strip_block_comments(raw)
    rows = re.findall(r"\{\s*(0x[0-9a-fA-F]+)\s*,\s*((?:KEY|BTN)_\w+)\s*\}\s*,?[ 	]*(?://[ 	]*(.*))?", body)
    pending, skipped, seen = [], [], set()
    for sc, key, comment in rows:
        frame = nec_frame(int(sc, 16))
        if frame is None or frame in seen:
            skipped.append(f"{key}@{sc}")  # unconvertible, or a second key on an already-mapped code
            continue
        seen.add(frame)
        pending.append((frame, key, (comment or "").strip()))
    return spdx, proto, pending, skipped


def name_commands(pending, overrides):
    """Overrides and primary names first, then secondary names, then the raw kernel key name."""
    taken, resolved = set(), [None] * len(pending)
    for i, (frame, key, comment) in enumerate(pending):
        name = overrides.get(key, (None,))[0] or ("mouse" if comment.lower() == "mouse" else _KEY_PRIMARY.get(key))
        if name and name not in taken:
            taken.add(name); resolved[i] = name
    for i, (frame, key, comment) in enumerate(pending):
        if resolved[i] is None and _KEY_SECONDARY.get(key) and _KEY_SECONDARY[key] not in taken:
            resolved[i] = _KEY_SECONDARY[key]; taken.add(resolved[i])
    for i, (frame, key, comment) in enumerate(pending):
        if resolved[i] is None:
            base = key.split("_", 1)[1].lower()
            name, n = base, 2
            while name in taken:
                name, n = f"{base}_{n}", n + 1
            resolved[i] = name; taken.add(name)
    cmds = []
    for (frame, key, comment), name in zip(pending, resolved):
        protocol, address, command = frame
        label = overrides.get(key, (None, None))[1] or _KEY_LABELS.get(name) or name.replace("_", " ").capitalize()
        if comment and comment.lower() != label.lower():
            label = f"{label} ({comment})"
        cmds.append({"name": name, "label": label, "kind": "parsed", "protocol": protocol, "address": address, "command": command})
    return cmds


def add_linux_keymaps(src, out, entries):
    rev = git_rev(src)
    tag = subprocess.run(["git", "-C", src, "describe", "--tags", "--exact-match"], capture_output=True, text=True).stdout.strip() or rev[:12]
    dest = os.path.join(out, "generated")
    os.makedirs(dest, exist_ok=True)
    report = []
    sets = []
    for fname, (brand, models, generic, overrides) in LINUX_KEYMAPS.items():
        rel = f"drivers/media/rc/keymaps/{fname}"
        spdx, proto, pending, skipped = parse_keymap(os.path.join(src, rel))
        split = LINUX_SPLITS.get(fname)
        if split:
            for address, (suffix, split_models) in split.items():
                part = [p for p in pending if p[0][1] == address]
                sets.append((fname, f"{fname[3:-2]}-{suffix}", brand, split_models, generic, overrides, spdx, proto, part, skipped))
            assert sum(len([p for p in pending if p[0][1] == a]) for a in split) == len(pending), fname
        else:
            sets.append((fname, fname[3:-2], brand, models, generic, overrides, spdx, proto, pending, skipped))
    for fname, short, brand, models, generic, overrides, spdx, proto, pending, skipped in sets:
        rel = f"drivers/media/rc/keymaps/{fname}"
        cmds = name_commands(pending, overrides)
        spdx = {"GPL-2.0+": "GPL-2.0-or-later", "GPL-2.0": "GPL-2.0-only"}.get(spdx, spdx)
        license_id = f"{spdx} (Linux kernel, {tag})"
        protocols = sorted({c["protocol"] for c in cmds})
        protocol_id = protocols[0] if len(protocols) == 1 else "mixed"
        set_id = "kernel-" + short
        url = f"{LINUX_URL}/blob/{tag}/{rel}"
        note = (f"Converted from the Linux kernel keymap {rel} ({tag}, commit {rev[:12]}), RC_PROTO_{proto}. "
                "Kernel key codes are mapped to this app's command names; unmapped keys keep their kernel name.")
        with open(os.path.join(dest, set_id + ".json"), "w", encoding="utf-8", newline="\n") as f:
            json.dump({
                "formatVersion": 1, "id": set_id, "protocolId": protocol_id, "category": "TV_BOX", "brand": brand,
                "models": models, "commands": cmds,
                "source": {"repository": f"Linux kernel {tag}", "url": url, "path": rel, "license": license_id,
                           "attribution": "The Linux kernel contributors; see the file header for its author."},
                "verification": "SOURCE_DISCOVERED", "notes": [note],
            }, f, ensure_ascii=False, indent=1)
        entries.append({
            "id": set_id, "category": "TV_BOX", "brand": brand, "models": models,
            "asset": f"catalog/generated/{set_id}.json", "format": "canonical", "fingerprintProtocol": protocol_id,
            "sourceUrl": url, "title": f"{brand} {models[0]} remote (Linux kernel keymap)",
            "repository": f"Linux kernel {tag} ({rev[:12]})", "license": license_id,
            "evidence": "Scancode table from the Linux kernel keymap for this box's remote, captured by its contributor "
                        "with ir-keytable. Not tested by this app.",
            "extraBrands": [GENERIC_BOX_BRAND] if generic else [],
        })
        report.append(f"{set_id}: {len(cmds)} commands ({protocol_id}){' skipped ' + ','.join(skipped) if skipped else ''}")
    print("\n".join(report))


def main(src, out, upstream=None, litinoveweedle=None, flipper=None, linux=None):
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(out)
    entries, taken, report = [], {}, []
    for fork, path in (("zephyrx47", src), ("upstream", upstream), ("litinoveweedle", litinoveweedle)):
        if path:
            add_smartir(fork, path, out, entries, taken, report)
    if flipper:
        add_flipper(flipper, out, entries)
    write_led_remotes(out, entries)
    if linux:
        add_linux_keymaps(linux, out, entries)
    add_fujicom_candidates(entries)
    ids = [e["id"] for e in entries]
    assert len(ids) == len(set(ids)), "duplicate code set ids"
    with open(os.path.join(out, "catalog.json"), "w", encoding="utf-8") as f:
        json.dump({"version": CATALOG_VERSION, "source": SMARTIR_FORKS["zephyrx47"][3], "license": "MIT", "entries": entries},
                  f, ensure_ascii=False, indent=1)
    print("\n".join(report))
    by_category, by_protocol = {}, {}
    for e in entries:
        by_category[e["category"]] = by_category.get(e["category"], 0) + 1
        if e["fingerprintProtocol"]:
            by_protocol[e["fingerprintProtocol"]] = by_protocol.get(e["fingerprintProtocol"], 0) + 1
    print("entries", len(entries), by_category, "fingerprints", by_protocol)


if __name__ == "__main__":
    args, opts = sys.argv[1:], {}
    for flag in ("--smartir-upstream", "--smartir-litinoveweedle", "--flipper-irdb", "--linux"):
        if flag in args:
            i = args.index(flag)
            opts[flag] = args[i + 1]
            del args[i:i + 2]
    main(args[0], args[1], opts.get("--smartir-upstream"), opts.get("--smartir-litinoveweedle"),
         opts.get("--flipper-irdb"), opts.get("--linux"))
