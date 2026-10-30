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
Protocol-defined LED remotes (LED_REMOTES) and full TV / box / soundbar remotes (TV_REMOTES, ids "fullremote-*")
are always generated as canonical JSON.

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
CATALOG_VERSION = 7

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
FULL_REMOTE_URL = "https://github.com/Lucaslhm/Flipper-IRDB/tree/main/TVs"
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


# Full TV / box / soundbar remotes generated from factual protocol values ("fullremote-*"). Each key was cross-checked
# across independent public captures (scratch tooling, not part of the build): Flipper-IRDB d126fb1 (TVs, Streaming_Devices,
# SoundBars) and probonopd/irdb 11aa5eb (codes/<brand>, JP1 / LIRC-derived tables, mapped as IrdbCsvImporter.mapIrdb does).
# A key is kept only when at least two independent files (identical copies counted once) give the same protocol, address and
# command and that value outvotes any other value for the key at least 2:1; a code already taken by a better-supported key is
# not reused. No third-party file is bundled for these. Rows: (command name, label, protocol, address, command); NECext
# commands are stored as Flipper does (command | ~command << 8, or the raw 16-bit command for Apple).
TV_REMOTES = [
    {
        "id": 'fullremote-samsung-tv', "category": 'TELEVISION', "brand": 'Samsung', "models": ['All Samsung TVs (full remote)'],
        "commands": [
            ('power', 'Power', 'Samsung32', 0x07, 0x02), ('power_off', 'Power off', 'Samsung32', 0x07, 0x98), ('input', 'Source', 'Samsung32', 0x07, 0x01),
            ('mute', 'Mute', 'Samsung32', 0x07, 0x0F), ('vol_up', 'Volume +', 'Samsung32', 0x07, 0x07), ('vol_down', 'Volume -', 'Samsung32', 0x07, 0x0B),
            ('ch_up', 'Channel +', 'Samsung32', 0x07, 0x12), ('ch_down', 'Channel -', 'Samsung32', 0x07, 0x10), ('up', 'Up', 'Samsung32', 0x07, 0x60),
            ('down', 'Down', 'Samsung32', 0x07, 0x61), ('left', 'Left', 'Samsung32', 0x07, 0x65), ('right', 'Right', 'Samsung32', 0x07, 0x62),
            ('ok', 'OK', 'Samsung32', 0x07, 0x68), ('back', 'Return', 'Samsung32', 0x07, 0x58), ('exit', 'Exit', 'Samsung32', 0x07, 0x2D),
            ('home', 'Smart Hub', 'Samsung32', 0x07, 0x79), ('menu', 'Menu', 'Samsung32', 0x07, 0x1A), ('settings', 'Tools', 'Samsung32', 0x07, 0x4B),
            ('info', 'Info', 'Samsung32', 0x07, 0x1F), ('guide', 'Guide', 'Samsung32', 0x07, 0x4F), ('netflix', 'Netflix', 'Samsung32', 0x07, 0xF3),
            ('prime_video', 'Prime Video', 'Samsung32', 0x07, 0xF4), ('digit_0', '0', 'Samsung32', 0x07, 0x11), ('digit_1', '1', 'Samsung32', 0x07, 0x04),
            ('digit_2', '2', 'Samsung32', 0x07, 0x05), ('digit_3', '3', 'Samsung32', 0x07, 0x06), ('digit_4', '4', 'Samsung32', 0x07, 0x08),
            ('digit_5', '5', 'Samsung32', 0x07, 0x09), ('digit_6', '6', 'Samsung32', 0x07, 0x0A), ('digit_7', '7', 'Samsung32', 0x07, 0x0C),
            ('digit_8', '8', 'Samsung32', 0x07, 0x0D), ('digit_9', '9', 'Samsung32', 0x07, 0x0E), ('red', 'Red', 'Samsung32', 0x07, 0x6C),
            ('green', 'Green', 'Samsung32', 0x07, 0x14), ('yellow', 'Yellow', 'Samsung32', 0x07, 0x15), ('blue', 'Blue', 'Samsung32', 0x07, 0x16),
            ('play', 'Play', 'Samsung32', 0x07, 0x47), ('pause', 'Pause', 'Samsung32', 0x07, 0x4A), ('play_pause', 'Play / Pause', 'Samsung32', 0x07, 0xB9),
            ('stop', 'Stop', 'Samsung32', 0x07, 0x46), ('ff', 'Fast forward', 'Samsung32', 0x07, 0x48), ('rew', 'Rewind', 'Samsung32', 0x07, 0x45),
            ('record', 'Record', 'Samsung32', 0x07, 0x49), ('subtitle', 'AD / Subtitles', 'Samsung32', 0x07, 0x25), ('audio', 'Audio', 'Samsung32', 0x07, 0x00),
            ('aspect', 'Picture size', 'Samsung32', 0x07, 0x3E), ('sleep', 'Sleep', 'Samsung32', 0x07, 0x03), ('list', 'Channel list', 'Samsung32', 0x07, 0x6B),
            ('last', 'Previous channel', 'Samsung32', 0x07, 0x13), ('text', 'Teletext', 'Samsung32', 0x07, 0x2C),
        ],
        "evidence": 'Every key is agreed by at least two independent remote captures (identical copies counted once) and outvotes any other value at least 2:1: 55 Flipper-IRDB file(s) and 29 probonopd/irdb file(s). Keys without that agreement are left out.',
        "sources": 'Flipper-IRDB d126fb1 captures (55 files); probonopd/irdb 11aa5eb codes/Samsung (29 files) (factual values only, files not bundled)',
        "support": 'power 64*, power_off 5*, input 41*, mute 74*, vol_up 73*, vol_down 70*, ch_up 59*, ch_down 57*, up 58*, down 58*, left 58*, right 58*, ok 53*, back 39*, exit 50*, home 24*, menu 54*, settings 32*, info 57*, guide 23*, netflix 8, prime_video 8, digit_0 51*, digit_1 53*, digit_2 53*, digit_3 53*, digit_4 53*, digit_5 53*, digit_6 53*, digit_7 53*, digit_8 53*, digit_9 53*, red 21*, green 23*, yellow 23*, blue 23*, play 30*, pause 31*, play_pause 4, stop 32*, ff 26*, rew 27*, record 21*, subtitle 29*, audio 11*, aspect 24*, sleep 24*, list 25*, last 38*, text 3*',
    },
    {
        "id": 'fullremote-lg-tv', "category": 'TELEVISION', "brand": 'LG', "models": ['All LG TVs (full remote)'],
        "commands": [
            ('power', 'Power', 'NEC', 0x04, 0x08), ('power_on', 'Power on', 'NEC', 0x04, 0xC4), ('power_off', 'Power off', 'NEC', 0x04, 0xC5),
            ('input', 'Input', 'NEC', 0x04, 0x0B), ('mute', 'Mute', 'NEC', 0x04, 0x09), ('vol_up', 'Volume +', 'NEC', 0x04, 0x02),
            ('vol_down', 'Volume -', 'NEC', 0x04, 0x03), ('ch_up', 'Channel +', 'NEC', 0x04, 0x00), ('ch_down', 'Channel -', 'NEC', 0x04, 0x01),
            ('up', 'Up', 'NEC', 0x04, 0x40), ('down', 'Down', 'NEC', 0x04, 0x41), ('left', 'Left', 'NEC', 0x04, 0x07),
            ('right', 'Right', 'NEC', 0x04, 0x06), ('ok', 'OK', 'NEC', 0x04, 0x44), ('back', 'Back', 'NEC', 0x04, 0x28),
            ('exit', 'Exit', 'NEC', 0x04, 0x5B), ('home', 'Home', 'NEC', 0x04, 0x7C), ('settings', 'Settings', 'NEC', 0x04, 0x43),
            ('q_menu', 'Q.Menu', 'NEC', 0x04, 0x45), ('info', 'Info', 'NEC', 0x04, 0xAA), ('guide', 'Guide', 'NEC', 0x04, 0xAB),
            ('netflix', 'Netflix', 'NEC', 0x04, 0x56), ('prime_video', 'Prime Video', 'NEC', 0x04, 0x5C), ('disney_plus', 'Disney+', 'NEC', 0x04, 0x31),
            ('digit_0', '0', 'NEC', 0x04, 0x10), ('digit_1', '1', 'NEC', 0x04, 0x11), ('digit_2', '2', 'NEC', 0x04, 0x12),
            ('digit_3', '3', 'NEC', 0x04, 0x13), ('digit_4', '4', 'NEC', 0x04, 0x14), ('digit_5', '5', 'NEC', 0x04, 0x15),
            ('digit_6', '6', 'NEC', 0x04, 0x16), ('digit_7', '7', 'NEC', 0x04, 0x17), ('digit_8', '8', 'NEC', 0x04, 0x18),
            ('digit_9', '9', 'NEC', 0x04, 0x19), ('red', 'Red', 'NEC', 0x04, 0x72), ('green', 'Green', 'NEC', 0x04, 0x71),
            ('yellow', 'Yellow', 'NEC', 0x04, 0x63), ('blue', 'Blue', 'NEC', 0x04, 0x61), ('play', 'Play', 'NEC', 0x04, 0xB0),
            ('pause', 'Pause', 'NEC', 0x04, 0xBA), ('stop', 'Stop', 'NEC', 0x04, 0xB1), ('ff', 'Fast forward', 'NEC', 0x04, 0x8E),
            ('rew', 'Rewind', 'NEC', 0x04, 0x8F), ('record', 'Record', 'NEC', 0x04, 0xBD), ('subtitle', 'Subtitles', 'NEC', 0x04, 0x39),
            ('audio', 'Audio', 'NEC', 0x04, 0x0A), ('aspect', 'Picture size', 'NEC', 0x04, 0x79), ('sleep', 'Sleep', 'NEC', 0x04, 0x0E),
            ('last', 'Q.View', 'NEC', 0x04, 0x1A), ('text', 'Teletext', 'NEC', 0x04, 0x20),
        ],
        "evidence": 'Every key is agreed by at least two independent remote captures (identical copies counted once) and outvotes any other value at least 2:1: 34 Flipper-IRDB file(s) and 13 probonopd/irdb file(s). Keys without that agreement are left out.',
        "sources": 'Flipper-IRDB d126fb1 captures (34 files); probonopd/irdb 11aa5eb codes/LG (13 files) (factual values only, files not bundled)',
        "support": 'power 44*, power_on 2*, power_off 2*, input 36*, mute 41*, vol_up 43*, vol_down 38*, ch_up 37*, ch_down 33*, up 35*, down 34*, left 34*, right 34*, ok 38*, back 29*, exit 23*, home 16*, settings 18*, q_menu 10*, info 21*, guide 15*, netflix 11, prime_video 11, disney_plus 3, digit_0 34*, digit_1 33*, digit_2 33*, digit_3 33*, digit_4 33*, digit_5 34*, digit_6 34*, digit_7 33*, digit_8 33*, digit_9 34*, red 19*, green 18*, yellow 18*, blue 18*, play 21*, pause 21*, stop 16*, ff 14*, rew 11*, record 4*, subtitle 16*, audio 4*, aspect 9*, sleep 8*, last 11*, text 12*',
    },
    {
        "id": 'fullremote-sony-tv', "category": 'TELEVISION', "brand": 'Sony', "models": ['All Sony TVs (full remote)'],
        "commands": [
            ('power', 'Power', 'SIRC', 0x01, 0x15), ('power_off', 'Power off', 'SIRC', 0x01, 0x2F), ('input', 'Input', 'SIRC', 0x01, 0x25),
            ('mute', 'Mute', 'SIRC', 0x01, 0x14), ('vol_up', 'Volume +', 'SIRC', 0x01, 0x12), ('vol_down', 'Volume -', 'SIRC', 0x01, 0x13),
            ('ch_up', 'Channel +', 'SIRC', 0x01, 0x10), ('ch_down', 'Channel -', 'SIRC', 0x01, 0x11), ('up', 'Up', 'SIRC', 0x01, 0x74),
            ('down', 'Down', 'SIRC', 0x01, 0x75), ('left', 'Left', 'SIRC', 0x01, 0x34), ('right', 'Right', 'SIRC', 0x01, 0x33),
            ('ok', 'OK', 'SIRC', 0x01, 0x65), ('back', 'Return', 'SIRC15', 0x97, 0x23), ('exit', 'Exit', 'SIRC', 0x01, 0x63),
            ('home', 'Home', 'SIRC', 0x01, 0x60), ('settings', 'Options', 'SIRC15', 0x97, 0x36), ('info', 'Info', 'SIRC', 0x01, 0x3A),
            ('guide', 'Guide', 'SIRC15', 0xA4, 0x5B), ('netflix', 'Netflix', 'SIRC15', 0x1A, 0x7C), ('digit_0', '0', 'SIRC', 0x01, 0x09),
            ('digit_1', '1', 'SIRC', 0x01, 0x00), ('digit_2', '2', 'SIRC', 0x01, 0x01), ('digit_3', '3', 'SIRC', 0x01, 0x02),
            ('digit_4', '4', 'SIRC', 0x01, 0x03), ('digit_5', '5', 'SIRC', 0x01, 0x04), ('digit_6', '6', 'SIRC', 0x01, 0x05),
            ('digit_7', '7', 'SIRC', 0x01, 0x06), ('digit_8', '8', 'SIRC', 0x01, 0x07), ('digit_9', '9', 'SIRC', 0x01, 0x08),
            ('red', 'Red', 'SIRC15', 0x97, 0x25), ('green', 'Green', 'SIRC15', 0x97, 0x26), ('yellow', 'Yellow', 'SIRC15', 0x97, 0x27),
            ('blue', 'Blue', 'SIRC15', 0x97, 0x24), ('play', 'Play', 'SIRC15', 0x97, 0x1A), ('pause', 'Pause', 'SIRC15', 0x97, 0x19),
            ('stop', 'Stop', 'SIRC15', 0x97, 0x18), ('ff', 'Fast forward', 'SIRC15', 0x97, 0x1C), ('rew', 'Rewind', 'SIRC15', 0x97, 0x1B),
            ('next', 'Next', 'SIRC15', 0x97, 0x3D), ('prev', 'Previous', 'SIRC15', 0x97, 0x3C), ('record', 'Record', 'SIRC15', 0x97, 0x20),
            ('audio', 'Audio', 'SIRC', 0x01, 0x17), ('aspect', 'Picture size', 'SIRC15', 0xA4, 0x3D), ('sleep', 'Sleep', 'SIRC', 0x01, 0x36),
            ('last', 'Previous channel', 'SIRC', 0x01, 0x3B), ('text', 'Teletext', 'SIRC', 0x01, 0x3F),
        ],
        "evidence": 'Every key is agreed by at least two independent remote captures (identical copies counted once) and outvotes any other value at least 2:1: 24 Flipper-IRDB file(s) and 19 probonopd/irdb file(s). Keys without that agreement are left out.',
        "sources": 'Flipper-IRDB d126fb1 captures (24 files); probonopd/irdb 11aa5eb codes/Sony (19 files) (factual values only, files not bundled)',
        "support": 'power 32*, power_off 2*, input 31*, mute 34*, vol_up 37*, vol_down 34*, ch_up 25*, ch_down 25*, up 26*, down 24*, left 25*, right 25*, ok 26*, back 18, exit 5*, home 14, settings 10, info 19*, guide 6, netflix 3, digit_0 21*, digit_1 23*, digit_2 23*, digit_3 23*, digit_4 23*, digit_5 23*, digit_6 23*, digit_7 23*, digit_8 23*, digit_9 23*, red 7, green 7, yellow 7, blue 7, play 13*, pause 12*, stop 11*, ff 10*, rew 7, next 6*, prev 6*, record 5*, audio 12*, aspect 5*, sleep 12*, last 10*, text 5*',
    },
    {
        "id": 'fullremote-philips-tv', "category": 'TELEVISION', "brand": 'Philips', "models": ['All Philips TVs (full remote, RC6)'],
        "commands": [
            ('power', 'Power', 'RC6', 0x00, 0x0C), ('input', 'Source', 'RC6', 0x00, 0x38), ('mute', 'Mute', 'RC6', 0x00, 0x0D),
            ('vol_up', 'Volume +', 'RC6', 0x00, 0x10), ('vol_down', 'Volume -', 'RC6', 0x00, 0x11), ('ch_up', 'Channel +', 'RC6', 0x00, 0x20),
            ('ch_down', 'Channel -', 'RC6', 0x00, 0x21), ('up', 'Up', 'RC6', 0x00, 0x58), ('down', 'Down', 'RC6', 0x00, 0x59),
            ('left', 'Left', 'RC6', 0x00, 0x5A), ('right', 'Right', 'RC6', 0x00, 0x5B), ('ok', 'OK', 'RC6', 0x00, 0x5C),
            ('back', 'Back', 'RC6', 0x00, 0x0A), ('home', 'Home', 'RC6', 0x00, 0x54), ('menu', 'Menu', 'RC6', 0x00, 0x57),
            ('settings', 'Options', 'RC6', 0x00, 0x40), ('info', 'Info', 'RC6', 0x00, 0x0F), ('guide', 'Guide', 'RC6', 0x00, 0xCC),
            ('netflix', 'Netflix', 'RC6', 0x00, 0x76), ('youtube', 'YouTube', 'RC6', 0x00, 0x79), ('prime_video', 'Prime Video', 'RC6', 0x00, 0xBA),
            ('digit_0', '0', 'RC6', 0x00, 0x00), ('digit_1', '1', 'RC6', 0x00, 0x01), ('digit_2', '2', 'RC6', 0x00, 0x02),
            ('digit_3', '3', 'RC6', 0x00, 0x03), ('digit_4', '4', 'RC6', 0x00, 0x04), ('digit_5', '5', 'RC6', 0x00, 0x05),
            ('digit_6', '6', 'RC6', 0x00, 0x06), ('digit_7', '7', 'RC6', 0x00, 0x07), ('digit_8', '8', 'RC6', 0x00, 0x08),
            ('digit_9', '9', 'RC6', 0x00, 0x09), ('red', 'Red', 'RC6', 0x00, 0x6D), ('green', 'Green', 'RC6', 0x00, 0x6E),
            ('yellow', 'Yellow', 'RC6', 0x00, 0x6F), ('blue', 'Blue', 'RC6', 0x00, 0x70), ('play', 'Play', 'RC6', 0x00, 0x2C),
            ('pause', 'Pause', 'RC6', 0x00, 0x30), ('stop', 'Stop', 'RC6', 0x00, 0x31), ('ff', 'Fast forward', 'RC6', 0x00, 0x28),
            ('rew', 'Rewind', 'RC6', 0x00, 0x2B), ('record', 'Record', 'RC6', 0x00, 0x37), ('subtitle', 'Subtitles', 'RC6', 0x00, 0x4B),
            ('aspect', 'Format', 'RC6', 0x00, 0xF5), ('list', 'Channel list', 'RC6', 0x00, 0xD2), ('text', 'Teletext', 'RC6', 0x00, 0x3C),
        ],
        "evidence": 'Every key is agreed by at least two independent remote captures (identical copies counted once) and outvotes any other value at least 2:1: 14 Flipper-IRDB file(s) and 3 probonopd/irdb file(s). Keys without that agreement are left out.',
        "sources": 'Flipper-IRDB d126fb1 captures (14 files); probonopd/irdb 11aa5eb codes/Philips (3 files) (factual values only, files not bundled)',
        "support": 'power 17*, input 8, mute 16*, vol_up 16*, vol_down 15*, ch_up 9*, ch_down 9*, up 14*, down 13*, left 13*, right 14*, ok 13*, back 9*, home 9*, menu 2, settings 9*, info 7*, guide 10*, netflix 4, youtube 2, prime_video 2, digit_0 12*, digit_1 12*, digit_2 12*, digit_3 12*, digit_4 12*, digit_5 12*, digit_6 12*, digit_7 12*, digit_8 12*, digit_9 12*, red 10*, green 10*, yellow 11*, blue 11*, play 5*, pause 5, stop 6*, ff 6*, rew 5*, record 5*, subtitle 5*, aspect 4*, list 2, text 9*',
    },
    {
        "id": 'fullremote-toshiba-tv', "category": 'TELEVISION', "brand": 'Toshiba', "models": ['All Toshiba TVs (full remote)'],
        "commands": [
            ('power', 'Power', 'NEC', 0x40, 0x12), ('mute', 'Mute', 'NEC', 0x40, 0x10), ('vol_up', 'Volume +', 'NEC', 0x40, 0x1A),
            ('vol_down', 'Volume -', 'NEC', 0x40, 0x1E), ('ch_up', 'Channel +', 'NEC', 0x40, 0x1B), ('ch_down', 'Channel -', 'NEC', 0x40, 0x1F),
            ('up', 'Up', 'NEC', 0x40, 0x19), ('down', 'Down', 'NEC', 0x40, 0x1D), ('left', 'Left', 'NEC', 0x40, 0x42),
            ('right', 'Right', 'NEC', 0x40, 0x40), ('ok', 'OK', 'NEC', 0x40, 0x21), ('back', 'Return', 'NEC', 0x40, 0x64),
            ('menu', 'Menu', 'NEC', 0x40, 0x5B), ('info', 'Info', 'NEC', 0x40, 0x16), ('digit_0', '0', 'NEC', 0x40, 0x00),
            ('digit_1', '1', 'NEC', 0x40, 0x01), ('digit_2', '2', 'NEC', 0x40, 0x02), ('digit_3', '3', 'NEC', 0x40, 0x03),
            ('digit_4', '4', 'NEC', 0x40, 0x04), ('digit_5', '5', 'NEC', 0x40, 0x05), ('digit_6', '6', 'NEC', 0x40, 0x06),
            ('digit_7', '7', 'NEC', 0x40, 0x07), ('digit_8', '8', 'NEC', 0x40, 0x08), ('digit_9', '9', 'NEC', 0x40, 0x09),
            ('red', 'Red', 'NEC', 0x40, 0x48), ('green', 'Green', 'NEC', 0x40, 0x49), ('yellow', 'Yellow', 'NEC', 0x40, 0x4A),
            ('blue', 'Blue', 'NEC', 0x40, 0x4B), ('play', 'Play', 'NEC', 0x40, 0x51), ('stop', 'Stop', 'NEC', 0x40, 0x4F),
            ('ff', 'Fast forward', 'NEC', 0x40, 0x53), ('rew', 'Rewind', 'NEC', 0x40, 0x4E), ('audio', 'Audio', 'NEC', 0x40, 0x13),
            ('sleep', 'Sleep', 'NEC', 0x40, 0x15), ('text', 'Teletext', 'NEC', 0x40, 0x17),
        ],
        "evidence": 'Every key is agreed by at least two independent remote captures (identical copies counted once) and outvotes any other value at least 2:1: 6 Flipper-IRDB file(s) and 11 probonopd/irdb file(s). Keys without that agreement are left out.',
        "sources": 'Flipper-IRDB d126fb1 captures (6 files); probonopd/irdb 11aa5eb codes/Toshiba (11 files) (factual values only, files not bundled)',
        "support": 'power 17*, mute 17*, vol_up 16*, vol_down 15*, ch_up 13*, ch_down 12*, up 4*, down 4*, left 3*, right 3*, ok 4*, back 3*, menu 7*, info 4*, digit_0 15*, digit_1 15*, digit_2 14*, digit_3 15*, digit_4 15*, digit_5 15*, digit_6 15*, digit_7 15*, digit_8 15*, digit_9 15*, red 7*, green 6*, yellow 6*, blue 6*, play 2*, stop 2*, ff 2*, rew 2*, audio 2*, sleep 3*, text 2*',
    },
    {
        "id": 'fullremote-hisense-tv', "category": 'TELEVISION', "brand": 'Hisense', "models": ['All Hisense TVs (full remote)'],
        "commands": [
            ('power', 'Power', 'NECext', 0xBF00, 0xF20D), ('input', 'Input', 'NECext', 0xBF00, 0xED12), ('mute', 'Mute', 'NECext', 0xBF00, 0xF10E),
            ('vol_up', 'Volume +', 'NECext', 0xBF00, 0xBB44), ('vol_down', 'Volume -', 'NECext', 0xBF00, 0xBC43), ('ch_up', 'Channel +', 'NECext', 0xBF00, 0xB54A),
            ('ch_down', 'Channel -', 'NECext', 0xBF00, 0xB44B), ('up', 'Up', 'NECext', 0xBF00, 0xE916), ('down', 'Down', 'NECext', 0xBF00, 0xE817),
            ('left', 'Left', 'NECext', 0xBF00, 0xE619), ('right', 'Right', 'NECext', 0xBF00, 0xE718), ('ok', 'OK', 'NECext', 0xBF00, 0xEA15),
            ('back', 'Back', 'NECext', 0xBF00, 0xB748), ('exit', 'Exit', 'NECext', 0xBF00, 0xA35C), ('home', 'Home', 'NECext', 0xBF00, 0xDF20),
            ('settings', 'Settings', 'NECext', 0xBF00, 0xEB14), ('info', 'Info', 'NECext', 0xBF00, 0xF30C), ('guide', 'Guide', 'NECext', 0xBF00, 0xE21D),
            ('youtube', 'YouTube', 'NECext', 0xBF00, 0x55AA), ('digit_0', '0', 'NECext', 0xBF00, 0xFF00), ('digit_1', '1', 'NECext', 0xBF00, 0xFE01),
            ('digit_2', '2', 'NECext', 0xBF00, 0xFD02), ('digit_3', '3', 'NECext', 0xBF00, 0xFC03), ('digit_4', '4', 'NECext', 0xBF00, 0xFB04),
            ('digit_5', '5', 'NECext', 0xBF00, 0xFA05), ('digit_6', '6', 'NECext', 0xBF00, 0xF906), ('digit_7', '7', 'NECext', 0xBF00, 0xF807),
            ('digit_8', '8', 'NECext', 0xBF00, 0xF708), ('digit_9', '9', 'NECext', 0xBF00, 0xF609), ('red', 'Red', 'NECext', 0xBF00, 0xAD52),
            ('green', 'Green', 'NECext', 0xBF00, 0xAC53), ('yellow', 'Yellow', 'NECext', 0xBF00, 0xAB54), ('blue', 'Blue', 'NECext', 0xBF00, 0xAA55),
            ('play', 'Play', 'NECext', 0xBF00, 0x57A8), ('pause', 'Pause', 'NECext', 0xBF00, 0x56A9), ('stop', 'Stop', 'NECext', 0xBF00, 0xA55A),
            ('ff', 'Fast forward', 'NECext', 0xBF00, 0xA659), ('rew', 'Rewind', 'NECext', 0xBF00, 0xA758), ('subtitle', 'Subtitles', 'NECext', 0xBF00, 0xE01F),
        ],
        "evidence": 'Every key is agreed by at least two independent remote captures (identical copies counted once) and outvotes any other value at least 2:1: 8 Flipper-IRDB file(s). Keys without that agreement are left out.',
        "sources": 'Flipper-IRDB d126fb1 captures (8 files) (factual values only, files not bundled)',
        "support": 'power 7, input 8, mute 8, vol_up 8, vol_down 8, ch_up 7, ch_down 7, up 8, down 6, left 7, right 7, ok 7, back 5, exit 3, home 5, settings 4, info 5, guide 5, youtube 5, digit_0 3, digit_1 4, digit_2 4, digit_3 4, digit_4 4, digit_5 4, digit_6 4, digit_7 4, digit_8 4, digit_9 4, red 3, green 3, yellow 3, blue 3, play 3, pause 3, stop 5, ff 4, rew 4, subtitle 4',
    },
    {
        "id": 'fullremote-vizio-tv', "category": 'TELEVISION', "brand": 'Vizio', "models": ['All Vizio TVs (full remote)'],
        "commands": [
            ('power', 'Power', 'NEC', 0x04, 0x08), ('input', 'Input', 'NEC', 0x04, 0x2F), ('mute', 'Mute', 'NEC', 0x04, 0x09),
            ('vol_up', 'Volume +', 'NEC', 0x04, 0x02), ('vol_down', 'Volume -', 'NEC', 0x04, 0x03), ('ch_up', 'Channel +', 'NEC', 0x04, 0x00),
            ('ch_down', 'Channel -', 'NEC', 0x04, 0x01), ('up', 'Up', 'NEC', 0x04, 0x45), ('down', 'Down', 'NEC', 0x04, 0x46),
            ('left', 'Left', 'NEC', 0x04, 0x47), ('right', 'Right', 'NEC', 0x04, 0x48), ('ok', 'OK', 'NEC', 0x04, 0x44),
            ('back', 'Back', 'NEC', 0x04, 0x4A), ('exit', 'Exit', 'NEC', 0x04, 0x49), ('home', 'Home', 'NEC', 0x04, 0x2D),
            ('info', 'Info', 'NEC', 0x04, 0x1B), ('guide', 'Guide', 'NEC', 0x04, 0x1C), ('netflix', 'Netflix', 'NEC', 0x04, 0xEB),
            ('prime_video', 'Prime Video', 'NEC', 0x04, 0xEA), ('disney_plus', 'Disney+', 'NEC', 0x04, 0x1F), ('digit_0', '0', 'NEC', 0x04, 0x10),
            ('digit_1', '1', 'NEC', 0x04, 0x11), ('digit_2', '2', 'NEC', 0x04, 0x12), ('digit_3', '3', 'NEC', 0x04, 0x13),
            ('digit_4', '4', 'NEC', 0x04, 0x14), ('digit_5', '5', 'NEC', 0x04, 0x15), ('digit_6', '6', 'NEC', 0x04, 0x16),
            ('digit_7', '7', 'NEC', 0x04, 0x17), ('digit_8', '8', 'NEC', 0x04, 0x18), ('digit_9', '9', 'NEC', 0x04, 0x19),
            ('play', 'Play', 'NEC', 0x04, 0x33), ('pause', 'Pause', 'NEC', 0x04, 0x37), ('stop', 'Stop', 'NEC', 0x04, 0x30),
            ('subtitle', 'Subtitles', 'NEC', 0x04, 0x39), ('aspect', 'Picture size', 'NEC', 0x04, 0x77), ('sleep', 'Sleep', 'NEC', 0x04, 0x0E),
            ('last', 'Last', 'NEC', 0x04, 0x1A),
        ],
        "evidence": 'Every key is agreed by at least two independent remote captures (identical copies counted once) and outvotes any other value at least 2:1: 13 Flipper-IRDB file(s) and 2 probonopd/irdb file(s). Keys without that agreement are left out.',
        "sources": 'Flipper-IRDB d126fb1 captures (13 files); probonopd/irdb 11aa5eb codes/Vizio (2 files) (factual values only, files not bundled)',
        "support": 'power 13*, input 13*, mute 14*, vol_up 14*, vol_down 13*, ch_up 13*, ch_down 10, up 14*, down 13*, left 13*, right 13*, ok 12*, back 9, exit 7*, home 6, info 10*, guide 3, netflix 7, prime_video 7, disney_plus 2, digit_0 12*, digit_1 12*, digit_2 12*, digit_3 11*, digit_4 12*, digit_5 12*, digit_6 11*, digit_7 11*, digit_8 11*, digit_9 12*, play 2, pause 2, stop 2, subtitle 10*, aspect 7*, sleep 3*, last 4*',
    },
    {
        "id": 'fullremote-hitachi-tv', "category": 'TELEVISION', "brand": 'Hitachi', "models": ['All Hitachi TVs (full remote, NEC)'],
        "commands": [
            ('power', 'Power', 'NEC', 0x50, 0x17), ('power_on', 'Power on', 'NEC', 0x50, 0x3E), ('power_off', 'Power off', 'NEC', 0x50, 0x3F),
            ('input', 'Input', 'NEC', 0x50, 0x09), ('mute', 'Mute', 'NEC', 0x50, 0x0B), ('vol_up', 'Volume +', 'NEC', 0x50, 0x12),
            ('vol_down', 'Volume -', 'NEC', 0x50, 0x15), ('ch_up', 'Channel +', 'NEC', 0x50, 0x19), ('ch_down', 'Channel -', 'NEC', 0x50, 0x18),
            ('up', 'Up', 'NEC', 0x50, 0x70), ('down', 'Down', 'NEC', 0x50, 0x71), ('left', 'Left', 'NEC', 0x50, 0x73),
            ('right', 'Right', 'NEC', 0x50, 0x72), ('ok', 'OK', 'NEC', 0x50, 0x2E), ('exit', 'Exit', 'NEC', 0x50, 0x64),
            ('menu', 'Menu', 'NEC', 0x50, 0x53), ('digit_0', '0', 'NEC', 0x50, 0x0C), ('digit_1', '1', 'NEC', 0x50, 0x0D),
            ('digit_2', '2', 'NEC', 0x50, 0x0E), ('digit_3', '3', 'NEC', 0x50, 0x0F), ('digit_4', '4', 'NEC', 0x50, 0x1C),
            ('digit_5', '5', 'NEC', 0x50, 0x1D), ('digit_6', '6', 'NEC', 0x50, 0x1E), ('digit_7', '7', 'NEC', 0x50, 0x1F),
            ('digit_8', '8', 'NEC', 0x50, 0x04), ('digit_9', '9', 'NEC', 0x50, 0x05), ('play', 'Play', 'NEC', 0x50, 0x46),
            ('pause', 'Pause', 'NEC', 0x50, 0x61), ('ff', 'Fast forward', 'NEC', 0x50, 0x47), ('aspect', 'Picture size', 'NEC', 0x50, 0x6F),
            ('sleep', 'Sleep', 'NEC', 0x50, 0x11), ('last', 'Previous channel', 'NEC', 0x50, 0x10),
        ],
        "evidence": 'Every key is agreed by at least two independent remote captures (identical copies counted once) and outvotes any other value at least 2:1: 2 Flipper-IRDB file(s) and 11 probonopd/irdb file(s). Keys without that agreement are left out.',
        "sources": 'Flipper-IRDB d126fb1 captures (2 files); probonopd/irdb 11aa5eb codes/Hitachi (11 files) (factual values only, files not bundled)',
        "support": 'power 9*, power_on 2*, power_off 2*, input 7*, mute 10*, vol_up 7*, vol_down 6*, ch_up 6*, ch_down 5*, up 4*, down 4*, left 3*, right 3*, ok 2*, exit 3*, menu 6*, digit_0 5*, digit_1 5*, digit_2 5*, digit_3 5*, digit_4 5*, digit_5 5*, digit_6 5*, digit_7 5*, digit_8 5*, digit_9 5*, play 2*, pause 2*, ff 2*, aspect 2*, sleep 2*, last 4*',
    },
    {
        "id": 'fullremote-insignia-tv', "category": 'TELEVISION', "brand": 'Insignia', "models": ['All Insignia TVs (full remote)'],
        "commands": [
            ('power', 'Power', 'NECext', 0x586, 0xF00F), ('input', 'Input', 'NECext', 0x586, 0xE21D), ('mute', 'Mute', 'NECext', 0x586, 0xF10E),
            ('vol_up', 'Volume +', 'NECext', 0x586, 0xF30C), ('vol_down', 'Volume -', 'NECext', 0x586, 0xF20D), ('ch_up', 'Channel +', 'NECext', 0x586, 0xF50A),
            ('ch_down', 'Channel -', 'NECext', 0x586, 0xF40B), ('up', 'Up', 'NECext', 0x586, 0xBD42), ('down', 'Down', 'NECext', 0x586, 0xBC43),
            ('left', 'Left', 'NECext', 0x586, 0xE916), ('right', 'Right', 'NECext', 0x586, 0xEA15), ('ok', 'OK', 'NECext', 0x586, 0xE718),
            ('exit', 'Exit', 'NECext', 0x586, 0xE41B), ('menu', 'Menu', 'NECext', 0x586, 0xEB14), ('info', 'Info', 'NECext', 0x586, 0xE817),
            ('digit_0', '0', 'NECext', 0x586, 0xF609), ('digit_1', '1', 'NECext', 0x586, 0xFF00), ('digit_2', '2', 'NECext', 0x586, 0xFE01),
            ('digit_3', '3', 'NECext', 0x586, 0xFD02), ('digit_4', '4', 'NECext', 0x586, 0xFC03), ('digit_5', '5', 'NECext', 0x586, 0xFB04),
            ('digit_6', '6', 'NECext', 0x586, 0xFA05), ('digit_7', '7', 'NECext', 0x586, 0xF906), ('digit_8', '8', 'NECext', 0x586, 0xF807),
            ('digit_9', '9', 'NECext', 0x586, 0xF708), ('sleep', 'Sleep', 'NECext', 0x586, 0xEC13),
        ],
        "evidence": 'Every key is agreed by at least two independent remote captures (identical copies counted once) and outvotes any other value at least 2:1: 2 Flipper-IRDB file(s) and 1 probonopd/irdb file(s). Keys without that agreement are left out.',
        "sources": 'Flipper-IRDB d126fb1 captures (2 files); probonopd/irdb 11aa5eb codes/Insignia (1 files) (factual values only, files not bundled)',
        "support": 'power 3*, input 3*, mute 3*, vol_up 3*, vol_down 2, ch_up 3*, ch_down 2, up 2*, down 2*, left 2*, right 2*, ok 3*, exit 3*, menu 3*, info 2*, digit_0 2*, digit_1 2*, digit_2 2*, digit_3 2*, digit_4 2*, digit_5 2*, digit_6 2*, digit_7 2*, digit_8 2*, digit_9 2*, sleep 2*',
    },
    {
        "id": 'fullremote-roku-tv', "category": 'TELEVISION', "brand": 'Roku', "models": ['All Roku TVs (full remote)'],
        "extraBrands": ['TCL', 'Onn', 'Philips', 'RCA', 'Sanyo'],
        "commands": [
            ('power', 'Power', 'NECext', 0xC7EA, 0xE817), ('mute', 'Mute', 'NECext', 0xC7EA, 0xDF20), ('vol_up', 'Volume +', 'NECext', 0xC7EA, 0xF00F),
            ('vol_down', 'Volume -', 'NECext', 0xC7EA, 0xEF10), ('up', 'Up', 'NECext', 0xC7EA, 0xE619), ('down', 'Down', 'NECext', 0xC7EA, 0xCC33),
            ('left', 'Left', 'NECext', 0xC7EA, 0xE11E), ('right', 'Right', 'NECext', 0xC7EA, 0xD22D), ('ok', 'OK', 'NECext', 0xC7EA, 0xD52A),
            ('back', 'Back', 'NECext', 0xC7EA, 0x9966), ('home', 'Home', 'NECext', 0xC7EA, 0xFC03), ('settings', 'Settings', 'NECext', 0xC7EA, 0x9E61),
            ('netflix', 'Netflix', 'NECext', 0xC7EA, 0xAD52), ('youtube', 'YouTube', 'NECext', 0xC7EA, 0xA956), ('prime_video', 'Prime Video', 'NECext', 0xC7EA, 0xB44B),
            ('disney_plus', 'Disney+', 'NECext', 0xC7EA, 0xF30C), ('ff', 'Fast forward', 'NECext', 0xC7EA, 0xAA55), ('rew', 'Rewind', 'NECext', 0xC7EA, 0xCB34),
            ('sleep', 'Sleep', 'NECext', 0xC7EA, 0x9D62), ('play', 'Play / Pause', 'NECext', 0xC7EA, 0xB34C),
        ],
        "evidence": 'Every key is agreed by at least two independent remote captures (identical copies counted once) and outvotes any other value at least 2:1: 14 Flipper-IRDB file(s). Keys without that agreement are left out.',
        "sources": 'Flipper-IRDB d126fb1 captures (14 files) (factual values only, files not bundled)',
        "support": 'power 11, mute 12, vol_up 12, vol_down 10, up 13, down 10, left 13, right 12, ok 14, back 10, home 11, settings 6, netflix 10, youtube 2, prime_video 3, disney_plus 6, ff 9, rew 8, sleep 4, play 7',
    },
    {
        "id": 'fullremote-fire-tv-edition', "category": 'TELEVISION', "brand": 'Amazon', "models": ['All Fire TV Edition TVs (full remote)'],
        "extraBrands": ['Toshiba', 'Insignia'],
        "commands": [
            ('power', 'Power', 'NECext', 0x7D02, 0xB946), ('mute', 'Mute', 'NECext', 0x7D02, 0xB34C), ('vol_up', 'Volume +', 'NECext', 0x7D02, 0xF30C),
            ('vol_down', 'Volume -', 'NECext', 0x7D02, 0xE619), ('ch_up', 'Channel +', 'NECext', 0x7D02, 0xF00F), ('ch_down', 'Channel -', 'NECext', 0x7D02, 0xA55A),
            ('up', 'Up', 'NECext', 0x7D02, 0xB748), ('down', 'Down', 'NECext', 0x7D02, 0xB24D), ('left', 'Left', 'NECext', 0x7D02, 0xB14E),
            ('right', 'Right', 'NECext', 0x7D02, 0xB649), ('ok', 'OK', 'NECext', 0x7D02, 0xB54A), ('back', 'Back', 'NECext', 0x7D02, 0xF20D),
            ('home', 'Home', 'NECext', 0x7D02, 0x609F), ('menu', 'Menu', 'NECext', 0x7D02, 0xBA45), ('settings', 'Settings', 'NECext', 0x7D02, 0x6996),
            ('guide', 'Guide', 'NECext', 0x7D02, 0xEB14), ('netflix', 'Netflix', 'NECext', 0x7D02, 0xA05F), ('prime_video', 'Prime Video', 'NECext', 0x7D02, 0x5EA1),
            ('ff', 'Fast forward', 'NECext', 0x7D02, 0xE817), ('rew', 'Rewind', 'NECext', 0x7D02, 0xE916), ('play', 'Play / Pause', 'NECext', 0x7D02, 0xA45B),
        ],
        "evidence": 'Every key is agreed by at least two independent remote captures (identical copies counted once) and outvotes any other value at least 2:1: 5 Flipper-IRDB file(s). Keys without that agreement are left out.',
        "sources": 'Flipper-IRDB d126fb1 captures (5 files) (factual values only, files not bundled)',
        "support": 'power 5, mute 4, vol_up 4, vol_down 4, ch_up 3, ch_down 3, up 4, down 4, left 4, right 4, ok 4, back 3, home 4, menu 2, settings 3, guide 2, netflix 2, prime_video 2, ff 3, rew 3, play 3',
    },
    {
        "id": 'fullremote-vestel-tv', "category": 'TELEVISION', "brand": 'Vestel', "models": ['All Vestel-made TVs (full remote)'],
        "extraBrands": ['Bush', 'Continental Edison', 'Digihome', 'EdenWood', 'Finlux', 'Gogen', 'JVC', 'Medion', 'Technika', 'Telefunken', 'Toshiba'],
        "commands": [
            ('power', 'Power', 'RC5', 0x01, 0x0C), ('input', 'Source', 'RC5', 0x01, 0x38), ('mute', 'Mute', 'RC5', 0x01, 0x0D),
            ('vol_up', 'Volume +', 'RC5', 0x01, 0x10), ('vol_down', 'Volume -', 'RC5', 0x01, 0x11), ('ch_up', 'Channel +', 'RC5', 0x01, 0x20),
            ('ch_down', 'Channel -', 'RC5', 0x01, 0x21), ('up', 'Up', 'RC5', 0x01, 0x14), ('down', 'Down', 'RC5', 0x01, 0x13),
            ('left', 'Left', 'RC5', 0x01, 0x15), ('right', 'Right', 'RC5', 0x01, 0x16), ('ok', 'OK', 'RC5', 0x01, 0x35),
            ('back', 'Back', 'RC5', 0x01, 0x0A), ('exit', 'Exit', 'RC5', 0x01, 0x25), ('home', 'Home', 'RC5', 0x01, 0x2E),
            ('menu', 'Menu', 'RC5', 0x01, 0x30), ('settings', 'Settings', 'RC5', 0x01, 0x2B), ('info', 'Info', 'RC5', 0x01, 0x12),
            ('guide', 'Guide', 'RC5', 0x01, 0x2F), ('netflix', 'Netflix', 'RC5', 0x01, 0x27), ('youtube', 'YouTube', 'RC5', 0x01, 0x29),
            ('prime_video', 'Prime Video', 'RC5', 0x01, 0x3E), ('digit_0', '0', 'RC5', 0x01, 0x00), ('digit_1', '1', 'RC5', 0x01, 0x01),
            ('digit_2', '2', 'RC5', 0x01, 0x02), ('digit_3', '3', 'RC5', 0x01, 0x03), ('digit_4', '4', 'RC5', 0x01, 0x04),
            ('digit_5', '5', 'RC5', 0x01, 0x05), ('digit_6', '6', 'RC5', 0x01, 0x06), ('digit_7', '7', 'RC5', 0x01, 0x07),
            ('digit_8', '8', 'RC5', 0x01, 0x08), ('digit_9', '9', 'RC5', 0x01, 0x09), ('red', 'Red', 'RC5', 0x01, 0x37),
            ('green', 'Green', 'RC5', 0x01, 0x36), ('yellow', 'Yellow', 'RC5', 0x01, 0x32), ('blue', 'Blue', 'RC5', 0x01, 0x34),
            ('play', 'Play', 'RC5', 0x01, 0x19), ('pause', 'Pause', 'RC5', 0x01, 0x31), ('stop', 'Stop', 'RC5', 0x01, 0x18),
            ('ff', 'Fast forward', 'RC5', 0x01, 0x1C), ('rew', 'Rewind', 'RC5', 0x01, 0x1B), ('record', 'Record', 'RC5', 0x01, 0x33),
            ('subtitle', 'Subtitles', 'RC5', 0x01, 0x1F), ('audio', 'Audio', 'RC5', 0x01, 0x0F), ('aspect', 'Picture size', 'RC5', 0x01, 0x0B),
            ('sleep', 'Sleep', 'RC5', 0x01, 0x2A), ('list', 'Channel list', 'RC5', 0x01, 0x26), ('last', 'Previous channel', 'RC5', 0x01, 0x22),
            ('text', 'Teletext', 'RC5', 0x01, 0x3C),
        ],
        "evidence": 'Every key is agreed by at least two independent remote captures (identical copies counted once) and outvotes any other value at least 2:1: 16 Flipper-IRDB file(s). Keys without that agreement are left out.',
        "sources": 'Flipper-IRDB d126fb1 captures (16 files) (factual values only, files not bundled)',
        "support": 'power 16, input 12, mute 16, vol_up 16, vol_down 16, ch_up 12, ch_down 13, up 15, down 16, left 14, right 15, ok 15, back 14, exit 12, home 5, menu 10, settings 3, info 6, guide 5, netflix 6, youtube 4, prime_video 3, digit_0 12, digit_1 12, digit_2 12, digit_3 11, digit_4 12, digit_5 12, digit_6 11, digit_7 12, digit_8 11, digit_9 11, red 8, green 8, yellow 7, blue 8, play 8, pause 8, stop 6, ff 3, rew 5, record 5, subtitle 5, audio 8, aspect 5, sleep 2, list 2, last 2, text 4',
    },
    {
        "id": 'fullremote-lg-soundbar', "category": 'AUDIO', "brand": 'LG', "models": ['All LG soundbars (full remote)'],
        "commands": [
            ('power', 'Power', 'Samsung32', 0x2C, 0x1E), ('input', 'Function', 'Samsung32', 0x2C, 0x8A), ('mute', 'Mute', 'Samsung32', 0x2C, 0x1F),
            ('vol_up', 'Volume +', 'Samsung32', 0x2C, 0x17), ('vol_down', 'Volume -', 'Samsung32', 0x2C, 0x16), ('settings', 'Settings', 'Samsung32', 0x2C, 0x98),
            ('info', 'Info', 'Samsung32', 0x2C, 0xA3), ('digit_0', '0', 'Samsung32', 0x2C, 0x4B), ('digit_1', '1', 'Samsung32', 0x2C, 0x41),
            ('digit_2', '2', 'Samsung32', 0x2C, 0x42), ('digit_3', '3', 'Samsung32', 0x2C, 0x43), ('digit_4', '4', 'Samsung32', 0x2C, 0x44),
            ('digit_5', '5', 'Samsung32', 0x2C, 0x45), ('digit_6', '6', 'Samsung32', 0x2C, 0x46), ('digit_7', '7', 'Samsung32', 0x2C, 0x47),
            ('digit_8', '8', 'Samsung32', 0x2C, 0x48), ('digit_9', '9', 'Samsung32', 0x2C, 0x49), ('stop', 'Stop', 'Samsung32', 0x2C, 0x05),
            ('next', 'Next', 'Samsung32', 0x2C, 0x07), ('prev', 'Previous', 'Samsung32', 0x2C, 0x06), ('sleep', 'Sleep', 'Samsung32', 0x2C, 0xC2),
            ('play', 'Play / Pause', 'Samsung32', 0x2C, 0x4F),
        ],
        "evidence": 'Every key is agreed by at least two independent remote captures (identical copies counted once) and outvotes any other value at least 2:1: 8 Flipper-IRDB file(s) and 2 probonopd/irdb file(s). Keys without that agreement are left out.',
        "sources": 'Flipper-IRDB d126fb1 captures (8 files); probonopd/irdb 11aa5eb codes/LG (2 files) (factual values only, files not bundled)',
        "support": 'power 10*, input 4*, mute 10*, vol_up 9*, vol_down 8*, settings 2, info 5*, digit_0 3*, digit_1 3*, digit_2 3*, digit_3 3*, digit_4 3*, digit_5 3*, digit_6 3*, digit_7 3*, digit_8 3*, digit_9 3*, stop 2*, next 3, prev 3, sleep 2*, play 4',
    },
    {
        "id": 'fullremote-sony-soundbar', "category": 'AUDIO', "brand": 'Sony', "models": ['All Sony soundbars (basic remote)'],
        "commands": [
            ('power', 'Power', 'SIRC15', 0x30, 0x15), ('input', 'Input', 'SIRC15', 0xB0, 0x69), ('mute', 'Mute', 'SIRC15', 0x30, 0x14),
            ('vol_up', 'Volume +', 'SIRC15', 0x30, 0x12), ('vol_down', 'Volume -', 'SIRC15', 0x30, 0x13), ('up', 'Up', 'SIRC15', 0xB0, 0x78),
            ('down', 'Down', 'SIRC15', 0xB0, 0x79), ('ok', 'OK', 'SIRC15', 0x30, 0x0C), ('back', 'Back', 'SIRC20', 0x110, 0x7D),
            ('audio', 'Sound field', 'SIRC20', 0x110, 0x12),
        ],
        "evidence": 'Every key is agreed by at least two independent remote captures (identical copies counted once) and outvotes any other value at least 2:1: 3 Flipper-IRDB file(s). Keys without that agreement are left out.',
        "sources": 'Flipper-IRDB d126fb1 captures (3 files) (factual values only, files not bundled)',
        "support": 'power 2, input 2, mute 3, vol_up 3, vol_down 3, up 3, down 3, ok 3, back 2, audio 2',
    },
]
# Apple TV: Apple's NEC variant (address 0x87EE; command byte = function << 1 | parity, high byte = remote id). Values are the
# Apple_TV_4K_Gen2 capture (remote id 0x8C); every function number is confirmed by probonopd/irdb codes/Apple/Apple TV
# (MENU 1, RIGHT 3, LEFT 4, UP 5, DOWN 6, ENTER 46, PLAY/PAUSE 47) and by four more Flipper-IRDB Apple TV captures with
# other remote ids, which share the same parity rule (odd parity over the command and id bytes).
TV_REMOTES.append({
    "id": "fullremote-apple-tv", "category": "TV_BOX", "brand": "Apple", "models": ["All Apple TV models (IR remote)"],
    "commands": [
        ("up", "Up", "NECext", 0x87EE, 0x8C0A), ("down", "Down", "NECext", 0x87EE, 0x8C0C),
        ("left", "Left", "NECext", 0x87EE, 0x8C09), ("right", "Right", "NECext", 0x87EE, 0x8C06),
        ("ok", "Select", "NECext", 0x87EE, 0x8C5C), ("back", "Menu / Back", "NECext", 0x87EE, 0x8C03),
        ("play", "Play / Pause", "NECext", 0x87EE, 0x8C5F),
    ],
    "evidence": "Apple remote protocol (NEC timing, address 0x87EE). Function numbers agree between probonopd/irdb and 5 Flipper-IRDB "
                "Apple TV captures; values are one complete capture (remote id 0x8C). An Apple TV paired to its own remote may "
                "ignore other remote ids.",
    "sources": "Flipper-IRDB d126fb1 Streaming_Devices/Apple captures (5 files); probonopd/irdb 11aa5eb codes/Apple/Apple TV "
               "(factual values only, files not bundled)",
    "support": "up 5*, down 5*, left 5*, right 5*, ok 3*, back 5*, play 4*",
})


