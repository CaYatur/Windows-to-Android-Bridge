# WinBridge — Windows ↔ Android Bridge

Your PC and your phone, treated as one machine. Mirror either screen onto the
other with sound and touch control, move files and clipboard both ways, route
audio and microphones between them, and build automations that run on the PC —
from the phone, the watch, a widget or your voice.

🇹🇷 [Türkçe README](README.tr.md)

> **Status:** [v0.2.0](../../releases/latest). See
> [what is verified](#what-has-actually-been-run) below — this release is large
> and the parts that need two machines and a stopwatch are called out honestly.

---

## What it does

### Screens

- **PC screen on the phone**, with touch control, keyboard, scrolling,
  pinch-zoom and PC audio alongside it.
- **Phone screen on the PC**, with mouse, keyboard and the navigation buttons —
  and phone audio alongside it.
- Both adapt themselves: quality, then frame rate, then resolution walk down
  from what the *receiver* reports, so the picture degrades in the order a
  viewer minds least.
- Interaction and audio are each switchable while the session is running.

### Clipboard

- Phone → PC and PC → phone, **both off by default**, and each direction has a
  switch on *both* machines. Turning one on and not the other is the usual
  reason a clipboard "does not arrive"; each side now says so rather than
  dropping it in silence.
- Copies are sent as they happen. Since Android 10 the clipboard may only be
  read by the app that owns the focused window, so the phone tries in order: a
  direct read (free whenever WinBridge is in front), then a one-pixel focus
  window if "display over other apps" has been allowed, then the relay activity.
  The settings screen reports which rung is actually answering on that phone.
- Also reachable from a Quick Settings tile, a launcher shortcut, the watch, the
  share sheet, and **Get clipboard from phone** in the Windows tray menu.

### Files

- Both directions, any size, with progress, resume offsets and checksums.
- **Right-click → "Send to phone"** in Windows Explorer, for files and folders.
- The Android **share sheet** for anything on the phone.
- Received files land in Downloads, visible to every other app.

### Audio

- PC output → phone
- Phone output → PC
- Phone microphone → PC
- PC microphone → phone

Raw PCM, so there is no encode or decode delay between the sound and the picture.

### Automations

Build them on the phone, run them on the PC. Shell commands, windows, processes,
files, HTTP, input, media, power — with `if` / `else`, `while`, `repeat`,
`foreach`, variables and an expression language.

Security is the point of the design, not a layer on top:

- Shell steps do nothing until you enable them on the PC, in front of the warning.
- Approval is bound to a hash of the executable body — renaming keeps it,
  editing one character of a command loses it.
- The confirmation dialog shows the command line **after** variable substitution.
- Runs are bounded in steps, iterations, output and wall-clock.
- Every run is written to an append-only audit file.
- One switch refuses everything.

Details: [docs/AUTOMATIONS.md](docs/AUTOMATIONS.md)

### Notifications

Phone notifications mirrored to the PC, with reply and dismiss. **Off by
default** — it reads every notification on the phone, so it takes two deliberate
steps to enable.

### Voice and assistants

There is no public on-device API that lets Gemini or Assistant hand a
third-party app an arbitrary command, so that integration cannot be built —
by anyone, with or without an API key. What WinBridge offers instead: automations
published as launcher shortcuts, a local voice matcher using the device's own
recogniser, a documented intent API for Tasker and friends, and "what is on my
PC screen" answered by OCR on the Windows side and read aloud.

The honest, complete version: [docs/ASSISTANT.md](docs/ASSISTANT.md)

### Still here from 0.1.x

Now playing as a real Android media session, CPU/GPU/RAM/network/battery,
volume, power actions, six home screen widgets — including an **automation
button** you point at one automation when you place it — and the Wear OS app.

### Wear OS

Media and system status, **plus** running automations, a trackpad for the PC,
voice commands, "what is on my PC screen" read on the wrist, and a clipboard
button. Three tiles.

Automations are not *written* on a watch — a step tree is not something anyone
edits on a 45 mm screen — but they can be run from one three ways: the app's own
list, the automations tile one swipe from the watch face, and a **complication**
on the face itself, configured per slot, so the one routine you run constantly
is zero taps away.

---

## Connectivity

| Method | When | Note |
|---|---|---|
| **LAN (TCP)** | Default from 0.2.0 | Everything works |
| **Bluetooth (RFCOMM)** | Opt-in | Presence, media, control, notifications |
| **Remote** | If you forward the port | Channel is end-to-end encrypted |

**Bluetooth is off by default from 0.2.0**, and existing installs are migrated
once. It is still the right carrier for presence and control — it works with no
Wi-Fi and survives the phone leaving the network — but it sustains roughly a
megabit, and mirroring, audio and files of any size do not fit in that.
Those streams are **refused** over Bluetooth with a reason rather than degraded
into something that looks broken.

Pair over one method and the other is provisioned automatically.

---

## Install

### Windows

1. Download `WinBridge-Setup-x.y.z.exe` from [Releases](../../releases).
2. Run it. No administrator rights required.
3. It lives in the system tray and starts when you sign in.

> **SmartScreen warning:** the installer is unsigned (code-signing certificates
> are a paid, identity-verified product). Choose "More info" → "Run anyway".

### Android

1. Install `app-release.apk` from the same page.
2. Open the app, follow the setup wizard.
3. Choose **Pair** from the Windows tray menu and scan the QR code.

Two Android permissions cannot be requested from code and have to be granted in
Android settings. The app shows their state next to the switch that needs them:

- **Accessibility** — required only for the PC to control the phone. It is the
  only way to tap and type outside our own app without root.
- **Notification access** — required only for notification mirroring.

**If you want Bluetooth:** pair the phone from Windows Settings first, the
normal way, then enable it in WinBridge on both sides. This step cannot be done
programmatically — it is an operating system requirement on both sides.

### Wear OS watch

Separate APK (`wear-release.apk`), signed with the same key:

```bash
adb connect <watch-ip>:5555
adb -s <watch-ip>:5555 install wear-release.apk
```

---

## Security

- Pairing generates a 32-byte pre-shared key delivered by QR code, which **never
  crosses the network**.
- Every session derives an ephemeral ECDH (P-256) key, so recorded traffic
  cannot be decrypted later even if the pre-shared key leaks.
- All traffic is AES-256-GCM with mutual authentication and replay protection.
- Every new capability is **off by default** except the ones that only read what
  you were already sharing.
- The automation intent API is off by default and token-protected — an exported
  receiver with no secret is a way for any installed app to run commands on
  your PC.

Details: [docs/PROTOCOL.md](docs/PROTOCOL.md)

---

## What has actually been run

This release is large, and honesty about testing is worth more than a green tick.

**Verified by automated tests, on every build**

- The wire protocol, both implementations, pinned to each other by known-answer
  vectors and by one filled-in sample of all 58 v2 messages.
- Lane priority: control frames overtake a queued backlog of bulk traffic.
- Media packets are dropped rather than queued without bound.
- The clipboard fingerprint, pinned to the same vector in both languages.
- `WinBridge.exe --selftest-capture out.png` reassembles a real screen frame
  through the tile codec and the packet format.
- `WinBridge.exe --selftest-automation` saves an automation through the real
  save path, runs it, and checks that the clipboard actually changed.
- Both apps and the host compile clean.

**Verified by hand**

- See the release notes for v0.2.0 for exactly what was exercised on the
  physical PC, phone and watch, and what was not.

If something behaves differently on your hardware, the logs are the place to
start: the Activity tab on Windows, and `adb logcat -s WinBridge` on the phone.

---

## Development

```bash
cd android && ./gradlew :app:assembleDebug :wear:assembleDebug
```

```bash
dotnet build windows/src/WinBridge.App/WinBridge.App.csproj
dotnet run --project windows/tests/WinBridge.Core.Tests
```

Requirements: JDK 17+, Android SDK 36, .NET 10 SDK, Inno Setup 6 (installer).

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — the decisions and why
- [docs/PROTOCOL.md](docs/PROTOCOL.md) — the wire format
- [docs/AUTOMATIONS.md](docs/AUTOMATIONS.md) — steps, expressions, security
- [docs/ASSISTANT.md](docs/ASSISTANT.md) — voice, shortcuts, the intent API

---

## License

MIT
