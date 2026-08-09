# Architecture

```
┌──────────────────── Windows (user session) ────────────────────┐
│  WinBridge.App  (WPF, tray, autostart at logon)                │
│                                                                 │
│   Providers            Session               Carriers           │
│   ─────────            ───────               ────────           │
│   Media   (GSMTC)  ┐                     ┌─ RFCOMM  (WinRT)     │
│   System  (PDH)    ├─► StateHub ─► Crypto├─ TCP     (sockets)   │
│   Volume  (CoreAudio)                    └─ UDP discovery       │
│   Power   (Win32)  ┘                                            │
└─────────────────────────────────────────────────────────────────┘
                              ▲   ▲
                    RFCOMM ───┘   └─── TCP (LAN, or WAN if forwarded)
                              ▼   ▼
┌──────────────────────── Android phone ──────────────────────────┐
│  BridgeService (foreground, connectedDevice)                    │
│    ├─ TransportManager  (BT preferred, LAN failover)            │
│    ├─ StateStore        (StateFlow, single source of truth)     │
│    ├─ Compose UI        Media / System / Control / Devices      │
│    └─ Glance widgets    media · stats · combined · power        │
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

## Repository layout

```
android/
  app/            phone app: UI, service, transports, widgets
  core/protocol/  framing, crypto, message models (pure Kotlin, unit-testable)
  core/transport/ RFCOMM + TCP carriers, discovery, reconnect
  wear/           Wear OS app + tiles (separate APK, same keystore)
windows/
  src/WinBridge.Core/  protocol mirror of core/protocol
  src/WinBridge.App/   WPF tray app, providers, carriers
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
- **Aggressive OEM battery managers** (Xiaomi/MIUI, Samsung, Huawei) will kill
  the foreground service unless it is exempted. The setup wizard walks the user
  through `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and, on Xiaomi, the
  separate Autostart toggle.
