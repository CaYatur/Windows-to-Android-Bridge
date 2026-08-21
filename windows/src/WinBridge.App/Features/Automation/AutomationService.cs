using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.Text;
using WinBridge.App.Localization;
using WinBridge.App.Server;
using WinBridge.App.Storage;
using WinBridge.Core.Protocol;
using Model = WinBridge.Core.Protocol;

namespace WinBridge.App.Features.Automation;

/// <summary>What the user is being asked to allow, in the words they will see.</summary>
public sealed record ApprovalRequest(
    string Title,
    string AutomationName,
    string DeviceName,
    string Risk,
    IReadOnlyList<string> Lines,
    bool IsSaveApproval);

/// <summary>
/// Validates, stores and runs automations.
///
/// The threat this is built around is not a stranger on the network — the
/// protocol already handles that. It is that a phone in someone else's hand for
/// thirty seconds should not be able to turn into a shell on the PC. So:
///
///   * shell steps do nothing until the user turns them on here, in front of the
///     warning, and no message from a phone can flip that switch;
///   * approval is bound to a hash of the executable body, so editing a command
///     revokes it automatically;
///   * the confirmation dialog shows the command line *after* variable
///     substitution, because a dialog reading "run {{cmd}}" teaches people to
///     click through;
///   * runs are bounded in steps, iterations, output and wall-clock, so a loop
///     with a bad condition stops instead of pinning a core;
///   * everything is written to an append-only audit file.
/// </summary>
public sealed class AutomationService(BridgeStore store, AutomationStore automations)
{
    private readonly ConcurrentDictionary<string, CancellationTokenSource> _runs = new();

    public event Action<string>? Log;

    /// <summary>Set by the UI. Returning false refuses; a null handler means refuse.</summary>
    public Func<ApprovalRequest, Task<bool>>? Approve { get; set; }

    /// <summary>Wired after construction, because the services and this both live on the host.</summary>
    public HostServices? Host { get; set; }

    public AutomationStore Store => automations;

    // ---- catalogue ---------------------------------------------------------

    public AutoCatalog Catalog(string deviceId)
    {
        var settings = store.Settings.Automation;

        return new AutoCatalog
        {
            Items = [.. automations.All.Select(Summarise)],
            StepTypes = [.. StepTypes.All],
            ShellEnabled = settings.Shell && !settings.PanicStop,
            TrustMode = settings.TrustMode,
            DeviceTrusted = store.IsTrusted(deviceId),
            AuthoringAllowed = settings.Authoring && !settings.PanicStop,
            Allowlist = [.. settings.Allowlist],
            Functions = [.. Expressions.Functions],
            Variables = [.. BuiltInVariables],
        };
    }

    public static AutomationSummary Summarise(Model.Automation automation) => new()
    {
        Id = automation.Id,
        Name = automation.Name,
        Description = automation.Description,
        Icon = automation.Icon,
        Color = automation.Color,
        Enabled = automation.Enabled,
        Approved = automation.Approved,
        ConfirmEachRun = automation.ConfirmEachRun,
        StepCount = CountSteps(automation.Steps),
        Risk = automation.Risk ?? RiskOf(automation),
        UpdatedAt = automation.UpdatedAt,
    };

    private static int CountSteps(IReadOnlyList<AutoStep> steps) =>
        steps.Sum(step => 1 + CountSteps(step.Then) + CountSteps(step.Else) + CountSteps(step.Do));

    /// <summary>
    /// A label the phone can colour a card with. Coarse on purpose: three
    /// meaningful buckets people will actually read, rather than a score nobody
    /// can interpret.
    /// </summary>
    public static string RiskOf(Model.Automation automation)
    {
        bool shell = false, privileged = false, dangerous = false;

        void Walk(IReadOnlyList<AutoStep> steps)
        {
            foreach (var step in steps)
            {
                if (step.Type == StepTypes.Shell)
                {
                    shell = true;
                    if (LooksDestructive(step.Command)) dangerous = true;
                }
                if (step.Elevated) dangerous = true;
                if (StepTypes.Privileged.Contains(step.Type)) privileged = true;

                Walk(step.Then);
                Walk(step.Else);
                Walk(step.Do);
            }
        }

        Walk(automation.Steps);

        if (dangerous) return "dangerous";
        if (shell) return "shell";
        if (privileged) return "elevated-input";
        return "safe";
    }

