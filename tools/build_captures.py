"""Builds one canonical code set per Flipper-IRDB remote capture for TVs, projectors and TV boxes ("capture-*").

Usage: python -I tools/build_captures.py <Flipper-IRDB checkout at d126fb1> <app/src/main/assets/catalog> [<probonopd/irdb checkout at 11aa5eb>]

With the irdb checkout, its typed tables (codes/<brand>/<TV|Projector|Satellite|Cable Box|Receiver|...>/<dev>,<sub>.csv,
JP1 / LIRC-derived) are added the same way ("capture-irdb-*"); tables without a device type are skipped.

Only the factual protocol values (protocol, address, command) of parsed signals are used, for the protocols the app can
send; raw captures and the files themselves are not bundled. A capture is kept when it has a power key and at least five
keys, and when no other capture or bundled set already has exactly the same signals. Button names are mapped to the
app's command names where known; other names keep their cleaned text. catalog.json is updated in place: entries with
ids "capture-*" are replaced. Run after build_catalog.py.
"""
import glob
import hashlib
import json
import os
import re
import sys

FOLDERS = {"TVs": "TELEVISION", "Projectors": "PROJECTOR", "Cable_Boxes": "TV_BOX", "Streaming_Devices": "TV_BOX", "DVB-T": "TV_BOX"}
EXTRA_FILES = {"_Converted_/IR_Plus/H/HB DIGITAL/MAG 250.ir": ("TV_BOX", "MAG (Infomir)", "MAG 250")}
# Folder names that are not brands, and brand folders shown under another name.
SKIP_BRANDS = {"BrandUnknown", "Hotels", "Universal", "Unknown", "Misc", "Generic"}
BRAND_NAMES = {"Mag": "MAG (Infomir)", "MAG": "MAG (Infomir)", "SkyQ": "Sky", "ContinentalEdison": "Continental Edison"}
SUPPORTED = {"NEC", "NECext", "Samsung32", "SIRC", "SIRC15", "SIRC20", "RC5", "RC5X", "RC6"}
URL = "https://github.com/Lucaslhm/Flipper-IRDB"
IRDB_URL = "https://github.com/probonopd/irdb"
IRDB_TYPES = {
    "TV": "TELEVISION", "Plasma": "TELEVISION", "LCD": "TELEVISION", "LCD TV": "TELEVISION", "HDTV": "TELEVISION", "Television": "TELEVISION",
    "Video Projector": "PROJECTOR", "Projector": "PROJECTOR", "LCD Projector": "PROJECTOR", "DLP Projector": "PROJECTOR",
    "Satellite": "TV_BOX", "Satelite": "TV_BOX", "Cable Box": "TV_BOX", "Cable": "TV_BOX", "DSS": "TV_BOX", "DVR": "TV_BOX",
    "Satelite DVR": "TV_BOX", "Satellite DVR": "TV_BOX", "HDTV Tuner": "TV_BOX", "Set Top Box": "TV_BOX", "IPTV": "TV_BOX",
    "Media Player": "TV_BOX", "Streaming Player": "TV_BOX",
    "Receiver": "AUDIO", "Amplifier": "AUDIO", "Multi-Zone Receiver": "AUDIO", "Sound Bar": "AUDIO", "Soundbar": "AUDIO",
    "Home Theater": "AUDIO", "Mini System": "AUDIO",
}
LICENSE = "Factual protocol values"


def le(s):
    return sum(int(x, 16) << (8 * i) for i, x in enumerate(s.split()))


