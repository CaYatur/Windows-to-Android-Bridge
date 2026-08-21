using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Text;
using WinBridge.App.Server;
using WinBridge.App.Storage;
using WinBridge.Core.Protocol;
using Model = WinBridge.Core.Protocol;

namespace WinBridge.App.Features.Automation;

/// <summary>Everything one run needs, and everything it is allowed to touch.</summary>
public sealed class RunContext
{
    private readonly Action<AutoEvent> _emit;

    public RunContext(
        string runId,
        Model.Automation automation,
        IPeerLink link,
        BridgeStore store,
        Action<AutoEvent> emit,
        CancellationToken token)
    {
        RunId = runId;
        Automation = automation;
        Link = link;
        Store = store;
        _emit = emit;
        Token = token;

        Resolver = new Expressions(Resolve);
    }

    public string RunId { get; }
    public Model.Automation Automation { get; }
    public IPeerLink Link { get; }
    public BridgeStore Store { get; }
    public HostServices? Host { get; init; }

    public Dictionary<string, string> Variables { get; } = new(StringComparer.OrdinalIgnoreCase);
    public StringBuilder Output { get; } = new();

    public int StepsRun { get; set; }
    public int MaxSteps { get; init; } = 500;
    public int MaxLoopIterations { get; init; } = 1000;
    public int MaxOutputBytes { get; init; } = 64 * 1024;
    public DateTime Deadline { get; init; } = DateTime.UtcNow.AddMinutes(2);
    public CancellationToken Token { get; set; }

    public int LastExit { get; set; }
    public string LastOut { get; set; } = "";
    public string LastErr { get; set; } = "";

    public Expressions Resolver { get; }

    public void Emit(string phase, string? message, int stepIndex = -1, AutoStep? step = null, string level = "info") =>
        _emit(new AutoEvent
        {
            RunId = RunId,
            AutomationId = Automation.Id,
            Phase = phase,
            StepIndex = stepIndex,
            StepId = step?.Id,
            StepType = step?.Type,
            Level = level,
            Message = message,
            At = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
        });

    public void Write(string text)
    {
        if (Output.Length >= MaxOutputBytes) return;

        // Truncated rather than dropped: a command that prints a gigabyte should
        // not be able to push the process into swap, but the first part of what
        // it said is usually the part worth reading.
        int room = MaxOutputBytes - Output.Length;
        Output.Append(text.Length <= room ? text : text[..room] + "\n… (truncated)");
    }

    public void Step()
    {
        Token.ThrowIfCancellationRequested();
        if (++StepsRun > MaxSteps)
            throw new InvalidOperationException($"step limit reached ({MaxSteps})");
        if (DateTime.UtcNow > Deadline)
            throw new InvalidOperationException("the automation ran out of time");
    }

    /// <summary>
    /// Resolves a name to a variable the automation set, then to something about
    /// the machine. Automation variables shadow built-ins so a name collision
    /// with a future built-in cannot silently change what an existing automation
    /// does.
    /// </summary>
    public object? Resolve(string name)
    {
        if (Variables.TryGetValue(name, out string? value)) return value;

        var host = Host;
        return name switch
        {
            "hostName" => Environment.MachineName,
            "user" => Environment.UserName,
            "device" => Link.PeerName,
            "runId" => RunId,
            "lastExit" => (double)LastExit,
            "lastOut" => LastOut,
            "lastErr" => LastErr,
            "time" => DateTime.Now.ToString("HH:mm"),
            "date" => DateTime.Now.ToString("yyyy-MM-dd"),
            "hour" => (double)DateTime.Now.Hour,
            "minute" => (double)DateTime.Now.Minute,
            "weekday" => DateTime.Now.DayOfWeek.ToString(),
            "cpu" => host is null ? 0d : host.Metrics.Sample().Cpu,
            "ramUsedMb" => host is null ? 0d : (double)host.Metrics.Sample().Ram.UsedMb,
            "ramTotalMb" => host is null ? 0d : (double)host.Metrics.Sample().Ram.TotalMb,
            "ramPct" => RamPercent(host),
            "battery" => host is null ? 0d : (double)host.Metrics.Sample().Battery.Pct,
            "charging" => host is not null && host.Metrics.Sample().Battery.Charging,
            "volume" => host is null ? 0d : (double)host.Volume.Read().Level,
            "muted" => host is not null && host.Volume.Read().Muted,
            "activeWindow" => host?.SystemQuery.Windows().FirstOrDefault(w => w.Active)?.Title ?? "",
            "activeProcess" => host?.SystemQuery.Windows().FirstOrDefault(w => w.Active)?.Process ?? "",
            _ => "",
        };
    }

