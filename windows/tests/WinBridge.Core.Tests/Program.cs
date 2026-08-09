// Protocol conformance checks. Deliberately dependency-free and runnable with
// `dotnet run` so the same assertions can be replayed on CI or by hand while
// bringing up the Kotlin client.

using System.Net;
using System.Net.Sockets;
using WinBridge.Core.Protocol;
using WinBridge.Core.Tests;

var argv = Environment.GetCommandLineArgs();

// Generates the cross-language known-answer vectors consumed by the Kotlin
// unit tests.
if (argv.Contains("--vectors"))
{
    int at = Array.IndexOf(argv, "--vectors");
    string output = at + 1 < argv.Length && !argv[at + 1].StartsWith('-')
        ? Path.GetFullPath(argv[at + 1])
        : Path.Combine(FindRepoRoot(),
            "android", "core", "protocol", "src", "test", "resources", "protocol-vectors.json");
    Vectors.Emit(output);
    return 0;

    // Walk up for the directory that holds both halves of the project, rather
    // than counting "../" from wherever the runner happened to start.
    static string FindRepoRoot()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null)
        {
            if (Directory.Exists(Path.Combine(dir.FullName, "android")) &&
                Directory.Exists(Path.Combine(dir.FullName, "windows")))
                return dir.FullName;
            dir = dir.Parent;
        }
        throw new DirectoryNotFoundException("could not locate the repository root");
    }
}

// A bare protocol server with no providers, no tray and verbose logging, for
// bringing up the Kotlin client. Pair it with `adb reverse tcp:8737 tcp:8737`
// so the phone reaches it over USB and the network is out of the picture.
if (argv.Contains("--serve"))
{
    int at = Array.IndexOf(argv, "--serve");
    int port = at + 1 < argv.Length && int.TryParse(argv[at + 1], out int p) ? p : 8737;
    string pskArg = at + 2 < argv.Length ? argv[at + 2] : DebugServer.DefaultPskBase64;
    await DebugServer.RunAsync(port, pskArg);
    return 0;
}

int failures = 0;

await Check("handshake succeeds and messages flow both ways", async () =>
{
    byte[] psk = CryptoBox.RandomBytes(32);
    var (server, client) = await LoopbackPair();

    var serverTask = ProtocolSession.AcceptAsync(
        server, new LocalIdentity("win-1", "TEST-PC", "windows"), h => psk, Cancel());
    var clientTask = ProtocolSession.ConnectAsync(
        client, new LocalIdentity("droid-1", "Test Phone", "android"), psk, Cancel());

    using var s = await serverTask;
    using var c = await clientTask;

    Assert(s.PeerDeviceId == "droid-1", $"server saw peer {s.PeerDeviceId}");
    Assert(c.PeerDeviceId == "win-1", $"client saw peer {c.PeerDeviceId}");

    await s.SendJsonAsync(new VolumeState { Level = 42, Muted = true }, Cancel());
    var got = await c.ReceiveAsync(Cancel());
    Assert(got.JsonType == MessageTypes.StateVolume, $"type was {got.JsonType}");
    var vol = got.As<VolumeState>();
    Assert(vol.Level == 42 && vol.Muted, $"volume round trip gave {vol.Level}/{vol.Muted}");

    await c.SendJsonAsync(new AuthMessage { Confirm = "x" }, Cancel());
    var back = await s.ReceiveAsync(Cancel());
    Assert(back.JsonType == MessageTypes.Auth, "client->server json failed");
});

await Check("binary blobs survive the channel", async () =>
{
    byte[] psk = CryptoBox.RandomBytes(32);
    var (server, client) = await LoopbackPair();
    var st = ProtocolSession.AcceptAsync(server, new LocalIdentity("w", "w", "windows"), h => psk, Cancel());
    var ct = ProtocolSession.ConnectAsync(client, new LocalIdentity("a", "a", "android"), psk, Cancel());
    using var s = await st;
    using var c = await ct;

    byte[] art = CryptoBox.RandomBytes(64 * 1024);
    await s.SendBlobAsync("art:deadbeef", art, Cancel());
    var got = await c.ReceiveAsync(Cancel());

    Assert(got.Inner == InnerType.Blob, "not a blob");
    Assert(got.Body.AsSpan().SequenceEqual(art), "blob bytes differ");
});

await Check("a wrong pairing key is rejected, not silently accepted", async () =>
{
    var (server, client) = await LoopbackPair();
    var st = ProtocolSession.AcceptAsync(
        server, new LocalIdentity("w", "w", "windows"), h => CryptoBox.RandomBytes(32), Cancel());
    var ct = ProtocolSession.ConnectAsync(
        client, new LocalIdentity("a", "a", "android"), CryptoBox.RandomBytes(32), Cancel());

    bool rejected = false;
    try { using var c = await ct; }
    catch (ProtocolException) { rejected = true; }
    Assert(rejected, "client accepted a server that did not know the key");

    try { using var s = await st; } catch { /* server tears down too */ }
});