    /// <summary>
    /// Patterns worth a second look before running unattended.
    ///
    /// Not a security boundary — anything here can be written another way, and
    /// pretending otherwise would be worse than useless. It exists to make the
    /// confirmation dialog say "this deletes things" instead of showing a command
    /// line the user skims past.
    /// </summary>
    private static bool LooksDestructive(string? command)
    {
        if (string.IsNullOrWhiteSpace(command)) return false;
        string lower = command.ToLowerInvariant();

        string[] markers =
        [
            "format ", "diskpart", "rd /s", "rmdir /s", "del /s", "del /q", "rm -rf",
            "remove-item -recurse", "-recurse -force", "cipher /w", "bcdedit",
            "vssadmin delete", "reg delete", "shutdown", "takeown", "icacls",
            "invoke-expression", "iex(", "iex ", "downloadstring", "invoke-webrequest",
            "set-executionpolicy", "net user", "schtasks /create",
        ];

        return markers.Any(marker => lower.Contains(marker, StringComparison.Ordinal));
    }

    public static readonly IReadOnlyList<string> BuiltInVariables =
    [
        "hostName", "user", "cpu", "ramUsedMb", "ramTotalMb", "ramPct", "battery",
        "charging", "volume", "muted", "activeWindow", "activeProcess", "time",
        "date", "hour", "minute", "weekday", "lastExit", "lastOut", "lastErr",
        "device", "runId",
    ];

    // ---- saving ------------------------------------------------------------

    public async Task<AutoSaved> SaveAsync(Model.Automation? incoming, IPeerLink link, CancellationToken ct)
    {
        var settings = store.Settings.Automation;

        if (incoming is null) return Invalid("", "no automation in the message");
        if (settings.PanicStop) return Invalid(incoming.Id, "automations are stopped on the PC");
        if (!settings.Authoring) return Invalid(incoming.Id, "editing from the phone is turned off on the PC");

        string? problem = Validate(incoming);
        if (problem is not null) return Invalid(incoming.Id, problem);

        var normalised = Normalise(incoming, link);
        var existing = automations.Find(normalised.Id);

        // Approval is per body, so a rename or a colour change keeps it and a
        // single edited character loses it.
        bool unchanged = existing is not null && existing.BodyHash == normalised.BodyHash;
        bool needsApproval = normalised.Risk != "safe" && !(unchanged && existing!.Approved);

        if (!needsApproval)
        {
            var approved = normalised with { Approved = true };
            automations.Save(approved);
            Log?.Invoke($"saved automation \"{approved.Name}\"");
            return new AutoSaved { Id = approved.Id, State = "saved", Summary = Summarise(approved) };
        }

        var request = new ApprovalRequest(
            Title: Strings.Get(existing is null ? "approve.new" : "approve.changed"),
            AutomationName: normalised.Name,
            DeviceName: link.PeerName,
            Risk: normalised.Risk ?? "safe",
            Lines: Describe(normalised),
            IsSaveApproval: true);

        bool allowed = Approve is not null && await Approve(request);
        var result = normalised with { Approved = allowed };
        automations.Save(result);

        Log?.Invoke($"automation \"{result.Name}\" {(allowed ? "approved" : "saved but not approved")}");
        return new AutoSaved
        {
            Id = result.Id,
            State = allowed ? "saved" : "pending",
            Reason = allowed ? null : "waiting for approval on the PC",
            Summary = Summarise(result),
        };
    }

    private static AutoSaved Invalid(string id, string reason) =>
        new() { Id = id, State = "invalid", Reason = reason };

