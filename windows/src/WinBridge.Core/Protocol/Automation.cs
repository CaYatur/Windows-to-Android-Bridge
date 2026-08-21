using System.Text.Json.Serialization;

namespace WinBridge.Core.Protocol;

/// <summary>
/// Everything a step can be. Kept as strings rather than an enum because the
/// phone may be a version ahead or behind: an unknown type is refused by name at
/// validation time with something the user can read, instead of deserialising to
/// whatever happens to be zero.
/// </summary>
public static class StepTypes
{
    public const string Shell = "shell";
    public const string Open = "open";
    public const string Window = "window";
    public const string Process = "process";
    public const string KeyPress = "key";
    public const string TypeText = "type";
    public const string Mouse = "mouse";
    public const string Media = "media";
    public const string Volume = "volume";
    public const string Power = "power";
    public const string ClipboardGet = "clip.get";
    public const string ClipboardSet = "clip.set";
    public const string Notify = "notify";
    public const string File = "file";
    public const string Http = "http";
    public const string Delay = "delay";
    public const string Set = "set";
    public const string If = "if";
    public const string While = "while";
    public const string Repeat = "repeat";
    public const string ForEach = "foreach";
    public const string Break = "break";
    public const string Continue = "continue";
    public const string Return = "return";
    public const string Log = "log";
    public const string Screenshot = "screenshot";
    public const string Describe = "describe";
    public const string PhoneNotify = "phone.notify";
    public const string PhoneRing = "phone.ring";
    public const string PhoneClipboard = "phone.clip";
    public const string CallAutomation = "call";

    public static readonly IReadOnlyList<string> All =
    [
        Shell, Open, Window, Process, KeyPress, TypeText, Mouse, Media, Volume, Power,
        ClipboardGet, ClipboardSet, Notify, File, Http, Delay, Set, If, While, Repeat,
        ForEach, Break, Continue, Return, Log, Screenshot, Describe,
        PhoneNotify, PhoneRing, PhoneClipboard, CallAutomation,
    ];

    /// <summary>Steps that can change the machine outside this app. These drive the risk labels.</summary>
    public static readonly IReadOnlyList<string> Privileged =
    [
        Shell, Process, File, Http, Power, CallAutomation,
    ];
}

/// <summary>
/// One step. A single flat shape with mostly-null fields rather than a
/// polymorphic hierarchy: it survives round-tripping through two JSON stacks
/// and an editor on a phone without either side needing a type registry, and
/// the validator is the thing that decides which fields a given type requires.
/// </summary>
public sealed record AutoStep
{
    [JsonPropertyName("id")] public string Id { get; init; } = "";
    [JsonPropertyName("type")] public string Type { get; init; } = "";
    [JsonPropertyName("note")] public string? Note { get; init; }
    [JsonPropertyName("enabled")] public bool Enabled { get; init; } = true;
    /// <summary>Keep going when this step fails, instead of aborting the run.</summary>
    [JsonPropertyName("onErrorContinue")] public bool OnErrorContinue { get; init; }

    // shell / process ---------------------------------------------------------
    /// <summary>"cmd", "powershell" or "exec" (no interpreter, argv passed straight through).</summary>
    [JsonPropertyName("shell")] public string? Shell { get; init; }
    [JsonPropertyName("command")] public string? Command { get; init; }
    [JsonPropertyName("args")] public List<string> Args { get; init; } = [];
    [JsonPropertyName("cwd")] public string? WorkingDirectory { get; init; }
    [JsonPropertyName("timeoutMs")] public int TimeoutMs { get; init; } = 30_000;
    [JsonPropertyName("elevated")] public bool Elevated { get; init; }
    [JsonPropertyName("hidden")] public bool Hidden { get; init; } = true;
    [JsonPropertyName("capture")] public bool Capture { get; init; } = true;

    // generic operands --------------------------------------------------------
    [JsonPropertyName("action")] public string? Action { get; init; }
    [JsonPropertyName("target")] public string? Target { get; init; }
    [JsonPropertyName("text")] public string? Text { get; init; }
    [JsonPropertyName("value")] public string? Value { get; init; }
    [JsonPropertyName("name")] public string? Name { get; init; }
    [JsonPropertyName("path")] public string? Path { get; init; }
    [JsonPropertyName("dest")] public string? Destination { get; init; }
    [JsonPropertyName("key")] public string? Key { get; init; }
    [JsonPropertyName("mods")] public List<string> Mods { get; init; } = [];
    [JsonPropertyName("number")] public double Number { get; init; }
    [JsonPropertyName("url")] public string? Url { get; init; }
    [JsonPropertyName("method")] public string? Method { get; init; }
    [JsonPropertyName("headers")] public Dictionary<string, string> Headers { get; init; } = [];
    [JsonPropertyName("body")] public string? Body { get; init; }