def parse(path):
    out, blk = [], {}

    def flush():
        if blk.get("type") == "parsed" and "name" in blk and "protocol" in blk:
            try:
                out.append((blk["name"], blk["protocol"], le(blk["address"]), le(blk["command"])))
            except (KeyError, ValueError):
                pass
    for line in open(path, encoding="utf-8", errors="replace"):
        line = line.strip()
        if line.startswith("#"):
            flush()
            blk = {}
            continue
        if ":" in line:
            k, v = line.split(":", 1)
            blk[k.strip()] = v.strip()
    flush()
    res = []
    for n, p, a, c in out:
        if p == "NECext" and (a >> 8) == ((a & 0xFF) ^ 0xFF) and (c >> 8) == ((c & 0xFF) ^ 0xFF):
            p, a, c = "NEC", a & 0xFF, c & 0xFF  # NECext that is really plain NEC
        elif p == "NEC":
            a, c = a & 0xFF, c & 0xFF
        elif p in ("RC5", "RC5X") and c >= 0x80:
            c &= 0x7F  # some converted files keep the toggle/field flag in bit 7
        if p in ("RC5", "RC5X"):
            p = "RC5X" if c >= 64 else "RC5"
        if fits(p, a, c):
            res.append((n, p, a, c))
    return res


# Address and command ranges per protocol (values outside them cannot be sent).
RANGES = {"NEC": (8, 8), "NECext": (16, 16), "Samsung32": (8, 8), "SIRC": (5, 7), "SIRC15": (8, 7), "SIRC20": (13, 7),
          "RC5": (5, 6), "RC5X": (5, 7), "RC6": (8, 8)}


def fits(p, a, c):
    r = RANGES.get(p)
    return r is not None and 0 <= a < (1 << r[0]) and 0 <= c < (1 << r[1])


# ---- Button names -> the app's command names
_M = {}
def _a(key, *names):
    for n in names: _M[n] = key
_a("power", "power", "pwr", "power toggle", "on off", "onoff", "standby", "power on off", "tv power", "power tv", "on standby", "power toggle tv")
_a("power_on", "power on", "poweron", "discrete on", "on")
_a("power_off", "power off", "poweroff", "discrete off", "off")
_a("vol_up", "vol up", "vol+", "volume +", "volume up", "volup", "volume+", "vol +", "v+", "vol plus", "volume plus")
_a("vol_down", "vol dn", "vol down", "vol-", "volume -", "volume down", "voldown", "volume-", "vol -", "v-", "vol minus", "volume minus", "vol dwn")
_a("mute", "mute", "muting", "volume mute", "mute toggle")
_a("ch_up", "ch next", "ch up", "ch+", "channel +", "channel up", "chup", "channel+", "ch +", "chan up", "prog+", "p+", "program up", "pr+", "page up ch+", "channel next")
_a("ch_down", "ch prev", "ch down", "ch-", "channel -", "channel down", "chdown", "channel-", "ch -", "chan down", "prog-", "p-", "program down", "pr-", "channel prev")
_a("up", "up", "cursor up", "arrow up", "nav up", "dpad up", "up arrow", "navigation up", "c up")
_a("down", "down", "cursor down", "arrow down", "nav down", "dpad down", "down arrow", "navigation down", "c down", "arrow dn")
_a("left", "left", "cursor left", "arrow left", "nav left", "dpad left", "left arrow", "navigation left", "c left", "arrow lt")
_a("right", "right", "cursor right", "arrow right", "nav right", "dpad right", "right arrow", "navigation right", "c right", "arrow rt")
_a("ok", "ok", "enter", "select", "cursor ok", "cursor enter", "center", "ok enter", "enter ok", "dpad center", "confirm")
_a("back", "back", "return", "back return", "return back", "previous menu", "go back")
_a("home", "home", "smart hub", "smarthub", "smart home", "home menu", "smart", "my apps home")
_a("menu", "menu", "tv menu")
_a("settings", "settings", "setup", "setting", "tools", "quick settings", "q menu", "q.menu", "options", "option")
_a("exit", "exit")
_a("input", "input", "source", "sources", "input source", "tv av", "tv/av", "input select", "av", "ext", "inputs", "input tv", "tv video", "source input", "input src", "src")
_a("info", "info", "information", "display", "i", "info display")
_a("guide", "guide", "epg", "tv guide")
_a("red", "red", "a red", "color red")
_a("green", "green", "b green", "color green")
_a("yellow", "yellow", "c yellow", "color yellow")
_a("blue", "blue", "d blue", "color blue")
_a("play", "play")
_a("pause", "pause")
_a("play_pause", "play pause", "play/pause", "playpause")
_a("stop", "stop")
_a("ff", "ff", "fast forward", "fast fwd", "fastforward", "forward", "fwd", "fast fo", ">>")
_a("rew", "rew", "rewind", "fast back", "fast ba", "rwd", "<<")
_a("next", "next", "skip next", "skip fwd", "skip forward", "next track", ">>|", "skip+")
_a("prev", "prev", "previous", "skip prev", "skip back", "skip backward", "previous track", "|<<", "skip-")
_a("record", "record", "rec")
_a("netflix", "netflix")
_a("youtube", "youtube", "you tube")
_a("prime_video", "prime video", "prime", "amazon", "amazon prime", "primevideo", "prime vid")
_a("disney_plus", "disney", "disney+", "disney plus")
_a("subtitle", "subtitle", "subtitles", "sub title", "cc", "closed caption", "closed captions", "subt", "ad subt", "caption")
_a("audio", "audio", "audio track", "lang", "language", "mts", "sap", "i ii", "mts sap")
_a("sleep", "sleep", "sleep timer")
_a("aspect", "aspect", "ratio", "aspect ratio", "picture size", "zoom", "p size", "wide", "format", "screen")
_a("list", "list", "ch list", "channel list")
_a("last", "last", "recall", "pre ch", "prev ch", "flashback", "flash back", "q view", "jump", "prech", "previous channel")
_a("apps", "apps", "app", "applications", "all apps", "my apps")
_a("text", "text", "teletext", "ttx")
for d in range(10):
    _a(f"digit_{d}", str(d), f"digit {d}", f"num {d}", f"number {d}", f"key {d}", f"btn {d}", f"{d}{d}"[:1] + " ")