    /// <summary>
    /// Structural checks only — the security checks happen at run time, because
    /// the settings they depend on can change between saving and running.
    /// </summary>
    public string? Validate(Model.Automation automation)
    {
        if (string.IsNullOrWhiteSpace(automation.Name)) return "the automation needs a name";
        if (automation.Steps.Count == 0) return "the automation has no steps";
        if (CountSteps(automation.Steps) > store.Settings.Automation.MaxSteps)
            return $"too many steps (limit {store.Settings.Automation.MaxSteps})";

        return Check(automation.Steps, 0);

        static string? Check(IReadOnlyList<AutoStep> steps, int depth)
        {
            // A container tree deep enough to matter is far more likely to be a
            // mistake, or a way to blow the stack, than something anyone wrote.
            if (depth > 12) return "steps are nested too deeply";

            foreach (var step in steps)
            {
                if (!StepTypes.All.Contains(step.Type))
                    return $"unknown step type \"{step.Type}\"";

                if (step.Type == StepTypes.Shell && string.IsNullOrWhiteSpace(step.Command))
                    return "a shell step has no command";

                if (step.Type is StepTypes.If or StepTypes.While && string.IsNullOrWhiteSpace(step.Condition))
                    return $"a \"{step.Type}\" step has no condition";

                if (step.Type == StepTypes.ForEach && string.IsNullOrWhiteSpace(step.Items))
                    return "a \"foreach\" step has nothing to iterate";

                string? nested = Check(step.Then, depth + 1)
                    ?? Check(step.Else, depth + 1)
                    ?? Check(step.Do, depth + 1);
                if (nested is not null) return nested;
            }
            return null;
        }
    }

    private Model.Automation Normalise(Model.Automation incoming, IPeerLink link)
    {
        var now = DateTimeOffset.UtcNow;
        var existing = string.IsNullOrWhiteSpace(incoming.Id) ? null : automations.Find(incoming.Id);

        var normalised = incoming with
        {
            Id = string.IsNullOrWhiteSpace(incoming.Id) ? Guid.NewGuid().ToString("N")[..12] : incoming.Id,
            Steps = Identify(incoming.Steps, "s"),
            CreatedBy = existing?.CreatedBy ?? link.PeerDeviceId,
            CreatedAt = existing?.CreatedAt ?? now,
            UpdatedAt = now,
            // Whatever the phone claimed about approval is discarded. Approval is
            // a decision made here.
            Approved = false,
        };

        normalised = normalised with
        {
            Risk = RiskOf(normalised),
            BodyHash = AutomationStore.BodyHash(normalised),
        };
        return normalised;
    }

    private static List<AutoStep> Identify(IReadOnlyList<AutoStep> steps, string prefix)
    {
        var result = new List<AutoStep>(steps.Count);
        for (int index = 0; index < steps.Count; index++)
        {
            var step = steps[index];
            string id = string.IsNullOrWhiteSpace(step.Id) ? $"{prefix}{index}" : step.Id;
            result.Add(step with
            {
                Id = id,
                Then = Identify(step.Then, id + "t"),
                Else = Identify(step.Else, id + "e"),
                Do = Identify(step.Do, id + "d"),
            });
        }
        return result;
    }

