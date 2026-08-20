# Architecture

```
┌──────────────────── Windows (user session) ────────────────────┐
│  WinBridge.App  (WPF, tray, autostart at logon)                 │
│                                                                 │
│   Providers          Feature services        Carriers           │
│   ─────────          ────────────────        ────────           │
│   Media   (GSMTC)    Clipboard   Screen   ┌─ RFCOMM  (WinRT)    │
│   System  (PDH)      Files       Audio    ├─ TCP     (sockets)  │
│   Volume  (CoreAudio) Input      SysQuery └─ UDP discovery      │
│   Power   (Win32)    Automations Notifications                  │
│                            │                                    │
│                   ClientSession ─► priority queue ─► Crypto     │
└─────────────────────────────────────────────────────────────────┘
                              ▲   ▲
                    RFCOMM ───┘   └─── TCP (LAN, or WAN if forwarded)
                              ▼   ▼
┌──────────────────────── Android phone ──────────────────────────┐
│  BridgeService (foreground, connectedDevice)                    │
│    ├─ BridgeClient      (LAN preferred from 0.2.0, BT opt-in)   │
│    ├─ BridgeState       (StateFlow, single source of truth)     │
│    └─ PhoneSink         everything the PC asks us to *do*       │
│                                                                 │
│  CaptureService (mediaProjection|microphone)  screen + audio    │
│  RemoteInputService (accessibility)           touch injection   │
│  NotificationRelay  (listener)                mirroring         │
│                                                                 │
│    Compose UI    Overview / Media / System / Automations / More │
│    Glance widgets  media · stats · combined · power · automations│
└─────────────────────────────────────────────────────────────────┘
                              ▲
                   Wearable Data Layer (via phone)
                              ▼
                    ┌──── Wear OS watch ────┐
                    │  separate APK, tiles  │
                    └───────────────────────┘
```

---

## ADR-1 — The Windows side is a tray app, not a Windows Service

**Decision.** Ship a user-session WPF application that auto-starts at logon via
`HKCU\…\Run`, not a service registered with the SCM.

**Why this is not a preference.** Windows Services run in Session 0, which is
isolated from the interactive desktop. From Session 0 you cannot:

- read the media session (`GlobalSystemMediaTransportControlsSessionManager`
  is per-user and returns nothing),
- read or set the audio endpoint volume (CoreAudio is per-session),
- call `LockWorkStation`, or broadcast `WM_SYSCOMMAND / SC_MONITORPOWER`.

Every headline feature of this project lives in the interactive session. A
service would need a session-resident helper anyway, at which point the service
adds installation friction and an IPC hop for nothing.

**Consequence.** The bridge is available once a user is logged in. Wake-on-LAN
and pre-login control are out of scope.

---

## ADR-2 — RFCOMM via WinRT, addressed by classic BR/EDR MAC

**Decision.** Use WinRT `RfcommServiceProvider` + `StreamSocketListener` on
Windows. Do not take a dependency on 32feet.NET. On Android, always reach the PC
via `adapter.getRemoteDevice(<classic MAC>)` supplied by the pairing payload.

**Evidence.** Measured against a real Android 12 handset with an unpackaged
.NET 10 console process (addresses below are illustrative):

```
bondState=12 (BONDED)  type=1 (CLASSIC)
CONNECTED to AA:BB:CC:11:22:33          ~136 ms
RX: PONG-FROM-WINDOWS                   ~185 ms round trip
```

The commonly cited concern that Win32-without-package-identity cannot publish a
usable SDP record did not reproduce. The record is live and Android's SDP lookup
resolves it.

**The trap this cost us, recorded so nobody re-learns it.** An initial sweep over
`BluetoothAdapter.bondedDevices` failed against every device including the PC.
The PC appears in that list under its **LE identity address** (e.g.
`7A:1B:2C:3D:4E:5F` — note the locally-administered bit) while its **classic**
address is a different, IEEE-assigned one. RFCOMM is a BR/EDR protocol; opening
it against an LE address fails with the famously uninformative
`read failed, socket might closed or timeout, read ret: -1`.

So: never discover the peer by name-matching or by sweeping bonded devices. The
Windows side knows its own classic address (`BluetoothAdapter.BluetoothAddress`)
and puts it in the pairing payload. This is also why `evt.peer` (PROTOCOL §5)
carries `bt.mac` explicitly.

