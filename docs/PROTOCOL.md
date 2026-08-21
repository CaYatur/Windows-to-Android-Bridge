# WinBridge Wire Protocol v2

One protocol, two carriers. A carrier only has to provide an ordered, reliable
byte stream; the framing, session and message layers are identical over
Bluetooth RFCOMM and TCP.

- **Bluetooth**: RFCOMM, service UUID `b6b3a8f1-6f1a-4a5e-9c2d-7e4f1a2b3c4d`
- **LAN / remote**: TCP, default port `8737` (discovery: UDP `8738`)

Windows is always the **server**; Android is always the **client**.

---

## 1. Framing

```
+--------+--------+---------------------+
| len:u32| type:u8|      payload        |
+--------+--------+---------------------+
```

- `len` is big-endian and counts `type` + `payload`.
- Maximum frame size is 4 MiB. Anything larger is a protocol violation and
  closes the connection.

| type | name        | encryption | when            |
|------|-------------|------------|-----------------|
| 0x01 | `HELLO`     | plaintext  | handshake only  |
| 0x02 | `HELLO_ACK` | plaintext  | handshake only  |
| 0x03 | `SECURE`    | AES-256-GCM| after handshake |
| 0x04 | `BYE`       | plaintext  | orderly close   |

> **v2 keeps the handshake and key schedule byte for byte.** A 0.1.x phone and a
> 0.2.0 host still connect; they simply never exchange the new message types.
> The known-answer vectors are unchanged, which is what proves it.

After the handshake completes, **only** `SECURE` and `BYE` frames are legal.
Receiving a plaintext `HELLO`/`HELLO_ACK` mid-session closes the connection.

### 1.1 SECURE payload

```
+-------------+-------------------------------+
| counter:u64 |  ciphertext || GCM tag (16B)  |
+-------------+-------------------------------+
```

- Nonce = `noncePrefix (4B, from key schedule) || counter (8B big-endian)`.
- The counter is per-direction and **strictly increasing**. A frame whose
  counter is not greater than the last accepted one is dropped and the session
  is terminated (replay protection).
- If the counter would wrap, the session is closed and must be re-established.

Plaintext inside a `SECURE` frame:

```
+----------+------------------+
| inner:u8 |      body        |
+----------+------------------+
```

| inner | meaning                                    |
|-------|--------------------------------------------|
| 0x01  | UTF-8 JSON message (see §3)                |
| 0x02  | binary blob: `idLen:u8, id[idLen], data[]` |
| 0x03  | real-time media packet (see §7)            |
| 0x04  | bulk transfer chunk (see §8)               |

### 1.2 Send lanes

One socket carries control, media and bulk traffic, so the writer drains a
strict three-lane priority queue:

| lane | carries | policy |
|------|---------|--------|
| control | JSON, blobs, heartbeat, input | never dropped, always first |
| media | screen tiles, audio | bounded; drops oldest under pressure |
| bulk | file chunks | never dropped, flow-controlled, always last |

Sent naively this all starves: a touch event queued behind a 48 KB tile batch
arrives late, and a file transfer pushes the heartbeat toward the 15 s liveness
cutoff, so the link feels worse the more it is used.

**Sealing happens in the writer, not at the call site.** Assigning the AES-GCM
counter earlier and writing later would let a low-priority frame burn a counter
ahead of a control frame and trip the peer's replay check.

The media lane drops rather than blocks. A screen tile two frames old has
already been superseded, and waiting for it only adds latency someone can see.
The bulk lane never drops, so a transfer is paced by the link rather than by how
fast the disk reads.

---

## 2. Handshake

Both sides hold a 32-byte **PSK** established during pairing (§4).

### 2.1 Messages

Client → Server, `HELLO`:

```json
{ "v": 1, "deviceId": "…uuid…", "name": "Redmi Note 9 Pro",
  "platform": "android", "mode": "session",
  "ephPub": "<base64 P-256 uncompressed point, 65B>",
  "nonce":  "<base64 16B>" }
```

Server → Client, `HELLO_ACK`:

```json
{ "v": 1, "deviceId": "…uuid…", "name": "CAYA",
  "platform": "windows",
  "ephPub": "<base64 65B>", "nonce": "<base64 16B>",
  "confirm": "<base64 32B>" }
```

### 2.2 Key schedule

```
Z        = ECDH(P-256, ephPrivSelf, ephPubPeer)            // 32 bytes
salt     = nonceClient || nonceServer                      // 32 bytes
ikm      = Z || PSK                                        // 64 bytes
prk      = HKDF-Extract(SHA-256, salt, ikm)

k_c2s    = HKDF-Expand(prk, "winbridge/v1/key/c2s",   32)
k_s2c    = HKDF-Expand(prk, "winbridge/v1/key/s2c",   32)
np_c2s   = HKDF-Expand(prk, "winbridge/v1/nonce/c2s",  4)
np_s2c   = HKDF-Expand(prk, "winbridge/v1/nonce/s2c",  4)
k_cfm    = HKDF-Expand(prk, "winbridge/v1/confirm",   32)

confirmS   = HMAC-SHA256(k_cfm, "server" || transcript)
confirmC   = HMAC-SHA256(k_cfm, "client" || transcript)
```