    private static double RamPercent(HostServices? host)
    {
        if (host is null) return 0;
        var ram = host.Metrics.Sample().Ram;
        return ram.TotalMb <= 0 ? 0 : Math.Round(ram.UsedMb * 100.0 / ram.TotalMb, 1);
    }
}

internal enum Flow { Normal, Break, Continue, Return }

/// <summary>
/// Walks the step tree.
///
/// Recursive rather than a bytecode loop: the tree is shallow by construction
/// (the validator caps nesting) and this way the code reads like the thing a
/// person built in the editor.
/// </summary>
internal sealed class StepExecutor(RunContext context)
{
    public async Task RunAsync(IReadOnlyList<AutoStep> steps) => await ExecuteAsync(steps);

    private async Task<Flow> ExecuteAsync(IReadOnlyList<AutoStep> steps)
    {
        for (int index = 0; index < steps.Count; index++)
        {
            var step = steps[index];
            if (!step.Enabled) continue;

            context.Step();
            context.Emit("step", Summary(step), index, step);

            try
            {
                var flow = await ExecuteAsync(step);
                if (flow != Flow.Normal) return flow;
            }
            catch (OperationCanceledException) { throw; }
            catch (Exception ex)
            {
                context.Emit("step", $"{step.Type}: {ex.Message}", index, step, "error");
                if (!step.OnErrorContinue) throw;
                context.LastErr = ex.Message;
            }
        }
        return Flow.Normal;
    }

    private string Summary(AutoStep step) => step.Type switch
    {
        StepTypes.Shell => $"run {Text(step.Command)}",
        StepTypes.If => $"if {step.Condition}",
        StepTypes.Set => $"set {step.Name}",
        _ => step.Type,
    };

    private string Text(string? value) => context.Resolver.Interpolate(value);

