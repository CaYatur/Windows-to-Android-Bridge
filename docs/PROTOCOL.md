# WinBridge Wire Protocol v1

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

transcript = SHA-256( helloBytes || helloAckBytesWithConfirmFieldOmitted )
confirmS   = HMAC-SHA256(k_cfm, "server" || transcript)
confirmC   = HMAC-SHA256(k_cfm, "client" || transcript)
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

## 6. Transport selection

Both carriers may be connected at once. The active one is chosen by
configurable preference, defaulting to **Bluetooth** as requested.

Practical note: LAN sustains far higher throughput than RFCOMM. Bluetooth is
the better default for always-on presence and survives the phone leaving Wi-Fi;
LAN is better the first time a large album-art cache is being filled. The
preference is exposed in settings rather than decided for the user.

Failover is event-driven, never polled — Android `NetworkCallback` for LAN,
bond/ACL broadcasts for Bluetooth — with exponential backoff plus jitter.