def canon(raw):
    k = raw.strip().lower()
    k = re.sub(r"[_\-]+", " ", k)
    k = re.sub(r"\s+", " ", k).strip()
    if k in _M: return _M[k]
    k2 = k.replace("/", " ").replace(".", " ")
    k2 = re.sub(r"\s+", " ", k2).strip()
    return _M.get(k2)

_LIRC = {
    "power": "power", "volumeup": "vol_up", "volumedown": "vol_down", "mute": "mute", "channelup": "ch_up", "channeldown": "ch_down",
    "up": "up", "down": "down", "left": "left", "right": "right", "ok": "ok", "enter": "ok", "select": "ok", "back": "back",
    "exit": "exit", "menu": "menu", "home": "home", "info": "info", "epg": "guide", "program": "guide", "red": "red", "green": "green",
    "yellow": "yellow", "blue": "blue", "play": "play", "pause": "pause", "playpause": "play_pause", "stop": "stop",
    "fastforward": "ff", "forward": "ff", "rewind": "rew", "record": "record", "next": "next", "previous": "prev",
    "subtitle": "subtitle", "audio": "audio", "language": "audio", "sleep": "sleep", "aspect_ratio": "aspect", "zoom": "aspect",
    "screen": "aspect", "last": "last", "list": "list", "setup": "settings", "option": "settings", "options": "settings",
    "text": "text", "netflix": "netflix", "tv": None, "video": None, "media": None, "source": "input", "input": "input",
    "power2": None, "sound": None, "config": "settings",
}
for _d in range(10): _LIRC[str(_d)] = f"digit_{_d}"
_canon0 = canon
def canon(raw):
    r = raw.strip()
    if r.upper().startswith(("KEY_", "BTN_")):
        return _LIRC.get(r[4:].lower())
    return _canon0(raw)


LABEL = {"power": "Power", "power_on": "Power on", "power_off": "Power off", "input": "Source", "mute": "Mute", "vol_up": "Volume +",
         "vol_down": "Volume -", "ch_up": "Channel +", "ch_down": "Channel -", "up": "Up", "down": "Down", "left": "Left", "right": "Right",
         "ok": "OK", "back": "Back", "exit": "Exit", "home": "Home", "menu": "Menu", "settings": "Settings", "info": "Info", "guide": "Guide",
         "apps": "Apps", "netflix": "Netflix", "youtube": "YouTube", "prime_video": "Prime Video", "disney_plus": "Disney+",
         "red": "Red", "green": "Green", "yellow": "Yellow", "blue": "Blue", "play": "Play", "pause": "Pause", "stop": "Stop",
         "play_pause": "Play / Pause", "ff": "Fast forward", "rew": "Rewind", "next": "Next", "prev": "Previous", "record": "Record",
         "subtitle": "Subtitles", "audio": "Audio", "aspect": "Picture size", "sleep": "Sleep", "list": "Channel list",
         "last": "Previous channel", "text": "Teletext"}