**Prerequisite we cannot remove.** OS-level Bluetooth pairing must be done once
in Windows Settings. Neither side can initiate bonding programmatically.

---

## ADR-3 — One PSK per device pair, shared across carriers

Pairing yields a 32-byte pre-shared key that identifies the *device pair*, not
the link. Each session then runs ephemeral ECDH authenticated by that PSK, so
sessions have forward secrecy and either carrier can be brought up without a
second pairing ceremony. See PROTOCOL §2 and §5.

The LAN listener may be deliberately exposed to the internet by users who
forward the port, so the channel is encrypted and mutually authenticated from
the first byte after the handshake — this is not optional hardening.

---

## ADR-4 — The client owns the update rate

The phone sends `sub` with a per-stream interval. Foreground UI asks for 1 Hz
system metrics; when only a widget is listening it asks for 30 s; media is
always push-on-change because GSMTC gives us events.

Pushing 1 Hz to a Glance widget would be throttled by the system anyway and
would cost battery for updates nobody sees. Making the rate a client decision
rather than a Windows-side heuristic keeps that policy where the knowledge is.

---

## ADR-5 — Intra-only JPEG tiles, not H.264

**Decision.** Screen streams are a list of changed 64×64 JPEG tiles. The codec
is negotiated in `stream.start`, so another can be added, but this is what 0.2.0
ships.

**Why, given H.264 exists and is smaller.** The requirement was minimum latency,
and the two are in tension. A hardware H.264 encoder buffers frames to build
references: excellent bytes-per-frame, but the first byte of a frame leaves
later, and the decoder adds its own reorder delay. Tiles have no reference state
at all — a frame is on the wire as soon as it is grabbed, and decodes as soon as
it lands.

There is a second reason, less principled but real: an H.264 path on Windows
needs Media Foundation transforms for *both* encode and decode, hand-rolled
through COM. That is the one piece of this project most likely to consume all
the effort and produce nothing testable.

**What it costs.** Bandwidth on full-screen video. A still desktop with a
blinking cursor is a few hundred bytes a frame because only tiles whose content
hash moved are sent; a fullscreen film is megabits. That is why the streams
refuse RFCOMM outright (ADR-7) and why the encoder adapts.

**Consequence.** Quality, then frame rate, then resolution walk down from what
the *receiver* reports, in that order — softer edges bother a viewer less than
stutter, and stutter less than a picture too small to read. Adaptation is driven
by `stream.stats` from the receiver rather than the sender's own guesses,
because only the receiver knows how old a frame was when it was painted.

---

## ADR-6 — One socket with priority lanes, not a second connection

**Decision.** Media and bulk traffic share the existing session, drained by a
single writer from a three-lane priority queue.

**Why not a second socket.** It would isolate cleanly, and it would also require
fresh HKDF labels for the new channel. Reusing the session keys with an
independent counter starting at zero reuses AES-GCM nonces — plaintext
disclosure plus forgery, not a theoretical nit. A second key schedule is a
change to the part of this project that is currently pinned by known-answer
vectors and known to be correct.

**Consequence.** The lanes have to do the isolation instead, and they do: media
is bounded and drops, bulk is flow-controlled and never drops, control always
goes first. Sealing moved into the writer so counters are assigned in wire
order — assigning earlier and writing later would let a bulk frame burn a
counter ahead of a heartbeat and trip the peer's replay check.

---

## ADR-7 — Mirroring and audio are refused over Bluetooth, not degraded

**Decision.** When the active carrier is RFCOMM, `stream.start` and `audio.start`
return `active: false` with a reason.

**Why.** RFCOMM sustains roughly a megabit. A usable mirror needs several. The
alternative to refusing is a stream that updates twice a second, which reads as
a broken feature rather than a slow one — and the user has no way to tell which.

**Consequence.** This is also why Bluetooth became opt-in in 0.2.0 (see
`BridgeSettings.Schema`). It remains the right carrier for presence, media and
control, and a poor one for a release whose headline features it cannot carry.
Existing installs are migrated to off once, and told so in the log.

---

## ADR-8 — Automations live on the PC; the phone is an editor

**Decision.** The definition is stored, hashed and approved on the Windows side.
The phone reads and writes it over the protocol and keeps no authoritative copy.