    private async Task<Flow> ExecuteAsync(AutoStep step)
    {
        var host = context.Host;

        switch (step.Type)
        {
            // ---- control flow ---------------------------------------------
            case StepTypes.If:
                return context.Resolver.EvaluateCondition(step.Condition)
                    ? await ExecuteAsync(step.Then)
                    : await ExecuteAsync(step.Else);

            case StepTypes.While:
            {
                int iterations = 0;
                while (context.Resolver.EvaluateCondition(step.Condition))
                {
                    if (++iterations > context.MaxLoopIterations)
                        throw new InvalidOperationException($"loop ran past {context.MaxLoopIterations} iterations");

                    context.Step();
                    var flow = await ExecuteAsync(step.Do);
                    if (flow == Flow.Break) break;
                    if (flow == Flow.Return) return flow;
                }
                return Flow.Normal;
            }

            case StepTypes.Repeat:
            {
                int count = Math.Min(step.Count, context.MaxLoopIterations);
                for (int n = 0; n < count; n++)
                {
                    context.Variables["index"] = n.ToString();
                    context.Step();
                    var flow = await ExecuteAsync(step.Do);
                    if (flow == Flow.Break) break;
                    if (flow == Flow.Return) return flow;
                }
                return Flow.Normal;
            }

            case StepTypes.ForEach:
            {
                object? value = context.Resolver.Evaluate(step.Items!);
                var items = value as IReadOnlyList<object?>
                    ?? Expressions.Stringify(value).Split(',').Select(part => (object?)part.Trim()).ToList();

                int n = 0;
                foreach (object? item in items)
                {
                    if (++n > context.MaxLoopIterations)
                        throw new InvalidOperationException($"loop ran past {context.MaxLoopIterations} iterations");

                    context.Variables[step.Var ?? "item"] = Expressions.Stringify(item);
                    context.Variables["index"] = (n - 1).ToString();
                    context.Step();

                    var flow = await ExecuteAsync(step.Do);
                    if (flow == Flow.Break) break;
                    if (flow == Flow.Return) return flow;
                }
                return Flow.Normal;
            }

            case StepTypes.Break: return Flow.Break;
            case StepTypes.Continue: return Flow.Continue;
            case StepTypes.Return: return Flow.Return;

            case StepTypes.Set:
                context.Variables[step.Name ?? "value"] =
                    Expressions.Stringify(context.Resolver.Evaluate(step.Value ?? "''"));
                return Flow.Normal;

            case StepTypes.Delay:
                await Task.Delay(
                    TimeSpan.FromMilliseconds(Math.Clamp(step.Number, 0, 60_000)), context.Token);
                return Flow.Normal;

            case StepTypes.Log:
                context.Write(Text(step.Text) + Environment.NewLine);
                return Flow.Normal;

            // ---- the machine ----------------------------------------------
            case StepTypes.Shell:
                await RunShellAsync(step);
                return Flow.Normal;

            case StepTypes.Open:
                Require(host).SystemQuery.Open(Text(step.Target), out string? openError);
                if (openError is not null) throw new InvalidOperationException(openError);
                return Flow.Normal;

            case StepTypes.Window:
            {
                var command = new WindowCommand { Action = step.Action ?? "focus", Match = Text(step.Target) };
                if (!Require(host).SystemQuery.Window(command, out string? error))
                    throw new InvalidOperationException(error ?? "window command failed");
                return Flow.Normal;
            }

            case StepTypes.Process:
                RunProcessStep(step, Require(host));
                return Flow.Normal;

            case StepTypes.KeyPress:
                Require(host).Input.Key(new InputKey
                {
                    Action = "tap",
                    Code = step.Key ?? "",
                    Mods = [.. step.Mods],
                });
                return Flow.Normal;

            case StepTypes.TypeText:
                Require(host).Input.Text(Text(step.Text));
                return Flow.Normal;

            case StepTypes.Mouse:
                RunMouseStep(step, Require(host));
                return Flow.Normal;

            case StepTypes.Media:
                await Require(host).Media.ControlAsync(step.Action ?? "toggle", (long)step.Number);
                return Flow.Normal;

            case StepTypes.Volume:
                RunVolumeStep(step, Require(host));
                return Flow.Normal;

            case StepTypes.Power:
            {
                if (!Require(host).Power.Execute(step.Action ?? "lock", (int)step.Number, out string? error))
                    throw new InvalidOperationException(error ?? "power command failed");
                return Flow.Normal;
            }

            case StepTypes.ClipboardGet:
            {
                var services = Require(host);
                ClipboardMessage? clip = null;
                await services.OnUiThread(() => clip = services.Clipboard.Read());
                context.Variables[step.Name ?? "clipboard"] = clip?.Text ?? "";
                return Flow.Normal;
            }

            case StepTypes.ClipboardSet:
            {
                var services = Require(host);
                string text = Text(step.Text);
                await services.OnUiThread(() =>
                    services.Clipboard.Apply(new ClipboardMessage
                    {
                        Text = text,
                        Hash = ClipboardBridge.Fingerprint(Encoding.UTF8.GetBytes(text)),
                    }));
                return Flow.Normal;
            }

            case StepTypes.Notify:
                Require(host).Notifications.Toast(
                    Text(step.Name) is { Length: > 0 } title ? title : "WinBridge",
                    Text(step.Text),
                    step.Action ?? "info");
                return Flow.Normal;

            case StepTypes.File:
                RunFileStep(step);
                return Flow.Normal;

            case StepTypes.Http:
                await RunHttpAsync(step);
                return Flow.Normal;

            case StepTypes.Screenshot:
            {
                byte[]? jpeg = Require(host).SystemQuery.Screenshot(Text(step.Target));
                if (jpeg is null) throw new InvalidOperationException("could not capture the screen");
                string id = $"shot:{Guid.NewGuid():N}"[..20];
                await context.Link.SendBlobAsync(id, jpeg, context.Token);
                context.Variables[step.Name ?? "screenshot"] = id;
                return Flow.Normal;
            }

            case StepTypes.Describe:
            {
                var description = await Require(host).SystemQuery.DescribeAsync(
                    new DescribeRequest { Target = Text(step.Target) });
                context.Variables[step.Name ?? "description"] =
                    $"{description.Title}\n{description.Text}".Trim();
                context.Write(context.Variables[step.Name ?? "description"] + Environment.NewLine);
                return Flow.Normal;
            }

            // ---- back to the phone ----------------------------------------
            case StepTypes.PhoneNotify:
                await context.Link.SendJsonAsync(new SysNotify
                {
                    Title = Text(step.Name) is { Length: > 0 } phoneTitle ? phoneTitle : context.Automation.Name,
                    Text = Text(step.Text),
                    Level = step.Action ?? "info",
                }, context.Token);
                return Flow.Normal;

            case StepTypes.PhoneRing:
                await context.Link.SendJsonAsync(new Model.PhoneRing
                {
                    Action = step.Action ?? "start",
                    Seconds = step.Number > 0 ? (int)step.Number : 30,
                }, context.Token);
                return Flow.Normal;

            case StepTypes.PhoneClipboard:
                await context.Link.SendJsonAsync(new ClipboardMessage
                {
                    Text = Text(step.Text),
                    Label = Environment.MachineName,
                }, context.Token);
                return Flow.Normal;

            case StepTypes.CallAutomation:
                throw new InvalidOperationException(
                    "calling another automation is not available in this build");

            default:
                throw new InvalidOperationException($"unknown step type \"{step.Type}\"");
        }
    }

