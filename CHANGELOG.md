# Changelog

## 0.2.2

### New
- **Custom AC quick settings**: save the current AC setup under a name, as many as you like; long-press one to rename, update or delete it.
- **Get new buttons on existing remotes**: when a full remote exists for your device's brand, the remote offers it ("N more buttons available → Update"). Your name, room, look and layout stay.
- **Edit home** redesigned: icon hints, a size pill on each tile, and hidden devices listed at the bottom so you can show them again.
- **Delete row** in the layout editor (from a key's menu, or the × on an empty row).
- Keys you add in the layout editor are highlighted on the remote after saving.
- **Hold a button** to keep sending it, like a real remote (fast volume, hold-OK menus, hold-power). Add buttons to favorites with the star in the remote's top bar.
- **yes satellite box** remote (older yes remote, 35 keys) under TV box › yes.
- **Edit layout looks like the remote**: same button style, round D-pad and volume/channel pills while you edit.
- Code-set list redesigned: numbered compact cards, the model as the title, tap a card to test it.
- **Testing codes** starts with **Power**, you can switch the test key (Power, Mute, Vol +…), and the "Did the device respond?" step has a big send button and clear Yes / No.

### Fixed
- A button you added in Edit layout (e.g. Home) could stay hidden on the remote after saving.
- Wi-Fi devices shown as offline after the router gave them a new IP: the app now finds them again.
- Wi-Fi: longer waits and an automatic second try before "didn't answer in time", plus tips on what to check.
- The same Wi-Fi device could be saved twice.
- A removed TV box brand still showed in the device list.

## 0.2.1

### New
- **Signed release APK** (about 10 MB, optimized) instead of the debug build. Uninstall earlier test builds before installing.
- **Full TV remotes** with arrows, OK, Home, Back, media and app keys (Netflix, Prime Video…) for Samsung, LG, Sony, Philips, Toshiba, Hisense, Vizio, Hitachi, Insignia, Roku TV, Fire TV Edition, Vestel-made TVs, Apple TV, plus LG and Sony soundbars.
- **Edit home**: drag tiles, one tap to resize or hide; one-tap size and colour in the long-press menu.
- **Layout profiles**: save a remote layout under a name and load it later.
- **Button order** per remote (Smart TV / Classic TV); **IR repeat**; **Check for updates** reads GitHub releases.
- Press Back twice to exit; temperature unit applies to AC defaults; better tablets, foldables and large text.
- Project is now **GPL-3.0** with contributing guide, code of conduct, security policy and issue/PR templates.

### Fixed
- Buttons added in the layout editor with an arrow or OK action were hidden after saving.

## 0.2.0

### New
- **15 languages** (added French, Spanish, German, Portuguese, Italian, Russian, Turkish, Persian, Hindi, Chinese, Indonesian, Urdu).
- **Themes**: six accent colours, wallpaper colours (Android 12+), pure‑black dark mode; pick language and theme on the first screen.
- **Home filters** (All, Favourites, Wi‑Fi, rooms) replace the bottom bar; everything is reachable from Home.
- **IR repeat** setting (1–5 sends per press).
- **Wi‑Fi remotes**: Android TV / Google TV (pairing code), Samsung, LG, Roku; Wi‑Fi ACs (Gree/Tadiran, Daikin, Electra Smart, Sensibo); discovery and add by IP.
- **Remote type** setting: Automatic / Infrared + Wi‑Fi / Wi‑Fi only. Phones without an IR emitter show only Wi‑Fi devices.
- **Layout editor** for every remote: icons, labels, colours, shapes, sizes, drag, swap, undo/redo, auto layout.
- **Five button styles**: Classic, Modern, Minimal, Compact, Vivid.
- **Touchpad mode** for the D‑pad: swipe to move, tap for OK.
- **AC screen redesign**: draggable dial, mode/fan/feature tiles, presets, **off timer**; settings can be changed while the AC is off.
- **Home**: customisable title, layout and sections; tile sizes, icons and colours; drag to rearrange; Wi‑Fi devices on home.
- **Rooms** redesign with quick‑add suggestions, a room page and a device checklist.
- **Widgets**: device remote and favourites.
- **More codes**: 470 code sets (from 153), LED strips and bulbs, generic Android TV boxes (X96, Tanix, Beelink, Mecool and more).
- **Settings** redesign with version, update check and developer info; new introduction and loading screen.

### Fixed
- Dark mode showed black text on the loading and introduction screens.
- The standard remote placed keys off‑centre and on top of each other.
- Renaming put the cursor before the existing name.

## 0.1.0
- First version: infrared remotes for ACs, TVs, TV boxes, audio, projectors and fans; code wizard; importers; English / Arabic / Hebrew.