The transcript is defined over canonical, length-prefixed components rather than
raw JSON bytes, so that two independent implementations cannot disagree about
serialization details (key order, whitespace, escaping):

```
chunk(x)   = len(x) as u16 big-endian || x

transcript = SHA-256(
    chunk("winbridge/v1")   ||
    chunk(deviceIdClient)   || chunk(ephPubClient) || chunk(nonceClient) ||
    chunk(deviceIdServer)   || chunk(ephPubServer) || chunk(nonceServer)
)
```

### 2.3 Sequence

1. Client sends `HELLO`.
2. Server replies `HELLO_ACK` carrying `confirmS`.
3. Client verifies `confirmS` in constant time. Mismatch ⇒ wrong PSK or an
   active attacker; abort without retrying.
4. Client's first `SECURE` frame is `{"t":"auth","confirm":"<confirmC>"}`.
   Server verifies in constant time. Mismatch ⇒ close.
5. Server pushes `state.host`, then the subscribed state (§3).

The exchange gives mutual authentication and forward secrecy: compromising the
PSK later does not decrypt recorded sessions, because `Z` is ephemeral.

---

## 3. Messages

Every JSON message carries a `t` (type) field.

### 3.1 Server → client

| `t`            | payload |
|----------------|---------|
| `state.host`   | `{name, os, uptimeSec, caps:{…}}` — `caps` lists which power actions this machine actually supports |
| `state.media`  | `{session, title, artist, album, appId, playing, posMs, durMs, canNext, canPrev, canSeek, artHash}` |
| `state.system` | `{cpu, ram:{usedMb,totalMb}, gpu:[{name,pct}], net:{upBps,downBps}, disk:[…], battery:{pct,charging,status,minutesLeft}}` |
| `state.volume` | `{level, muted}` |
| `evt.peer`     | cross-transport provisioning payload (§5) |
| `blob`         | inner type `0x02`, `id` = `art:<hash>` |
| `pong`         | `{echo}` |

`state.*` messages are **deltas**: fields that have not changed since the last
push for that type may be omitted. `req.state` forces a full snapshot.

### 3.2 Client → server

| `t`           | payload |
|---------------|---------|
| `auth`        | `{confirm}` — handshake step 4 |
| `sub`         | `{rates:{media:0, system:1000, volume:0}}` — ms interval per stream; `0` = push on change only; `-1` = unsubscribe |
| `req.state`   | `{}` — full resync |
| `req.blob`    | `{id:"art:<hash>"}` |
| `cmd.media`   | `{action:"play"\|"pause"\|"toggle"\|"next"\|"prev"\|"seek", posMs}` |
| `cmd.volume`  | `{action:"set"\|"mute"\|"unmute", level}` |
| `cmd.power`   | `{action:"lock"\|"sleep"\|"hibernate"\|"shutdown"\|"restart"\|"logoff"\|"display_off", delaySec}` |
| `ping`        | `{echo}` |

**The client drives the update rate.** The phone raises `system` to 1 Hz when a
UI screen is foregrounded and drops it to 30 s when only a widget is listening.
The server never guesses.

`cmd.power` actions absent from `state.host.caps` are rejected with an error
rather than silently ignored — the phone greys those buttons out.

### 3.3 Album art

Art is referenced by `artHash` (SHA-256 of the decoded image, hex, first 16
bytes). The client keeps a **permanent** hash-keyed cache and only issues
`req.blob` for a hash it has never seen. Art is downscaled to 256×256 JPEG on
the Windows side before hashing, which keeps a transfer near 10–20 KB — the
difference between usable and unusable over RFCOMM.

### 3.4 Liveness

App-level `ping`/`pong` every 5 s. Three missed `pong`s (15 s) ⇒ the client
declares the link dead and starts reconnecting. `TCP_NODELAY` is set on both
ends; Nagle on small control frames is the single biggest source of perceived
lag.

---

## 4. Pairing

Pairing establishes the 32-byte PSK. It is **always** initiated by the user on
the Windows side and is time-boxed to 60 seconds.

### 4.1 QR (primary)

The tray shows a QR encoding:

```json
{ "v":1, "psk":"<base64 32B>", "id":"<windows deviceId>", "name":"DESKTOP-01",
  "lan":{"host":"192.168.1.20","port":8737},
  "bt":{"mac":"AA:BB:CC:11:22:33","uuid":"b6b3a8f1-…"} }
```