# yes (Israel) satellite box, older remote: probonopd/irdb 11aa5eb codes/YES/Unknown_YES (LIRC-derived, NEC device 132,
# subdevice 60), mapped as IrdbCsvImporter.mapIrdb does (NECext, command byte + its inverse). Function 51 is a second "0"
# in the source and is left out because it is ambiguous. Single source: the Flipper-IRDB copy is converted from the same file.
def _yes(n, label, f):
    return (n, label, "NECext", 0x3C84, f | ((f ^ 0xFF) << 8))


TV_REMOTES.append({
    "id": "fullremote-yes-stb", "category": "TV_BOX", "brand": "yes", "models": ["yes satellite box (older remote)"],
    "commands": [
        _yes("power", "Power", 33), _yes("mute", "Mute", 37), _yes("vol_up", "Volume +", 38), _yes("vol_down", "Volume -", 39),
        _yes("ch_up", "Channel +", 53), _yes("ch_down", "Channel -", 55), _yes("up", "Up", 43), _yes("down", "Down", 36),
        _yes("left", "Left", 52), _yes("right", "Right", 59), _yes("ok", "OK", 58), _yes("exit", "Exit", 49),
        _yes("info", "Info", 46), _yes("yes_key", "yes", 35), _yes("help", "Help (?)", 44), _yes("mosaic", "Mosaic", 54),
        _yes("plus", "+", 60), _yes("messages", "Messages", 67), _yes("orange", "Orange", 62), _yes("green", "Green", 45),
        _yes("yellow", "Yellow", 61), _yes("blue", "Blue", 63), _yes("key_i", "I", 64), _yes("key_a", "A", 65), _yes("key_b", "B", 66),
        _yes("digit_0", "0", 47), _yes("digit_1", "1", 48), _yes("digit_2", "2", 41), _yes("digit_3", "3", 57), _yes("digit_4", "4", 40),
        _yes("digit_5", "5", 32), _yes("digit_6", "6", 56), _yes("digit_7", "7", 50), _yes("digit_8", "8", 42), _yes("digit_9", "9", 34),
    ],
    "evidence": "One remote table from LIRC (via probonopd/irdb), NEC protocol, device 132 / subdevice 60. Not cross-checked by a "
                "second independent capture; newer yes boxes (yes+, yes Max) may use another remote.",
    "sources": "probonopd/irdb 11aa5eb codes/YES/Unknown_YES (LIRC-derived; factual values only, file not bundled)",
    "support": "all keys 1 (irdb)",
})

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