await Check("an unpaired device cannot connect at all", async () =>
{
    var (server, client) = await LoopbackPair();
    var st = ProtocolSession.AcceptAsync(
        server, new LocalIdentity("w", "w", "windows"), h => null, Cancel());
    var ct = ProtocolSession.ConnectAsync(
        client, new LocalIdentity("stranger", "stranger", "android"), CryptoBox.RandomBytes(32), Cancel());

    bool refused = false;
    try { using var s = await st; } catch (ProtocolException) { refused = true; }
    Assert(refused, "server let an unpaired device in");
    try { using var c = await ct; } catch { }
});

await Check("replayed frames are refused", async () =>
{
    byte[] psk = CryptoBox.RandomBytes(32);
    byte[] transcript = new byte[32];
    byte[] nonceC = CryptoBox.RandomBytes(16), nonceS = CryptoBox.RandomBytes(16);

    using var a = System.Security.Cryptography.ECDiffieHellman.Create(
        System.Security.Cryptography.ECCurve.NamedCurves.nistP256);
    using var b = System.Security.Cryptography.ECDiffieHellman.Create(
        System.Security.Cryptography.ECCurve.NamedCurves.nistP256);

    using var sender = CryptoBox.Derive(a, ProtocolSession.ExportPoint(b), nonceC, nonceS, psk, transcript, true);
    using var receiver = CryptoBox.Derive(b, ProtocolSession.ExportPoint(a), nonceC, nonceS, psk, transcript, false);

    byte[] first = sender.Seal("hello"u8);
    byte[] second = sender.Seal("world"u8);

    Assert(receiver.Open(first).AsSpan().SequenceEqual("hello"u8), "first frame corrupted");
    Assert(receiver.Open(second).AsSpan().SequenceEqual("world"u8), "second frame corrupted");

    bool replayRejected = false;
    try { receiver.Open(first); } catch (ProtocolException) { replayRejected = true; }
    Assert(replayRejected, "a replayed frame was accepted");
});

await Check("tampered ciphertext fails authentication", async () =>
{
    byte[] psk = CryptoBox.RandomBytes(32);
    byte[] transcript = new byte[32];
    byte[] nonceC = CryptoBox.RandomBytes(16), nonceS = CryptoBox.RandomBytes(16);

    using var a = System.Security.Cryptography.ECDiffieHellman.Create(
        System.Security.Cryptography.ECCurve.NamedCurves.nistP256);
    using var b = System.Security.Cryptography.ECDiffieHellman.Create(
        System.Security.Cryptography.ECCurve.NamedCurves.nistP256);
    using var sender = CryptoBox.Derive(a, ProtocolSession.ExportPoint(b), nonceC, nonceS, psk, transcript, true);
    using var receiver = CryptoBox.Derive(b, ProtocolSession.ExportPoint(a), nonceC, nonceS, psk, transcript, false);

    byte[] frame = sender.Seal("sensitive"u8);
    frame[12] ^= 0x01;

    bool caught = false;
    try { receiver.Open(frame); }
    catch (System.Security.Cryptography.AuthenticationTagMismatchException) { caught = true; }
    Assert(caught, "a modified frame was accepted");
    await Task.CompletedTask;
});

await Check("oversized frames are refused before allocation", async () =>
{
    var (server, client) = await LoopbackPair();
    var header = new byte[5];
    System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(header, uint.MaxValue);
    header[4] = (byte)FrameType.Hello;
    await client.WriteAsync(header);
    await client.FlushAsync();

    bool caught = false;
    try { await Framing.ReadAsync(server, Cancel()); }
    catch (ProtocolException) { caught = true; }
    Assert(caught, "a 4 GiB frame length was not rejected");
});

Console.WriteLine();
Console.WriteLine(failures == 0 ? "ALL CHECKS PASSED" : $"{failures} CHECK(S) FAILED");
return failures;

async Task Check(string name, Func<Task> body)
{
    try
    {
        await body();
        Console.WriteLine($"  PASS  {name}");
    }
    catch (Exception ex)
    {
        failures++;
        Console.WriteLine($"  FAIL  {name}");
        Console.WriteLine($"        {ex.GetType().Name}: {ex.Message}");
    }
}

static void Assert(bool condition, string message)
{
    if (!condition) throw new Exception(message);
}

static CancellationToken Cancel() => new CancellationTokenSource(TimeSpan.FromSeconds(15)).Token;

static async Task<(Stream Server, Stream Client)> LoopbackPair()
{
    var listener = new TcpListener(IPAddress.Loopback, 0);
    listener.Start();
    var port = ((IPEndPoint)listener.LocalEndpoint).Port;

    var clientSocket = new TcpClient();
    var connect = clientSocket.ConnectAsync(IPAddress.Loopback, port);
    var accepted = await listener.AcceptTcpClientAsync();
    await connect;
    listener.Stop();

    accepted.NoDelay = true;
    clientSocket.NoDelay = true;
    return (accepted.GetStream(), clientSocket.GetStream());
}