for _i in range(10):
    LABEL[f"digit_{_i}"] = str(_i)


def slug(t):
    return re.sub(r"[^a-z0-9]+", "-", t.lower()).strip("-")[:60]


def brand_of(folder):
    if folder in BRAND_NAMES:
        return BRAND_NAMES[folder]
    return folder.replace("_", " ").strip()


def model_of(fname, folder):
    m = os.path.splitext(fname)[0].replace("_", " ").replace("-", " ")
    m = re.sub(r"\s+", " ", m).strip()
    for p in (folder, brand_of(folder), folder.replace("_", " ")):
        if m.lower().startswith(p.lower() + " "):
            m = m[len(p) + 1:]
    if brand_of(folder) == "MAG (Infomir)" and m.lower().startswith("tvbox"):
        m = "MAG TV box remote"
    return m or "Remote"


def clean_label(raw):
    t = re.sub(r"_+", " ", raw).strip()
    return t[:1].upper() + t[1:] if t else raw


def frames_hash(frames):
    return hashlib.sha1(json.dumps(sorted(frames), default=str).encode()).hexdigest()


def irdb_rows(path):
    """(name, protocol, address, command) from an irdb CSV, mapped as IrdbCsvImporter.mapIrdb does."""
    import csv
    res = []
    with open(path, encoding="utf-8", errors="replace") as fh:
        for row in csv.reader(fh):
            if len(row) < 5 or row[0] == "functionname":
                continue
            name = ",".join(row[:-4])
            try:
                d, sd, f = int(row[-3]), int(row[-2]), int(row[-1])
            except ValueError:
                continue
            proto = row[-4].strip().upper()
            if proto in ("NEC", "NEC1", "NEC2"):
                m = ("NEC", d, f) if sd < 0 or sd == d ^ 0xFF else ("NECext", d | (sd << 8), f | ((f ^ 0xFF) << 8))
            elif proto in ("NECX1", "NECX2"):
                m = ("Samsung32", d, f) if sd < 0 or sd == d else None
            elif proto == "SONY12":
                m = ("SIRC", d, f)
            elif proto == "SONY15":
                m = ("SIRC15", d, f)
            elif proto == "SONY20":
                m = ("SIRC20", d | ((sd if sd >= 0 else 0) << 5), f)
            elif proto == "RC5":
                m = ("RC5X" if f >= 64 else "RC5", d, f)
            elif proto == "RC6":
                m = ("RC6", d, f)
            else:
                m = None
            if m and fits(*m):
                res.append((name, *m))
    return res


