# Voice, assistants, and what is actually possible

The most-asked-for thing in this project is some version of:

> "Hey Gemini, bring that window to the front on my PC."

This document says plainly which parts of that can be built, which cannot, and
what WinBridge does instead. It is written first because the honest answer is
short and the workarounds are long.

---

## The short answer

**There is no public on-device API that lets Gemini or Google Assistant hand a
third-party app an arbitrary command.** There is no MCP-style extension surface
on Android that a normal app can register with, and no way to say "route
sentences that mention my PC to me". So the direct integration — you speak a
free-form sentence, the assistant parses it, your app receives the meaning —
cannot be built. Not with an API key, and not without one.

What *is* reachable, with no key and no account:

| Route | What it gives you | How reliable |
|---|---|---|
| **App shortcuts** | Each approved automation becomes a launcher shortcut the assistant can open by name | Deterministic to publish; voice invocation varies by device |
| **Assistant routines** | A routine step of "open app shortcut" runs an automation | Works wherever routines do |
| **In-app / on-watch voice** | `RecognizerIntent` + local matching, no key | Works on any device with a recogniser |
| **Intent API** | Tasker, MacroDroid, Bixby routines, `adb shell am broadcast` | Fully deterministic |
| **Home screen + Quick Settings** | Widget, two tiles | Fully deterministic |
| **Mirror + "what's on my screen"** | Ask the phone's own assistant about the mirrored PC screen | Works as well as that feature does |

That last one is worth calling out. The phone's built-in screen-understanding
does not know anything about your PC — but if the PC screen is *being mirrored
onto the phone*, then it is on the phone's screen, and asking about it works.
No integration, no key. It is a trick rather than a feature, but it is a good
one.

---

## What WinBridge ships

### 1. Automations as shortcuts

Every automation that is **enabled and approved on the PC** is published as a
dynamic shortcut (`ShortcutManagerCompat`, capped by what the launcher will
hold). They appear on long-press of the app icon, can be dragged to the home
screen, and can be targeted by an assistant routine.

Unapproved automations are deliberately not published: a shortcut that looks
like a button and does nothing when pressed is worse than no shortcut.

Turn this off in **More → Assistant and shortcuts**.

> **Verify voice invocation on your own phone.** Whether saying a shortcut's
> name aloud reaches it depends on the device and on whether Gemini has replaced
> Assistant there. The shortcut itself is deterministic; the voice path is not
> something this project can promise.

### 2. Speak a command

**Tell my PC** (in the app, on the watch, and as a shortcut) opens the device's
own speech recogniser. Nothing is sent to a server by WinBridge — whatever your
chosen recogniser already does is between you and it — and the matching happens
on the phone.

The matcher is deliberately small:

1. **Automation names first.** If you named one "goodnight", saying goodnight
   runs it, even if the phrase also contains a built-in keyword.
2. **Built-in phrases** — lock, sleep, shut down, restart, sign out, screen off,
   play, pause, next, previous, mute, volume up/down/*number*, "what is on
   screen", "windows". Turkish equivalents are matched too.
3. **Window names.** Anything left that mentions an open window or process is
   treated as "bring that forward", which is what such a sentence almost always
   means.

A fuzzy name match needs two thirds of the words to line up. That threshold is
not arbitrary: "open the browser on my PC" must not fire "close the browser".
When nothing matches with enough confidence, it says so rather than guessing —
the tenth guess might be a shutdown.

Every alternative the recogniser returns is tried, best first, because
recognisers routinely put the right words second when a command contains an app
name they do not know.

### 3. The intent API

For Tasker, MacroDroid, Bixby routines, or a shell script:

```bash
adb shell am broadcast \
  -a com.cayatur.winbridge.RUN_AUTOMATION \
  -n com.cayatur.winbridge/.service.TriggerReceiver \
  --es token YOUR_TOKEN \
  --es id AUTOMATION_ID
```

| Action | Extras |
|---|---|
| `com.cayatur.winbridge.RUN_AUTOMATION` | `token`, `id` |
| `com.cayatur.winbridge.SEND_CLIPBOARD` | `token` |
| `com.cayatur.winbridge.COMMAND` | `token`, `text` — parsed like a spoken command |

**The token is not optional and not decoration.** An exported broadcast receiver
with no secret is a way for any app you install to run commands on your PC. The
token is generated on first run, shown in **More → Assistant and shortcuts**,
and can be regenerated there. The whole API is off by default.

### 4. Describing the PC screen

**What is on screen** asks the PC to read itself and sends back the foreground
window title, the process, the list of open windows, and the on-screen text.

The OCR runs on **Windows**, through the recogniser already in the operating
system. That is not laziness about the phone side — putting it there means no
model ships inside the APK, it works whether or not you are mirroring, and it
costs nothing on a phone battery. Where Windows has no OCR language pack
installed, it degrades to window titles and says why.

The answer is spoken aloud if **Speak the answer** is on, using the device's
text-to-speech. On the watch, the same command shows the answer on the wrist.

---

## What was considered and rejected

**App Actions with custom built-in intents.** Registering a custom capability in
`shortcuts.xml` is the closest thing to a real assistant integration, but it
requires an upload to the Play Console to take effect for anything but local
testing, and Google has retired several of the behaviours it depended on. A
feature that only works for developers with a Play listing is not a feature this
project can ship.

**Bundling a recognition model in the APK.** Several megabytes for something the
PC can already do offline.

**Bring-your-own API key.** Would work, and would make the free-form case much
better. It is deliberately not in 0.2.0: a key in an app's settings is a
credential to look after, and every other feature here works without one. If it
is added later it will be opt-in and clearly separated from the paths that need
no account.

**Reading the assistant's output.** Technically reachable through an
accessibility service. That would mean watching every window on the device to
scrape one, which is a wildly disproportionate permission for the convenience,
and exactly the kind of thing an accessibility service should not be used for.