def write_tv_remotes(out, entries):
    os.makedirs(os.path.join(out, "generated"), exist_ok=True)
    for r in TV_REMOTES:
        cmds = [{"name": n, "label": label, "kind": "parsed", "protocol": p, "address": a, "command": c}
                for n, label, p, a, c in r["commands"]]
        names = [c["name"] for c in cmds]
        frames = [(c["protocol"], c["address"], c["command"]) for c in cmds]
        assert len(names) == len(set(names)) and len(frames) == len(set(frames)), r["id"] + " has duplicates"
        protocols = sorted({c["protocol"] for c in cmds})
        protocol_id = protocols[0] if len(protocols) == 1 else "mixed"
        name = r["id"] + ".json"
        with open(os.path.join(out, "generated", name), "w", encoding="utf-8", newline="\n") as f:
            json.dump({
                "formatVersion": 1, "id": r["id"], "protocolId": protocol_id, "category": r["category"],
                "brand": r["brand"], "models": r["models"], "commands": cmds,
                "source": {"repository": "Universal IR protocol table", "url": FULL_REMOTE_URL,
                           "license": "Factual protocol values", "attribution": r["sources"]},
                "verification": "SOURCE_DISCOVERED", "notes": [r["evidence"], "Agreeing files per key (* = also in irdb): " + r["support"]],
            }, f, ensure_ascii=False, indent=1)
        # "All ..." titles sort before "Flipper-IRDB ..." / "SmartIR ..." in CatalogDao.codeSetRows (ORDER BY title).
        entries.append({
            "id": r["id"], "category": r["category"], "brand": r["brand"], "models": r["models"],
            "asset": f"catalog/generated/{name}", "format": "canonical", "fingerprintProtocol": protocol_id,
            "sourceUrl": FULL_REMOTE_URL, "title": r["models"][0], "repository": r["sources"],
            "license": "Factual protocol values", "evidence": r["evidence"], "extraBrands": r.get("extraBrands", []),
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
    # rc-geekbox.c is GPL-2.0-only, which cannot be combined with the GPL-3.0 app, so it is not bundled.
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
    write_tv_remotes(out, entries)
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
