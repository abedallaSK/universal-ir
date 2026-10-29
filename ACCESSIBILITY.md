# Accessibility

Universal IR should be usable by everyone, including people who use a screen reader, large text, high-contrast or one-handed setups, or a right-to-left language. This page describes what the app supports today, what is known not to work well yet, and how to report a barrier.

## Our commitment

- Accessibility problems are treated as **bugs**, not feature requests.
- New screens are built with Jetpack Compose semantics, Material 3 components and the rules below.
- We aim to follow the [WCAG 2.2](https://www.w3.org/TR/WCAG22/) level AA principles as they apply to mobile apps, and Android's [accessibility guidelines](https://developer.android.com/guide/topics/ui/accessibility).

## Supported environments

| | |
|---|---|
| **Platform** | Android 8.0 (API 26) and newer; phones, tablets and foldables, portrait and landscape |
| **Screen readers** | TalkBack (Android's built-in screen reader) |
| **Text size** | Follows the system font size; layouts grow with it |
| **Languages** | 15 languages, including right-to-left Arabic, Hebrew, Persian and Urdu |
| **Display** | Light, dark and pure-black themes; six accent colours, wallpaper colours or a custom colour |

## What the app does

- **Screen reader labels:** every remote key, the D-pad and touchpad arrows, device tiles and icon-only buttons have spoken labels (e.g. "Volume up", "Power"). Coloured keys (red / green / yellow / blue, LED colours) are announced by name, not only shown by colour.
- **Large text:** tiles, cards and dialogs grow with the system font size instead of cutting text off. Fixed-size controls (remote keys, the AC dial) cap the text scale so labels stay inside the button.
- **Touch targets:** remote keys are 56–96 dp, and other controls use Material 3 sizes (at least 48 dp). Button size can be set to Small, Medium or Large (Settings → Appearance).
- **Not by colour alone:** state is also shown with text or icons. For example, the AC shows "On · Cool" as well as the colour, and selected items show a check mark.
- **Contrast:** text on coloured buttons switches between black and white automatically, including for custom accent colours.
- **Feedback:** optional haptic feedback and click sound on every key press (Settings → Feedback).
- **Alternatives to gestures:** everything that uses drag or long-press also has a tap path. Layouts can be edited with the key editor sheet instead of dragging; device actions are also in Saved devices. The D-pad works as buttons or, optionally, as a swipe touchpad.
- **Right-to-left:** layouts mirror for RTL languages, except things that must not: D-pad directions, media transport and model numbers stay left-to-right.
- **No time limits:** nothing in the app expires or requires a fast response, except Android TV pairing codes, which come from the TV.

## Known limitations

We know about these and want to fix them. Help is welcome.

- **Wi-Fi remote screens** have their own text, so in languages other than English, Arabic and Hebrew some of their labels are still shown in English.
- **Drag-to-rearrange** (home tiles, layout editor) has no TalkBack custom actions such as "move up / move down" yet. Use the key editor or the long-press menu instead.
- **The AC temperature dial** is a drag gesture; screen-reader users should use the − / + buttons next to it (fully labelled).
- **Home-screen widgets** (Jetpack Glance) have not been fully tested with TalkBack.
- **Swipe touchpad** is gesture-only by design; switch the D-pad back to buttons for screen readers or switch access.
- **The app has not had a formal accessibility audit.** Testing so far has been on a limited set of phones.
- **Infrared needs line of sight.** This is a physical limit: the phone must point at the device.

## Report a barrier

If something is hard or impossible to use, please tell us:

1. Open an [**Accessibility barrier** issue](https://github.com/abedallaSK/universal-ir/issues/new?template=accessibility.yml). Describe what you tried, the assistive technology you use (TalkBack, font size, switch access, …), your phone and the app version.
2. If you prefer not to report publicly, contact the maintainer through <https://abedallask.netlify.app/>.

We aim to reply within 7 days and to prioritise barriers that block a task completely.

## For contributors

When you change the UI, please:

- give every icon-only control a `contentDescription` (or `semantics { contentDescription = … }`);
- test with TalkBack, the largest font size, and one RTL language (see the pull request checklist);
- never use colour as the only way to show state;
- keep touch targets at least 48 dp;
- put UI text in `tools/strings_source.py` so it can be translated.