    // control flow ------------------------------------------------------------
    [JsonPropertyName("cond")] public string? Condition { get; init; }
    [JsonPropertyName("then")] public List<AutoStep> Then { get; init; } = [];
    [JsonPropertyName("else")] public List<AutoStep> Else { get; init; } = [];
    [JsonPropertyName("do")] public List<AutoStep> Do { get; init; } = [];
    [JsonPropertyName("count")] public int Count { get; init; }
    [JsonPropertyName("items")] public string? Items { get; init; }
    [JsonPropertyName("var")] public string? Var { get; init; }
}

/// <summary>
/// A saved automation. <see cref="BodyHash"/> is what approval is bound to, so
/// editing anything that can execute revokes the approval automatically rather
/// than relying on the editor to remember to ask again.
/// </summary>
public sealed record Automation
{
    [JsonPropertyName("id")] public string Id { get; init; } = "";
    [JsonPropertyName("name")] public string Name { get; init; } = "";
    [JsonPropertyName("desc")] public string? Description { get; init; }
    [JsonPropertyName("icon")] public string? Icon { get; init; }
    [JsonPropertyName("color")] public string? Color { get; init; }
    [JsonPropertyName("enabled")] public bool Enabled { get; init; } = true;
    [JsonPropertyName("steps")] public List<AutoStep> Steps { get; init; } = [];
    /// <summary>Default values for the variables the steps interpolate.</summary>
    [JsonPropertyName("vars")] public Dictionary<string, string> Variables { get; init; } = [];
    /// <summary>Ask on the PC before every run, even for an approved automation.</summary>
    [JsonPropertyName("confirm")] public bool ConfirmEachRun { get; init; }
    /// <summary>Refuse to run while the workstation is locked.</summary>
    [JsonPropertyName("requireUnlocked")] public bool RequireUnlocked { get; init; } = true;
    [JsonPropertyName("createdBy")] public string? CreatedBy { get; init; }
    [JsonPropertyName("createdAt")] public DateTimeOffset CreatedAt { get; init; }
    [JsonPropertyName("updatedAt")] public DateTimeOffset UpdatedAt { get; init; }

    // Filled in by the host; a phone that sets these has them overwritten.
    [JsonPropertyName("approved")] public bool Approved { get; init; }
    [JsonPropertyName("bodyHash")] public string? BodyHash { get; init; }
    [JsonPropertyName("risk")] public string? Risk { get; init; }
    [JsonPropertyName("shortcut")] public string? Shortcut { get; init; }
}

public sealed record AutomationSummary
{
    [JsonPropertyName("id")] public string Id { get; init; } = "";
    [JsonPropertyName("name")] public string Name { get; init; } = "";
    [JsonPropertyName("desc")] public string? Description { get; init; }
    [JsonPropertyName("icon")] public string? Icon { get; init; }
    [JsonPropertyName("color")] public string? Color { get; init; }
    [JsonPropertyName("enabled")] public bool Enabled { get; init; }
    [JsonPropertyName("approved")] public bool Approved { get; init; }
    [JsonPropertyName("confirm")] public bool ConfirmEachRun { get; init; }
    [JsonPropertyName("steps")] public int StepCount { get; init; }
    /// <summary>"safe", "elevated-input", "shell" or "dangerous".</summary>
    [JsonPropertyName("risk")] public string Risk { get; init; } = "safe";
    [JsonPropertyName("updatedAt")] public DateTimeOffset UpdatedAt { get; init; }
}

public sealed record AutoListRequest
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.AutoList;
}

/// <summary>
/// Everything the phone needs to render the automation screen without guessing:
/// what exists, what this host will actually let it do, and which step types it
/// understands. A phone that is a version ahead hides step types this host does
/// not list rather than offering an editor for something that cannot run.
/// </summary>
public sealed record AutoCatalog
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.AutoCatalog;
    [JsonPropertyName("items")] public List<AutomationSummary> Items { get; init; } = [];
    [JsonPropertyName("stepTypes")] public List<string> StepTypes { get; init; } = [];
    [JsonPropertyName("shellEnabled")] public bool ShellEnabled { get; init; }
    /// <summary>"strict" or "trusted".</summary>
    [JsonPropertyName("trustMode")] public string TrustMode { get; init; } = "strict";
    [JsonPropertyName("deviceTrusted")] public bool DeviceTrusted { get; init; }
    [JsonPropertyName("authoringAllowed")] public bool AuthoringAllowed { get; init; }
    [JsonPropertyName("allowlist")] public List<string> Allowlist { get; init; } = [];
    [JsonPropertyName("functions")] public List<string> Functions { get; init; } = [];
    [JsonPropertyName("variables")] public List<string> Variables { get; init; } = [];
}

