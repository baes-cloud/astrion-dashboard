# Hardware & voice troubleshooting

## Voice: a command says "Activated" but nothing happens

Home Assistant matches an utterance against **exposed entity names** before it
falls back to built-in intents. If any scene/script is named the same as the
phrase, it wins — and if that entity is a cloud scene (Tuya "Tap-to-Run",
etc.) HA reports `Activated` + success even when the cloud side does nothing.

Seen here: a Tuya scene named *"Turn off all lights"* shadowed the built-in
`HassTurnOff` intent, so the phrase activated an inert cloud scene instead of
turning off 46 lights. The fix is to stop exposing the offending entity to
Assist (Settings → Voice assistants → Expose), **not** to delete it — Tuya
entities come back on the next integration reload.

Diagnose without speaking a word:

```bash
curl -s -X POST -H "Authorization: Bearer $HA_TOKEN" -H "Content-Type: application/json" \
  -d '{"text":"turn off all lights","language":"en"}' \
  "$HA_URL/api/conversation/process" | python3 -m json.tool
```

`response.data.success[]` names exactly which entity HA acted on. If it's a
scene you didn't expect, that's your shadow. A healthy result reads
`"Turned off all of the lights"`, not `"Activated"`.

Note the app is only ever a relay here — it streams your audio and renders
HA's reply verbatim, so "says something but does nothing" is virtually always
an HA intent/exposure issue rather than an app bug.

---

# Capturing real Samsung IR codes with a FLIRC

The codes in `IrBlaster.DEFAULT_CODES` are the widely-published Samsung TV
values and work on most sets. If a button does nothing in IR Mode, capture the
real code from your original Samsung remote and override it in config — **no
APK rebuild required**.

## Which codes are already built in

| Button | Hex | Confidence |
|---|---|---|
| POWER | `0xE0E040BF` | high — near-universal Samsung |
| VOLUME_UP | `0xE0E0E01F` | high |
| VOLUME_DOWN | `0xE0E0D02F` | high |
| MUTE | `0xE0E0F00F` | high |
| UP / DOWN | `0xE0E006F9` / `0xE0E08679` | high |
| LEFT / RIGHT | `0xE0E0A659` / `0xE0E046B9` | high |
| CENTER (Enter) | `0xE0E016E9` | high |
| BACK (Return) | `0xE0E01AE5` | high |
| HOME (Smart Hub) | `0xE0E09E61` | **medium** — varies by model year |
| PAGE_UP / PAGE_DOWN (Ch±) | `0xE0E048B7` / `0xE0E008F7` | high |

`HOME` is the one most likely to need re-capturing — Samsung moved Smart Hub /
Home around across model years. On a 2023+ Serif it may differ.

---

## Option A — FLIRC GUI (quickest)

The stock FLIRC app shows raw timings in its recorder, but the cleanest path is
the CLI, because it prints the decoded protocol **and** the hex in one line.

## Option B — `flirc_util` CLI (recommended)

1. Install FLIRC software (gives you `flirc_util`):
   - Linux: download from <https://flirc.tv/downloads> and install the `.deb`,
     or `sudo snap install flirc`.
2. Plug the FLIRC USB receiver in.
3. Confirm it's seen:
   ```bash
   flirc_util version
   ```
4. Put it into record/debug mode and point your **Samsung remote** at it, then
   press one button at a time:
   ```bash
   flirc_util sendir --help     # sanity check the binary works
   flirc_util record_api        # or: flirc_util ir_debug
   ```

The practical one-liner that dumps decoded protocol + hex as you press keys:

```bash
flirc_util ir_debug
```

Each press prints something like:

```
protocol: NEC  scancode: 0xE0E040BF  repeat: 0
```

`scancode` (or `hash`/`key`) is the value you want. Samsung is reported as
`NEC`, `NECx`, or `SAMSUNG` depending on FLIRC firmware version — all fine,
the 32-bit value is what matters.

### If flirc_util isn't available

`ir-keytable` on Linux with any LIRC-compatible receiver works too:

```bash
sudo ir-keytable -p all -t          # -p all enables every decoder
```

Press a Samsung button; you'll see lines like:

```
lirc protocol(necx): scancode = 0xe0e040bf
```

---

## Feeding captured codes back into the remote

Add the hex to the `ir_mode.codes` block in `/sdcard/astrion/dashboard.json`.
Keys are `HardwareKey` names; values may be `"0xE0E040BF"`, `"E0E040BF"`, or a
decimal number.

```json
{
  "ir_mode": {
    "codes": {
      "HOME": "0xE0E0D629",
      "POWER": "0xE0E040BF"
    }
  }
}
```

Push it and reopen the app (config is re-read on every foreground):

```bash
adb -s 10.0.1.141:5555 push dashboard.json /sdcard/astrion/dashboard.json
```

Any button **not** listed keeps its built-in default, so you only need to
override the ones that are actually wrong.

---

## Verifying the emitter itself

Two independent checks that the HA100's IR hardware is alive:

```bash
# 1. The HAL and service must exist (both confirmed present on this unit)
adb shell service list | grep consumer_ir
adb shell ls /system/lib/hw/ | grep consumerir
```

2. Open IR Mode on the remote — the popup prints a live capability line
   (`IR emitter ready · 38 kHz`, or an explicit failure) and echoes every
   hardware button press with the hex it transmitted. If it says
   `transmit FAILED`, the HAL rejected the pattern; if the TV simply doesn't
   respond, the code is wrong and needs capturing above.

3. Phone-camera trick: most phone front cameras see IR. Point the HA100's
   emitter at one and press a D-pad button in IR Mode — you should see a faint
   purple flicker. That isolates "emitter not firing" from "wrong code".
