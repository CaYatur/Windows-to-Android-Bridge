using System.Net;
using System.Net.Sockets;
using WinBridge.Core.Protocol;

namespace WinBridge.Core.Tests;

/// <summary>
/// The minimum server that still exercises the whole protocol: handshake, both
/// message directions, blobs, ping. No providers, no UI, no pairing state — so
/// when the Kotlin client misbehaves, there is exactly one thing under test.
/// </summary>
public static class DebugServer
{
    /// <summary>A fixed key so the phone can be pointed at this without pairing.</summary>
    public const string DefaultPskBase64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    public static async Task RunAsync(int port, string pskBase64)
    {
        byte[] psk = Convert.FromBase64String(pskBase64);
        var identity = new LocalIdentity(
            "22222222-2222-4222-8222-222222222222", Environment.MachineName, "windows");

        var listener = new TcpListener(IPAddress.Any, port);
        listener.Start();

        Console.WriteLine($"debug server on tcp/{port}");
        Console.WriteLine($"psk (base64): {pskBase64}");
        Console.WriteLine($"device id:    {identity.DeviceId}");
        Console.WriteLine("hint: adb reverse tcp:8737 tcp:8737, then connect to 127.0.0.1:8737");
        Console.WriteLine("waiting...");

        while (true)
        {
            var client = await listener.AcceptTcpClientAsync();
            client.NoDelay = true;
            _ = Task.Run(() => ServeOneAsync(client, identity, psk));
        }
    }

    private static async Task ServeOneAsync(TcpClient client, LocalIdentity identity, byte[] psk)
    {
        var remote = client.Client.RemoteEndPoint?.ToString() ?? "?";
        Console.WriteLine($"\n[{remote}] connected");

        ProtocolSession? session = null;
        var cts = new CancellationTokenSource(TimeSpan.FromMinutes(30));

        try
        {
            session = await ProtocolSession.AcceptAsync(
                client.GetStream(), identity,
                hello =>
                {
                    Console.WriteLine($"[{remote}] HELLO from {hello.Name} ({hello.Platform}) " +
                                      $"id={hello.DeviceId} mode={hello.Mode}");
                    return psk;
                },
                cts.Token);

            Console.WriteLine($"[{remote}] HANDSHAKE OK — authenticated as {session.PeerName}");

            await session.SendJsonAsync(new HostState
            {
                Name = Environment.MachineName,
                Os = Environment.OSVersion.VersionString,
                UptimeSec = Environment.TickCount64 / 1000,
                Caps = new PowerCaps { Sleep = true, Hibernate = false },
            }, cts.Token);

            // A changing value proves the phone is really re-rendering rather
            // than showing one stale snapshot.
            var ticker = Task.Run(async () =>
            {
                int level = 0;
                while (!cts.IsCancellationRequested)
                {
                    level = (level + 5) % 105;
                    await session.SendJsonAsync(
                        new VolumeState { Level = level, Muted = false }, cts.Token);
                    await session.SendJsonAsync(new SystemState
                    {
                        Cpu = Random.Shared.Next(5, 80),
                        Ram = new RamInfo { UsedMb = 9000, TotalMb = 32000 },
                        Gpu = [new GpuInfo { Name = "GPU", Pct = Random.Shared.Next(0, 60) }],
                        Net = new NetInfo { UpBps = 120_000, DownBps = 3_400_000 },
                        Battery = new BatteryInfo { Present = false, Status = "none" },
                    }, cts.Token);
                    await Task.Delay(1000, cts.Token);
                }
            }, cts.Token);

            while (!cts.IsCancellationRequested)
            {
                var message = await session.ReceiveAsync(cts.Token);
                Console.WriteLine($"[{remote}] <- {message.JsonType ?? "blob"} ({message.Body.Length}B)");

                if (message.JsonType == MessageTypes.Ping)
                    await session.SendJsonAsync(
                        new PongMessage { Echo = message.As<PingMessage>().Echo }, cts.Token);
            }

            await ticker;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[{remote}] ended: {ex.GetType().Name}: {ex.Message}");
        }
        finally
        {
            cts.Cancel();
            session?.Dispose();
            try { client.Dispose(); } catch { }
            Console.WriteLine($"[{remote}] closed");
        }
    }
}