The phone scans it and immediately holds the PSK. **The PSK never crosses the
network**, so this path has no online or offline attack surface beyond someone
physically photographing the screen.

> The `bt.mac` field must be the Windows **classic BR/EDR** address. See
> [ARCHITECTURE.md](ARCHITECTURE.md#adr-2) — Android will happily hand you an
> LE identity address for the same PC, and RFCOMM to that address always fails.

### 4.2 PIN (fallback)

For phones without a working camera, or manual entry:

1. Windows shows a 6-digit code.
2. Client sends `HELLO` with `"mode":"pair"`; the key schedule substitutes the
   UTF-8 PIN bytes for the PSK.
3. `confirmS` proves the server knows the PIN.
4. The server then sends the real 32-byte PSK inside the first `SECURE` frame.

**Known limitation, stated plainly:** an attacker who relays the full exchange
can mount an offline dictionary attack against a 6-digit code. This is why the
PIN path is constrained to a 60-second window, 5 attempts, a fresh code on every
retry, and — unless the user explicitly overrides it — connections from
non-private source addresses are refused while pairing is open. QR is preferred
precisely because it has none of these caveats. Implementing a true PAKE
(SPAKE2) would remove the limitation and is deliberately deferred.

---

## 5. Cross-transport provisioning

Once a session is authenticated over *either* carrier, the server sends
`evt.peer`:

```json
{ "t":"evt.peer",
  "bt":  {"mac":"AA:BB:CC:11:22:33", "uuid":"b6b3a8f1-…"},
  "lan": {"hosts":["192.168.1.20","10.0.0.4"], "port":8737} }
```

and the client replies with its own equivalent. Both sides persist the result
alongside the PSK. The effect is what the user asked for: pair once over
Bluetooth and LAN works automatically, or pair over LAN and Bluetooth works
automatically — no second pairing step.

The PSK is shared across carriers by design; it identifies the *device pair*,
not the link.

---

## 6. Feature negotiation

After auth, each side sends what it is willing and able to do right now — the
server as `evt.features`, the client as `cl.features` — and re-sends it whenever
a setting changes.

```json
{ "t":"evt.features", "v":2,
  "clipboard":{"send":false,"receive":false,"maxBytes":262144},
  "files":{"enabled":true,"maxChunk":49152,"autoAccept":false},
  "screen":{"send":true,"receive":true,"codecs":["jpeg-tiles"],"targets":2,"carrierOk":true},
  "audio":{"playback":false,"mic":false,"formats":["pcm_s16le"],"carrierOk":true},
  "input":{"send":true,"receive":false,"reason":"disabled in settings"},
  "automations":true, "shell":false, "notifications":false,
  "describe":true, "ring":true }
```

This exists for the same reason `state.host.caps` did in v1: the peer greys out
what is off rather than offering a button that silently does nothing. `carrierOk`
is false when the current carrier cannot carry that stream at all — see §7.

---

## 7. Media packets (inner `0x03`)

```
+--------+---------+--------+---------+--------+-----------+
| kind:u8|stream:u8| seq:u32| ts:u32  |flags:u8| payload   |
+--------+---------+--------+---------+--------+-----------+
```

`kind` is 1 for video and 2 for audio. `ts` is milliseconds since the stream
started. `flags` bit 0 is **keyframe**, bit 1 is **end of frame**.

| stream | id |
|---|---|
| `pc.screen` | 0 |
| `phone.screen` | 1 |
| `pc.audio` | 2 |
| `phone.audio` | 3 |
| `pc.mic` | 4 |
| `phone.mic` | 5 |

### 7.1 The video payload — `jpeg-tiles`

A frame is a list of changed tiles, repeated:

```
index:u16, len:u32, jpeg[len]
```

Tiles are 64x64. Geometry (`w`, `h`, `tileW`, `tileH`, `cols`, `rows`) comes from
`stream.info` before the first frame, so the receiver never has to infer it.
Tile *n* sits at column `n % cols`, row `n / cols`.

**Keyframe marks only the first packet of a frame.** It tells the receiver to
clear its canvas, so setting it on every packet of a split frame would have the
receiver wipe the tiles it painted a moment earlier.

A frame is split across packets at ~48 KB. The sender must reset the change
state of any tile whose packet was dropped, or a tile that never changes again —
a toolbar, a wallpaper edge — stays stale for the rest of the session.

**Why intra-only tiles rather than H.264.** A hardware H.264 encoder buffers
frames to build references: the bytes are far smaller, but the first byte of a
frame leaves later. Tiles have no reference state, so a frame is on the wire as
soon as it is grabbed, and a still desktop costs a few hundred bytes because
only the tiles whose content hash moved are sent. What it costs is bandwidth on
full-screen video — which is why these streams are refused over RFCOMM rather
than degraded, and why quality, then frame rate, then resolution walk themselves
down from what the receiver reports. That order is the one a viewer minds least:
softer edges bother people less than stutter, and stutter less than a picture
too small to read.

The codec is named in `stream.start`, so another one can be added without a
breaking change.

### 7.2 The audio payload

Raw PCM, format announced in `audio.start` / `audio.info` — `pcm_s16le`,
48 kHz stereo by default, ~20 ms per packet.

Uncompressed on purpose. At 48 kHz stereo that is 1.5 Mbit/s, which a LAN does
not notice, and it costs no encode or decode latency at either end. The entire
point is that the sound lines up with the picture.

### 7.3 Carrier limits

RFCOMM sustains roughly a megabit. Mirroring and audio are **refused** over
Bluetooth rather than degraded: a mirror that updates twice a second reads as a
broken feature, not a slow one. `screen.carrierOk` and `audio.carrierOk` say so
before anything is attempted.

---

## 8. Bulk chunks (inner `0x04`)

```
+----------+--------+--------+--------+
| xferId:u32| seq:u32|flags:u8| data  |
+----------+--------+--------+--------+
```

`flags` bit 0 marks the last chunk. Chunks are 48 KB.

The JSON side is `xfer.offer` -> `xfer.accept` / `xfer.reject` -> chunks ->
`xfer.done`, with `xfer.progress` and `xfer.cancel` available throughout.
Offers carry a `batch` id so a multi-file selection arrives as one thing.

Receivers must treat the offered relative path as hostile — it may have come
from a share sheet another app wrote — and refuse anything that resolves outside
the chosen folder.

---

## 8b. Clipboard fingerprints

`cb.set` carries a `hash` alongside the text. It is the **first 16 bytes of
SHA-256 over the UTF-8 text, lowercase hex** — 32 characters.

This is normative, not an implementation detail. Each side stores the
fingerprint of whatever it last wrote to its own clipboard, and drops an
incoming clipboard whose fingerprint matches: that is the only thing standing
between the two machines and an endless game of catch with the same string. Both
sides fingerprint the text they actually hold rather than trusting the value in
the message, so a peer that computes it differently is merely ignored instead of
starting a loop, and both test suites assert the same pinned vector:

```
"WinBridge clipboard fingerprint vector — panoyu paylaş 42"
  → cd00c6119b8e5ea05aeacacacb769e12
```

Images are fingerprinted after a round trip through the receiver's own PNG
encoder, because that is the byte sequence it will read back a moment later.

---

## 9. Message index (v2)

Clipboard: `cb.set`, `cb.get`

Files: `xfer.offer`, `xfer.accept`, `xfer.reject`, `xfer.progress`, `xfer.done`,
`xfer.cancel`

Screen: `screen.list`, `screen.targets`, `stream.start`, `stream.stop`,
`stream.info`, `stream.config`, `stream.stats`

Audio: `audio.start`, `audio.stop`, `audio.info`, `audio.devices`, `audio.route`

Input: `input.mouse`, `input.key`, `input.text`, `input.touch`, `input.gesture`,
`input.nav`, `input.scroll`

Automations: `auto.list`, `auto.catalog`, `auto.get`, `auto.def`, `auto.save`,
`auto.saved`, `auto.delete`, `auto.run`, `auto.event`, `auto.result`,
`auto.cancel`, `auto.log`

Notifications: `notif.post`, `notif.remove`, `notif.action`, `notif.dismiss`,
`notif.sync`, `notif.state`

Machine: `sys.windows`, `sys.windowlist`, `sys.window`, `sys.processes`,
`sys.processlist`, `sys.process`, `sys.describe`, `sys.description`,
`sys.notify`, `sys.open`, `phone.ring`, `phone.state`

Coordinates in every input message are **normalised 0..1 against the streamed
surface**, never pixels: the sender is looking at a scaled copy and should not
have to know the receiver resolution, DPI, or which monitor the pointer landed
on.

Both implementations are pinned together by one filled-in sample of every
message in `protocol-vectors.json`, decoded and asserted field by field on the
Kotlin side. A property renamed on one side without the other fails the build by
name — without that check it would surface much later as a value silently
arriving as its default.

---

## 10. Transport selection

Both carriers may be connected at once. The active one is chosen by
configurable preference, defaulting to **Bluetooth** as requested.

Practical note: LAN sustains far higher throughput than RFCOMM. Bluetooth is
the better default for always-on presence and survives the phone leaving Wi-Fi;
LAN is better the first time a large album-art cache is being filled. The
preference is exposed in settings rather than decided for the user.

Failover is event-driven, never polled — Android `NetworkCallback` for LAN,
bond/ACL broadcasts for Bluetooth — with exponential backoff plus jitter.
