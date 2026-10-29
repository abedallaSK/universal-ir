# Hardware testing checklist — vivo X200 Ultra

## Recorded so far (2026-10-26)

| Check | Result | How |
|---|---|---|
| Phone identity | V2454A (PD2454), Android 16, OriginOS 6 | `adb shell getprop` |
| Declares `android.hardware.consumerir` | **Yes** | `adb shell pm list features` |
| App installs and starts | **Yes** (debug build) | USB install, needs on-phone approval on vivo |
| App detects a usable emitter | **Yes** (Home shows no "no IR emitter" banner) | In app |
| Carrier frequency ranges | *Not yet recorded* | Hardware diagnostics page |
| Any IR code controlled a real device | **Not yet tested** | — |

Emulator (no IR hardware): the app reports "does not declare an IR transmitter", never claims a send succeeded, and every screen remains usable.

## Checklist to complete on the phone

Record each result in the app (wizard / Test dialog "Yes/No" answers are stored with the phone model in *Verification history*) and copy the outcome into the table above.

1. **Emitter**: Settings → Hardware diagnostics. Note the carrier ranges shown.
2. **Visible burst**: tap *Send test burst* while filming the top edge with another phone's camera. A faint flash means the LED fires.
3. **Known TV code**: Add device → Television → your brand → **Test** on each code set (sends *mute* or *volume*). Answer Yes/No.
4. **Supported AC code** (pick the one matching your unit):
   - Tadiran with the older remote → *Tadiran 64-bit*
   - Tadiran 10i/15i/inv220a/Alpha → *Gree 64-bit*
   - Electra Classic/Smart → *Electra 104-bit*; Electra central → *Electra 34-bit*
   - Tornado Super Inverter / Electra Platinum Plus → *Coolix*
   - Kelon-made Electra/Tadiran → *KELON168*
5. **Complete state**: in the AC remote change mode, temperature and fan; confirm the unit's display follows each change.
6. **Multi-frame messages**:
   - KELON168 power-on (TURBO + state) and power-off (TURBO + toggle);
   - Electra 34 power-on (toggle + state).
   Confirm the unit ends in the requested state.
7. **Repeats**: press + several times quickly. Only the last temperature should be sent (350 ms debounce), and the unit should land on it.
8. **Aim and distance**: test at 1, 3 and 5 m, and at ±30°. Note where it stops working.
9. **Features**: swing, turbo, quiet/sleep, light. Mark which ones work.
10. **Toggle sync** (Electra 34): switch the unit with its own remote, then use *It is ON/OFF now* in the app and confirm the next command behaves.

When steps 3–5 pass for a code set, the app promotes that model's profile to **Level 3**. Level 4 needs step 9 documented for the important features.
