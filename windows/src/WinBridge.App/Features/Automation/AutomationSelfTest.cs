using System.Text;
using System.Windows;
using WinBridge.App.Server;
using WinBridge.App.Storage;
using WinBridge.Core.Protocol;
using Model = WinBridge.Core.Protocol;

namespace WinBridge.App.Features.Automation;

/// <summary>
/// Saves an automation, runs it, and checks that it actually did what it said.
///
/// The bug this exists to catch is not a crash. A step whose operand never
/// arrives — because the phone's editor had no field to type it into — saves
/// cleanly, validates cleanly, runs cleanly, and quietly does nothing. From the
/// phone that is indistinguishable from a broken PC, and from the PC it is
/// indistinguishable from a phone that never sent anything. The only way to tell
/// is to run a real automation through the real save path and then look at the
/// clipboard.
///
/// Invoked with <c>WinBridge.exe --selftest-automation</c>, before the
/// single-instance check, so it works while the tray app is already running.
/// Pass <c>--keep</c> to leave the fixture in the store, which is how the same
/// automation gets onto a paired phone or watch for a device test.
/// </summary>
public static class AutomationSelfTest
{
    private const string Marker = "winbridge-selftest-";

    public static int Run(bool keep)
    {
        var report = new StringBuilder();
        bool ok = true;

        try
        {
            var store = new BridgeStore();

            var automations = new AutomationStore();
            var service = new AutomationService(store, automations);
            service.Host = HostServices.ForSelfTest(store, service);

            string expected = Marker + Guid.NewGuid().ToString("N")[..8];
            var fixture = Fixture(expected);

            // Through the real save path: normalisation, risk scoring and the
            // body hash approval is bound to all have to agree, or the run gate
            // refuses it later for reasons that have nothing to do with steps.
            var link = new SelfTestLink();
            var saved = service.SaveAsync(fixture, link, CancellationToken.None).GetAwaiter().GetResult();
            report.AppendLine($"save      {saved.State} id={saved.Id} risk={saved.Summary?.Risk ?? "?"}");

            if (saved.State != "saved")
            {
                report.AppendLine($"FAIL      the fixture was not saved: {saved.Reason}");
                return Finish(report, false);
            }

            var stored = automations.Find(saved.Id);
            if (stored is null || !stored.Approved)
            {
                report.AppendLine("FAIL      a safe automation should be approved on save");
                ok = false;
            }

            var events = new List<string>();
            var result = service.RunAsync(
                new AutoRunRequest { Id = saved.Id },
                link,
                e => events.Add(Describe(e)),
                CancellationToken.None).GetAwaiter().GetResult();

            report.AppendLine($"run       ok={result.Ok} steps={result.StepsRun} {result.Error}".TrimEnd());
            foreach (string line in events.Take(24)) report.AppendLine($"  · {line}");

            if (!result.Ok)
            {
                report.AppendLine("FAIL      the run did not finish cleanly");
                ok = false;
            }

            // The point of the whole exercise: not "did it run" but "did the
            // clipboard change".
            string? actual = null;
            RunOnStaThread(() => actual = new ClipboardBridge().Read()?.Text);
            report.AppendLine($"clipboard {(actual is null ? "(unreadable)" : actual)}");

            if (actual != expected)
            {
                report.AppendLine($"FAIL      expected the clipboard to hold \"{expected}\"");
                ok = false;
            }

            // clip.get feeds a variable, which is the other half of the pair and
            // the part an automation reads back.
            if (!result.Variables.TryGetValue("readback", out string? readback) || readback != expected)
            {
                report.AppendLine($"FAIL      clip.get stored \"{readback ?? "(nothing)"}\"");
                ok = false;
            }
            else
            {
                report.AppendLine($"readback  {readback}");
            }

            if (keep)
            {
                report.AppendLine($"kept      \"{stored?.Name}\" left in the store for device testing");
            }
            else
            {
                automations.Delete(saved.Id);
                report.AppendLine("cleanup   fixture removed");
            }
        }
        catch (Exception ex)
        {
            report.AppendLine($"FAIL      {ex.GetType().Name}: {ex.Message}");
            ok = false;
        }

        return Finish(report, ok);
    }

    private static string Describe(AutoEvent e) =>
        $"{e.Phase} {e.StepType ?? e.StepId} {e.Message}".Replace("  ", " ").Trim();

    private static int Finish(StringBuilder report, bool ok)
    {
        report.AppendLine($"RESULT    {(ok ? "ok" : "FAILED")}");
        Console.Error.Write(report.ToString());
        return ok ? 0 : 1;
    }

    /// <summary>
    /// An automation that touches both clipboard steps and a conditional, so a
    /// missing operand shows up as a wrong value rather than a silent no-op.
    /// </summary>
    private static Model.Automation Fixture(string expected) => new()
    {
        Id = "selftest-clipboard",
        Name = "Clipboard self-test",
        Description = "Writes a marker to the clipboard and reads it back.",
        Enabled = true,
        RequireUnlocked = false,
        Steps =
        [
            new AutoStep { Type = StepTypes.ClipboardSet, Text = expected },
            new AutoStep { Type = StepTypes.ClipboardGet, Name = "readback" },
            new AutoStep
            {
                Type = StepTypes.If,
                Condition = "readback == \"\"",
                Then = [new AutoStep { Type = StepTypes.Log, Text = "clipboard came back empty" }],
            },
        ],
    };

    /// <summary>
    /// The clipboard is an STA API and this runs before any WPF application
    /// exists, so there is no dispatcher to borrow.
    /// </summary>
    private static void RunOnStaThread(Action action)
    {
        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try { action(); }
            catch (Exception ex) { failure = ex; }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        thread.Join();
        if (failure is not null) throw failure;
    }

    /// <summary>Stands in for a phone. Nothing is sent anywhere.</summary>
    private sealed class SelfTestLink : IPeerLink
    {
        public string PeerName => "self-test";
        public string PeerDeviceId => "selftest";
        public string Carrier => "none";

        public Task SendJsonAsync<T>(T message, CancellationToken ct) => Task.CompletedTask;
        public Task SendBlobAsync(string id, ReadOnlyMemory<byte> data, CancellationToken ct) => Task.CompletedTask;
        public Task SendXferAsync(XferChunk chunk, CancellationToken ct) => Task.CompletedTask;
        public bool TrySendMedia(in MediaPacket packet) => true;
    }
}