public sealed record AutoGetRequest
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.AutoGet;
    [JsonPropertyName("id")] public string Id { get; init; } = "";
}

public sealed record AutoDefinition
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.AutoDef;
    [JsonPropertyName("automation")] public Automation? Automation { get; init; }
    [JsonPropertyName("error")] public string? Error { get; init; }
}

public sealed record AutoSaveRequest
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.AutoSave;
    [JsonPropertyName("automation")] public Automation? Automation { get; init; }
}

public sealed record AutoSaved
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.AutoSaved;
    [JsonPropertyName("id")] public string Id { get; init; } = "";
    /// <summary>"saved", "pending", "rejected" or "invalid".</summary>
    [JsonPropertyName("state")] public string State { get; init; } = "saved";
    [JsonPropertyName("reason")] public string? Reason { get; init; }
    [JsonPropertyName("summary")] public AutomationSummary? Summary { get; init; }
}

public sealed record AutoDeleteRequest
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.AutoDelete;
    [JsonPropertyName("id")] public string Id { get; init; } = "";
}

public sealed record AutoRunRequest
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.AutoRun;
    [JsonPropertyName("id")] public string Id { get; init; } = "";
    [JsonPropertyName("args")] public Dictionary<string, string> Args { get; init; } = [];
    /// <summary>Validate and report what would run, without running it.</summary>
    [JsonPropertyName("dryRun")] public bool DryRun { get; init; }
}

public sealed record AutoEvent
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.AutoEvent;
    [JsonPropertyName("runId")] public string RunId { get; init; } = "";
    [JsonPropertyName("id")] public string AutomationId { get; init; } = "";
    /// <summary>"queued", "awaiting-confirm", "started", "step", "output", "finished".</summary>
    [JsonPropertyName("phase")] public string Phase { get; init; } = "";
    [JsonPropertyName("stepIndex")] public int StepIndex { get; init; } = -1;
    [JsonPropertyName("stepId")] public string? StepId { get; init; }
    [JsonPropertyName("stepType")] public string? StepType { get; init; }
    /// <summary>"info", "warn" or "error".</summary>
    [JsonPropertyName("level")] public string Level { get; init; } = "info";
    [JsonPropertyName("message")] public string? Message { get; init; }
    [JsonPropertyName("at")] public long At { get; init; }
}

public sealed record AutoResult
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.AutoResult;
    [JsonPropertyName("runId")] public string RunId { get; init; } = "";
    [JsonPropertyName("id")] public string AutomationId { get; init; } = "";
    [JsonPropertyName("ok")] public bool Ok { get; init; }
    [JsonPropertyName("error")] public string? Error { get; init; }
    [JsonPropertyName("output")] public string? Output { get; init; }
    [JsonPropertyName("steps")] public int StepsRun { get; init; }
    [JsonPropertyName("durationMs")] public long DurationMs { get; init; }
    [JsonPropertyName("vars")] public Dictionary<string, string> Variables { get; init; } = [];
}

public sealed record AutoCancelRequest
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.AutoCancel;
    [JsonPropertyName("runId")] public string RunId { get; init; } = "";
}

public sealed record AutoLogEntry
{
    [JsonPropertyName("at")] public DateTimeOffset At { get; init; }
    [JsonPropertyName("id")] public string AutomationId { get; init; } = "";
    [JsonPropertyName("name")] public string Name { get; init; } = "";
    [JsonPropertyName("device")] public string Device { get; init; } = "";
    [JsonPropertyName("ok")] public bool Ok { get; init; }
    [JsonPropertyName("durationMs")] public long DurationMs { get; init; }
    [JsonPropertyName("detail")] public string? Detail { get; init; }
}

public sealed record AutoLog
{
    [JsonPropertyName("t")] public string Type => MessageTypesV2.AutoLog;
    [JsonPropertyName("items")] public List<AutoLogEntry> Items { get; init; } = [];
}