def main(flipper, out, irdb=None):
    cat_path = os.path.join(out, "catalog.json")
    cat = json.load(open(cat_path, encoding="utf-8"))
    entries = [e for e in cat["entries"] if not e["id"].startswith("capture-")]
    # Captures identical to a set that is already bundled (full remotes, copied Flipper files) are not added twice.
    seen = set()
    for e in entries:
        p = os.path.join(os.path.dirname(out), e["asset"])
        if e["format"] == "canonical" and os.path.exists(p):
            d = json.load(open(p, encoding="utf-8"))
            seen.add(frames_hash({(c.get("protocol"), c.get("address"), c.get("command")) for c in d["commands"]}))
    files = []
    for folder, cat_name in FOLDERS.items():
        for f in sorted(glob.glob(os.path.join(flipper, folder, "**", "*.ir"), recursive=True)):
            rel = os.path.relpath(f, flipper).replace(os.sep, "/")
            parts = rel.split("/")
            if len(parts) < 3 or parts[1] in SKIP_BRANDS:
                continue
            files.append((rel, f, cat_name, brand_of(parts[1]), model_of(parts[-1], parts[1])))
    if irdb:
        codes = os.path.join(irdb, "codes")
        for f in sorted(glob.glob(os.path.join(codes, "*", "*", "*.csv"))):
            rel = os.path.relpath(f, codes).replace(os.sep, "/")
            brand, kind, fname = rel.split("/")
            cat_name = IRDB_TYPES.get(kind)
            if not cat_name or brand.lower() in ("unknown", "generic", "universal"):
                continue
            dev, _, sub = os.path.splitext(fname)[0].partition(",")
            code = dev if sub in ("", "-1") else f"{dev}.{sub}"
            files.append(("irdb:" + rel, f, cat_name, brand.title() if brand.isupper() and len(brand) > 4 else brand, f"{kind} · code {code}"))
    for rel, (cat_name, brand, model) in EXTRA_FILES.items():
        f = os.path.join(flipper, rel)
        if os.path.exists(f):
            files.append((rel, f, cat_name, brand, model))
    gen = os.path.join(out, "generated", "captures")
    os.makedirs(gen, exist_ok=True)
    for old in glob.glob(os.path.join(gen, "*.json")):
        os.remove(old)
    new, ids = [], set()
    for rel, f, cat_name, brand, model in files:
        cmds, names, frames = [], set(), set()
        for raw, p, a, c in (irdb_rows(f) if rel.startswith("irdb:") else parse(f)):
            if p not in SUPPORTED:
                continue
            name = canon(raw) or ("key_" + slug(raw).replace("-", "_"))
            if name == "key_" or name in names or (p, a, c) in frames:
                continue
            names.add(name); frames.add((p, a, c))
            cmds.append({"name": name, "label": LABEL.get(name) or clean_label(raw), "kind": "parsed", "protocol": p, "address": a, "command": c})
        if not ({"power", "power_on"} & names) or len(cmds) < 5:
            continue
        h = frames_hash(frames)
        if h in seen:
            continue
        seen.add(h)
        from_irdb = rel.startswith("irdb:")
        sid = ("capture-irdb-" if from_irdb else "capture-") + slug(f"{brand}-{model}")
        while sid in ids:
            sid += "-2"
        ids.add(sid)
        protos = sorted({c["protocol"] for c in cmds})
        pid = protos[0] if len(protos) == 1 else "mixed"
        src_name = f"probonopd/irdb 11aa5eb (codes/{rel[5:]})" if from_irdb else f"Flipper-IRDB d126fb1 ({rel})"
        note = (f"One remote table from {src_name}. Only its protocol values are used; "
                "it is not cross-checked by a second source.")
        repo = "probonopd/irdb 11aa5eb (values only)" if from_irdb else "Flipper-IRDB d126fb1 (values only)"
        src_url = f"{IRDB_URL}/blob/11aa5eb/codes/{rel[5:].replace(' ', '%20')}" if from_irdb else f"{URL}/blob/d126fb1/{rel.replace(' ', '%20')}"
        with open(os.path.join(gen, sid + ".json"), "w", encoding="utf-8", newline="\n") as fh:
            json.dump({"formatVersion": 1, "id": sid, "protocolId": pid, "category": cat_name, "brand": brand, "models": [model],
                       "commands": cmds, "source": {"repository": repo, "url": IRDB_URL if from_irdb else URL, "license": LICENSE,
                                                    "attribution": rel},
                       "verification": "SOURCE_DISCOVERED", "notes": [note]}, fh, ensure_ascii=False, separators=(",", ":"))
        new.append({"id": sid, "category": cat_name, "brand": brand, "models": [model], "asset": f"catalog/generated/captures/{sid}.json",
                    "format": "canonical", "fingerprintProtocol": pid, "sourceUrl": src_url,
                    "title": f"{model} ({len(cmds)} keys)", "repository": repo.replace("(values only)", "(values only, file not bundled)"),
                    "license": LICENSE, "evidence": note, "extraBrands": []})
    cat["entries"] = entries + new
    with open(cat_path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(cat, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    by = {}
    for e in new:
        by[e["category"]] = by.get(e["category"], 0) + 1
    print(len(new), "captures", by, "brands", len({e["brand"] for e in new}))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