**Why this is not an implementation detail.** If the phone held the definition
and sent it at run time, the bytes a person approved and the bytes that executed
would be two different things, and every other control in the feature would be
decoration. Approval is bound to a hash of the executable body — steps and
variable defaults, deliberately not the name or colour — so renaming keeps
approval and editing a command loses it.

**Consequence.** The expression language is a purpose-built evaluator rather
than an embedded scripting engine, for the same reason: an automation arrives
from a phone, and handing that text to something that can reach the filesystem
or the network would make the sandbox question unanswerable. This grammar reads
variables, compares them and calls a fixed function list. There is nothing else
to express.

The confirmation dialog is shown the command line *after* interpolation. A
dialog reading `run {{cmd}}` teaches people that the answer is always yes.

---

## ADR-9 — The accessibility service is the ceiling for phone input

**Decision.** Touches from the PC are injected with
`AccessibilityService.dispatchGesture`.

**Why there is no better option.** An ordinary Android app cannot deliver an
input event outside its own window. The alternatives are root, or an ADB-pushed
helper running as the shell user — which is how scrcpy does it, and which needs
a computer to set up every reboot on many devices.

**What this does not do, stated so it is not discovered later.** It cannot touch
`FLAG_SECURE` windows (banking apps, DRM video). Each gesture carries tens of
milliseconds of dispatch overhead, so this is not scrcpy-grade. Text is injected
into the focused field rather than synthesised as key events.

**Consequence.** Three gates before anything is injected: the user's setting,
Android's grant, and an open mirroring session. An accessibility service that
would tap the screen whenever a packet arrived is not a thing worth granting,
and the third gate is what makes the permission proportionate to the feature.

---

## ADR-10 — OCR runs on Windows, not on the phone

**Decision.** "What is on my PC screen" is answered by `Windows.Media.Ocr` on
the host.

**Why.** The phone-side alternative is bundling a recognition model into the
APK — several megabytes for something the machine being described can already do
offline, with no extra dependency and no cost to a phone battery. It also means
the phone gets an answer whether or not it is currently mirroring.

**Consequence.** Windows editions without an OCR language pack degrade to window
titles plus a reason, rather than the feature failing to load.

---

## Repository layout

```
android/
  app/            phone app: UI, services, features, widgets
    feature/        clipboard, files, audio, voice, shortcuts, the sink
    service/        bridge, capture, accessibility, notifications, tiles
    ui/             Compose screens, PC viewer, transparent relays
  core/protocol/  framing, crypto, message models (pure Kotlin, unit-testable)
  wear/           Wear OS app + tiles (separate APK, same keystore)
windows/
  src/WinBridge.Core/  protocol mirror of core/protocol
  src/WinBridge.App/
    Features/         clipboard, files, screen, audio, input, OCR, notifications
    Features/Automation/  expression language, step executor, store
    Providers/        media, metrics, volume, power
    Server/           carriers, session router, host services
  installer/           Inno Setup script
docs/
```

## Known limitations

- **Wear OS ships as a second APK.** Play delivers watch APKs from a single
  bundle; GitHub Releases cannot. Both APKs are signed with the same keystore,
  which the Wearable Data Layer requires in order to talk between them.
- **The Windows installer is unsigned.** SmartScreen will warn on first run
  ("More info" → "Run anyway"). Code-signing certificates are a paid,
  identity-verified product.
- **PIN pairing is dictionary-attackable in principle.** Mitigated by a 60 s
  window and attempt limits; QR pairing has no such caveat. See PROTOCOL §4.2.
- **Screen sharing from the phone needs a tap every session.** Android asks for
  MediaProjection consent each time and there is no way to remember the answer.
  The PC-side viewer therefore opens with an explanation instead of waiting
  silently for a first frame.
- **There is no virtual sound card.** A device other apps can select is a signed
  kernel driver, not something a user-mode app can create. What the audio
  settings offer instead is a *route*: point phone audio at a virtual cable
  (VB-Audio, VAC) and the phone microphone becomes selectable as a recording
  device everywhere else on the system.
- **Assistants cannot be integrated with directly.** No public on-device API
  lets Gemini or Assistant hand a third-party app an arbitrary command. See
  [ASSISTANT.md](ASSISTANT.md) for what is offered instead.
- **Aggressive OEM battery managers** (Xiaomi/MIUI, Samsung, Huawei) will kill
  the foreground service unless it is exempted. The setup wizard walks the user
  through `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and, on Xiaomi, the
  separate Autostart toggle.