    /// <summary>A plain-language outline of what an automation will do, for the approval dialog.</summary>
    public static List<string> Describe(Model.Automation automation, Expressions? resolver = null)
    {
        var lines = new List<string>();
        Walk(automation.Steps, 0);
        return lines;

        void Walk(IReadOnlyList<AutoStep> steps, int depth)
        {
            string pad = new(' ', depth * 2);
            foreach (var step in steps)
            {
                if (!step.Enabled) continue;
                lines.Add(pad + Line(step));

                if (step.Then.Count > 0) { lines.Add(pad + "  then:"); Walk(step.Then, depth + 2); }
                if (step.Else.Count > 0) { lines.Add(pad + "  else:"); Walk(step.Else, depth + 2); }
                if (step.Do.Count > 0) { lines.Add(pad + "  do:"); Walk(step.Do, depth + 2); }
            }
        }

        string Text(string? value) => resolver is null ? value ?? "" : resolver.Interpolate(value);

        string Line(AutoStep step) => step.Type switch
        {
            StepTypes.Shell => $"run [{step.Shell ?? "cmd"}]: {Text(step.Command)}"
                + (step.Elevated ? "   (as administrator)" : ""),
            StepTypes.Open => $"open: {Text(step.Target)}",
            StepTypes.Window => $"window {step.Action}: {Text(step.Target)}",
            StepTypes.Process => $"process {step.Action}: {Text(step.Target)}",
            StepTypes.KeyPress => $"press: {string.Join("+", step.Mods.Append(step.Key ?? ""))}",
            StepTypes.TypeText => $"type: {Text(step.Text)}",
            StepTypes.File => $"file {step.Action}: {Text(step.Path)}"
                + (step.Destination is null ? "" : $" -> {Text(step.Destination)}"),
            StepTypes.Http => $"{step.Method ?? "GET"} {Text(step.Url)}",
            StepTypes.Power => $"power: {step.Action}",
            StepTypes.CallAutomation => $"run another automation: {step.Target}",
            StepTypes.If => $"if {step.Condition}",
            StepTypes.While => $"while {step.Condition}",
            StepTypes.Repeat => $"repeat {step.Count} times",
            StepTypes.ForEach => $"for each {step.Var} in {step.Items}",
            StepTypes.Delay => $"wait {step.Number} ms",
            StepTypes.Set => $"set {step.Name} = {step.Value}",
            _ => step.Type + (step.Text is null ? "" : $": {Text(step.Text)}"),
        };
    }

    // ---- running -----------------------------------------------------------

    public async Task<AutoResult> RunAsync(
        AutoRunRequest request, IPeerLink link, Action<AutoEvent> emit, CancellationToken ct)
    {
        string runId = Guid.NewGuid().ToString("N")[..8];
        var automation = automations.Find(request.Id);
        var settings = store.Settings.Automation;
        var started = Stopwatch.StartNew();

        if (automation is null) return Failed(runId, request.Id, "no such automation");
        if (settings.PanicStop) return Failed(runId, automation.Id, "automations are stopped on the PC");
        if (!settings.Enabled) return Failed(runId, automation.Id, "automations are off on the PC");
        if (!automation.Enabled) return Failed(runId, automation.Id, "this automation is disabled");

        if (automation.BodyHash != AutomationStore.BodyHash(automation))
            return Failed(runId, automation.Id, "the stored automation does not match its approval");

        if (!automation.Approved && automation.Risk != "safe")
            return Failed(runId, automation.Id, "not approved on the PC yet");

        if (automation.RequireUnlocked && SessionState.IsLocked())
            return Failed(runId, automation.Id, "the PC is locked");

        var context = new RunContext(runId, automation, link, store, emit, ct)
        {
            MaxSteps = settings.MaxSteps,
            MaxLoopIterations = settings.MaxLoopIterations,
            MaxOutputBytes = settings.MaxOutputBytes,
            Deadline = DateTime.UtcNow.AddMilliseconds(settings.MaxRuntimeMs),
            Host = Host,
        };

        foreach (var (key, value) in automation.Variables) context.Variables[key] = value;
        foreach (var (key, value) in request.Args) context.Variables[key] = value;

        bool needsConfirm = automation.ConfirmEachRun || RequiresRunConfirmation(automation, link);
        if (needsConfirm || request.DryRun)
        {
            // Interpolated, not the template: the whole value of the dialog is
            // that it shows the command that is actually about to run.
            var lines = Describe(automation, context.Resolver);

            if (request.DryRun)
            {
                return new AutoResult
                {
                    RunId = runId,
                    AutomationId = automation.Id,
                    Ok = true,
                    Output = string.Join(Environment.NewLine, lines),
                    DurationMs = started.ElapsedMilliseconds,
                };
            }

            emit(Event(runId, automation.Id, "awaiting-confirm", "waiting for confirmation on the PC"));

            var approval = new ApprovalRequest(
                Title: Strings.Get("approve.run"),
                AutomationName: automation.Name,
                DeviceName: link.PeerName,
                Risk: automation.Risk ?? "safe",
                Lines: lines,
                IsSaveApproval: false);

            if (Approve is null || !await Approve(approval))
            {
                automations.Record(Audit(automation, link, false, started.ElapsedMilliseconds, "refused on the PC"));
                return Failed(runId, automation.Id, "refused on the PC");
            }
        }

        var cancellation = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _runs[runId] = cancellation;
        context.Token = cancellation.Token;

        emit(Event(runId, automation.Id, "started", automation.Name));
        Log?.Invoke($"running \"{automation.Name}\" for {link.PeerName}");

        string? error = null;
        try
        {
            var executor = new StepExecutor(context);
            await executor.RunAsync(automation.Steps);
        }
        catch (OperationCanceledException) { error = "cancelled"; }
        catch (Exception ex) { error = ex.Message; }
        finally
        {
            _runs.TryRemove(runId, out _);
            cancellation.Dispose();
        }

        automations.Record(Audit(automation, link, error is null, started.ElapsedMilliseconds, error));
        emit(Event(runId, automation.Id, "finished", error ?? "done", error is null ? "info" : "error"));

        return new AutoResult
        {
            RunId = runId,
            AutomationId = automation.Id,
            Ok = error is null,
            Error = error,
            Output = context.Output.ToString(),
            StepsRun = context.StepsRun,
            DurationMs = started.ElapsedMilliseconds,
            Variables = context.Variables.ToDictionary(v => v.Key, v => v.Value),
        };
    }

