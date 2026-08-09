# WinBridge — Windows ↔ Android Bridge

Monitor and control your Windows PC from your phone. Now playing, CPU/GPU/RAM/
network/battery as **home screen widgets**; lock, sleep, shut down and volume
control in one tap. Connects over **Bluetooth** or **LAN** — pair once, both
work.

🇹🇷 [Türkçe README](README.tr.md)

> **Status:** in development. Items checked off below are implemented and
> verified on real hardware.

---

## What it does

**See on your phone**

- Now playing: title, artist, album, cover art, position/duration, source app
- CPU, GPU, RAM, network (up/down) and disk usage
- Battery percentage and charging state (on laptops)
- Volume level and mute state

**Control from your phone**

- Play / pause / next / previous / seek
- Volume and mute
- Lock, sleep, hibernate, sign out, restart, shut down
- Turn off the display *(where the system supports it — the button is disabled
  on machines that don't)*

**Widgets** — media, system stats, combined, and power controls; usable
individually or together.

**Wear OS** — media and system status on the watch, relayed through the phone.

---

## Connectivity

| Method | When | Note |
|---|---|---|
| **Bluetooth (RFCOMM)** | Default preference | No Wi-Fi needed, works anywhere |
| **LAN (TCP)** | Same network | Much higher throughput — better for cover art |
| **Remote** | If you forward the port on your PC | Channel is end-to-end encrypted |

Pair over one method and the other is provisioned **automatically**: the peer's
address and key are exchanged over the channel that is already trusted.

---

## Install

### Windows

1. Download `WinBridge-Setup-x.y.z.exe` from [Releases](../../releases).
2. Run it. No administrator rights required.
3. The UI opens once on first install; after that it lives in the system tray.
4. It starts automatically when you sign in.

> **SmartScreen warning:** the installer is unsigned (code-signing certificates
> are a paid product). Choose "More info" → "Run anyway".

### Android

1. Download `app-release.apk` from the same page and install it (you'll need to
   allow installation from unknown sources).
2. Open the app and follow the setup wizard.
3. Choose **Pair** from the Windows tray menu and scan the QR code.

**If you want Bluetooth:** pair the phone from Windows Settings first, the
normal way. This step cannot be done programmatically — it is an operating
system requirement on both sides.

### Wear OS watch

The watch app is a separate APK (`wear-release.apk`). Outside the Play Store it
has to be installed onto the watch directly:

```bash
adb connect <watch-ip>:5555
adb -s <watch-ip>:5555 install wear-release.apk
```

The watch app works through the phone, so WinBridge must be installed there too.

---

## Language

The apps ship in **English** and **Turkish**, following your device language.
Any other language falls back to English.

---

## Security

- Pairing generates a 32-byte pre-shared key, delivered by QR code, which
  **never crosses the network**.
- Every session additionally derives an ephemeral ECDH (P-256) key, so recorded
  traffic cannot be decrypted later even if the pre-shared key leaks (forward
  secrecy).
- All traffic is AES-256-GCM encrypted with mutual authentication.
- Pairing mode only opens when you start it, and only for 60 seconds.

Details: [docs/PROTOCOL.md](docs/PROTOCOL.md)

---

## Development

```bash
# Android
cd android && ./gradlew :app:assembleDebug
```

```bash
# Windows
cd windows && dotnet build
```

Requirements: JDK 17+, Android SDK 36, .NET 10 SDK, Inno Setup 6 (for the
installer).

Architecture decisions and the reasoning behind them:
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

---

## License

MIT