    private static HostServices Require(HostServices? host) =>
        host ?? throw new InvalidOperationException("this step needs services the host has not started");

    // ---- shell --------------------------------------------------------------

    private async Task RunShellAsync(AutoStep step)
    {
        var settings = context.Store.Settings.Automation;

        // Checked at run time, not at save time: the switch can be turned off
        // after an automation was approved, and off has to mean off.
        if (!settings.Shell)
            throw new InvalidOperationException("shell steps are turned off on the PC");
        if (step.Elevated && !settings.AllowElevated)
            throw new InvalidOperationException("elevated steps are turned off on the PC");

        string command = Text(step.Command);
        if (string.IsNullOrWhiteSpace(command)) throw new InvalidOperationException("empty command");

        string shell = step.Shell ?? "cmd";
        var info = new ProcessStartInfo
        {
            CreateNoWindow = step.Hidden,
            UseShellExecute = step.Elevated,
            WorkingDirectory = string.IsNullOrWhiteSpace(step.WorkingDirectory)
                ? Environment.GetFolderPath(Environment.SpecialFolder.UserProfile)
                : Text(step.WorkingDirectory),
        };

        switch (shell)
        {
            case "powershell":
                info.FileName = "powershell.exe";
                // -NoProfile keeps a user profile script from changing what the
                // approved command means; -ExecutionPolicy Bypass applies only
                // to this one invocation.
                info.ArgumentList.Add("-NoProfile");
                info.ArgumentList.Add("-NonInteractive");
                info.ArgumentList.Add("-ExecutionPolicy");
                info.ArgumentList.Add("Bypass");
                info.ArgumentList.Add("-Command");
                info.ArgumentList.Add(command);
                break;

            case "exec":
                info.FileName = command;
                foreach (string argument in step.Args) info.ArgumentList.Add(Text(argument));
                break;

            default:
                info.FileName = "cmd.exe";
                info.ArgumentList.Add("/c");
                info.ArgumentList.Add(command);
                break;
        }

        if (step.Elevated) info.Verb = "runas";

        bool capture = step.Capture && !step.Elevated;
        if (capture)
        {
            info.RedirectStandardOutput = true;
            info.RedirectStandardError = true;
            info.UseShellExecute = false;
        }

        using var process = Process.Start(info)
            ?? throw new InvalidOperationException($"could not start {info.FileName}");

        var stdout = capture ? process.StandardOutput.ReadToEndAsync(context.Token) : Task.FromResult("");
        var stderr = capture ? process.StandardError.ReadToEndAsync(context.Token) : Task.FromResult("");

        int timeout = Math.Clamp(step.TimeoutMs, 100, 600_000);
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(context.Token);
        deadline.CancelAfter(timeout);

        try
        {
            await process.WaitForExitAsync(deadline.Token);
        }
        catch (OperationCanceledException) when (!context.Token.IsCancellationRequested)
        {
            try { process.Kill(entireProcessTree: true); } catch { }
            throw new InvalidOperationException($"the command did not finish within {timeout} ms");
        }

        context.LastExit = process.ExitCode;
        context.LastOut = (await stdout).TrimEnd();
        context.LastErr = (await stderr).TrimEnd();

        if (context.LastOut.Length > 0) context.Write(context.LastOut + Environment.NewLine);
        if (context.LastErr.Length > 0) context.Write(context.LastErr + Environment.NewLine);

        if (step.Name is not null) context.Variables[step.Name] = context.LastOut;

        if (process.ExitCode != 0 && !step.OnErrorContinue)
            throw new InvalidOperationException($"exit code {process.ExitCode}");
    }