    /// <summary>
    /// Strict mode confirms anything that can execute. Trusted mode skips the
    /// prompt for a trusted device when every shell command matches the
    /// allowlist — and still prompts for the ones that do not, which is the part
    /// that makes an allowlist worth having rather than a blanket exemption.
    /// </summary>
    private bool RequiresRunConfirmation(Model.Automation automation, IPeerLink link)
    {
        var settings = store.Settings.Automation;
        if (automation.Risk == "safe") return false;

        if (settings.TrustMode != "trusted") return true;
        if (!store.IsTrusted(link.PeerDeviceId)) return true;

        return !AllShellStepsAllowlisted(automation.Steps, settings.Allowlist);
    }

    private static bool AllShellStepsAllowlisted(IReadOnlyList<AutoStep> steps, IReadOnlyList<string> allowlist)
    {
        foreach (var step in steps)
        {
            if (step.Type == StepTypes.Shell || step.Type == StepTypes.Process)
            {
                string command = (step.Command ?? step.Target ?? "").Trim();
                if (command.Length == 0) return false;

                // Matched against the template, before interpolation: a command
                // whose executable name comes out of a variable is exactly the
                // case an allowlist must not wave through.
                if (command.Contains("{{", StringComparison.Ordinal)) return false;

                bool listed = allowlist.Any(entry =>
                    command.StartsWith(entry, StringComparison.OrdinalIgnoreCase) ||
                    Path.GetFileName(command.Split(' ')[0])
                        .Equals(entry, StringComparison.OrdinalIgnoreCase));

                if (!listed) return false;
            }

            if (step.Elevated) return false;

            if (!AllShellStepsAllowlisted(step.Then, allowlist)) return false;
            if (!AllShellStepsAllowlisted(step.Else, allowlist)) return false;
            if (!AllShellStepsAllowlisted(step.Do, allowlist)) return false;
        }
        return true;
    }

    public bool Cancel(string runId)
    {
        if (!_runs.TryGetValue(runId, out var cancellation)) return false;
        cancellation.Cancel();
        return true;
    }

    public void CancelAll()
    {
        foreach (var cancellation in _runs.Values) cancellation.Cancel();
    }

    private static AutoResult Failed(string runId, string id, string error) =>
        new() { RunId = runId, AutomationId = id, Ok = false, Error = error };

    private static AutoEvent Event(string runId, string id, string phase, string? message, string level = "info") =>
        new()
        {
            RunId = runId,
            AutomationId = id,
            Phase = phase,
            Level = level,
            Message = message,
            At = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
        };

    private static AutoLogEntry Audit(
        Model.Automation automation, IPeerLink link, bool ok, long durationMs, string? detail) => new()
        {
            At = DateTimeOffset.UtcNow,
            AutomationId = automation.Id,
            Name = automation.Name,
            Device = link.PeerName,
            Ok = ok,
            DurationMs = durationMs,
            Detail = detail,
        };
}