    private void RunProcessStep(AutoStep step, HostServices host)
    {
        string target = Text(step.Target);

        switch (step.Action)
        {
            case "kill":
            {
                var matches = Process.GetProcessesByName(
                    target.Replace(".exe", "", StringComparison.OrdinalIgnoreCase));
                if (matches.Length == 0) throw new InvalidOperationException($"{target} is not running");

                foreach (var process in matches)
                {
                    try { process.Kill(); } finally { process.Dispose(); }
                }
                return;
            }

            case "start":
            {
                var info = new ProcessStartInfo(target) { UseShellExecute = true };
                foreach (string argument in step.Args) info.ArgumentList.Add(Text(argument));
                Process.Start(info);
                return;
            }

            default:
                throw new InvalidOperationException($"unknown process action \"{step.Action}\"");
        }
    }

    private void RunMouseStep(AutoStep step, HostServices host)
    {
        switch (step.Action)
        {
            case "move":
            {
                // x in number, y in value: two coordinates need two fields, and
                // parsing the second out of prose is how a mouse ends up at 0,0.
                double y = double.TryParse(Text(step.Value), out double parsed) ? parsed : 0;
                host.Input.MoveAbsolute((int)step.Number, (int)y);
                return;
            }
            case "click":
            case "down":
            case "up":
                if (step.Action == "click") host.Input.Click(step.Key ?? "left");
                else host.Input.Button(step.Key ?? "left", step.Action == "down");
                return;
            case "wheel":
                host.Input.Wheel((int)step.Number, 0);
                return;
            default:
                throw new InvalidOperationException($"unknown mouse action \"{step.Action}\"");
        }
    }

    private void RunVolumeStep(AutoStep step, HostServices host)
    {
        switch (step.Action)
        {
            case "set": host.Volume.SetLevel((int)step.Number); return;
            case "mute": host.Volume.SetMuted(true); return;
            case "unmute": host.Volume.SetMuted(false); return;
            case "up": host.Volume.SetLevel(host.Volume.Read().Level + (int)Math.Max(1, step.Number)); return;
            case "down": host.Volume.SetLevel(host.Volume.Read().Level - (int)Math.Max(1, step.Number)); return;
            default: throw new InvalidOperationException($"unknown volume action \"{step.Action}\"");
        }
    }

    // ---- files and network --------------------------------------------------

    private void RunFileStep(AutoStep step)
    {
        var settings = context.Store.Settings.Automation;
        string path = Text(step.Path);
        string destination = Text(step.Destination);

        bool writes = step.Action is "write" or "append" or "copy" or "move" or "delete" or "mkdir";
        if (writes && !settings.AllowFileWrite)
            throw new InvalidOperationException("file-changing steps are turned off on the PC");

        switch (step.Action)
        {
            case "read":
                context.Variables[step.Name ?? "content"] = File.ReadAllText(path);
                return;
            case "write": File.WriteAllText(path, Text(step.Text)); return;
            case "append": File.AppendAllText(path, Text(step.Text)); return;
            case "copy": File.Copy(path, destination, overwrite: true); return;
            case "move": File.Move(path, destination, overwrite: true); return;
            case "delete": File.Delete(path); return;
            case "mkdir": Directory.CreateDirectory(path); return;
            case "exists":
                context.Variables[step.Name ?? "exists"] =
                    (File.Exists(path) || Directory.Exists(path)) ? "true" : "false";
                return;
            case "list":
                context.Variables[step.Name ?? "files"] =
                    string.Join(",", Directory.EnumerateFileSystemEntries(path).Take(500).Select(Path.GetFileName));
                return;
            default:
                throw new InvalidOperationException($"unknown file action \"{step.Action}\"");
        }
    }

    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(30) };

    private async Task RunHttpAsync(AutoStep step)
    {
        if (!context.Store.Settings.Automation.AllowNetwork)
            throw new InvalidOperationException("network steps are turned off on the PC");

        string url = Text(step.Url);
        var request = new HttpRequestMessage(
            new HttpMethod((step.Method ?? "GET").ToUpperInvariant()), url);

        foreach (var (key, value) in step.Headers) request.Headers.TryAddWithoutValidation(key, Text(value));
        if (step.Body is not null) request.Content = new StringContent(Text(step.Body), Encoding.UTF8);

        using var response = await Http.SendAsync(request, context.Token);
        string body = await response.Content.ReadAsStringAsync(context.Token);

        context.LastExit = (int)response.StatusCode;
        context.LastOut = body;
        if (step.Name is not null) context.Variables[step.Name] = body;
        context.Write(body.Length > 2000 ? body[..2000] + "…" : body);
    }
}
