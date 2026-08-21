using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using WinBridge.App.Storage;
using WinBridge.Core.Protocol;

namespace WinBridge.App.Features.Automation;

/// <summary>
/// Where automations live, and the record of what has run.
///
/// Automations are stored on the PC rather than on the phone, and the phone
/// edits them through the protocol. That is deliberate: the machine that will
/// execute a command is the one that has to hold the approved copy of it, or an
/// approval means nothing — a phone could send different bytes at run time than
/// the ones a person looked at.
/// </summary>
public sealed class AutomationStore
{
    private readonly string _path;
    private readonly string _auditPath;
    private readonly Lock _gate = new();
    private List<Core.Protocol.Automation> _items = [];

    public AutomationStore(string? directory = null)
    {
        string folder = directory ?? BridgeStore.DefaultDirectory;
        _path = Path.Combine(folder, "automations.json");
        _auditPath = Path.Combine(folder, "automation-audit.jsonl");
        Load();
    }

    public event Action<string>? Log;

    public IReadOnlyList<Core.Protocol.Automation> All
    {
        get { lock (_gate) return [.. _items]; }
    }

    public Core.Protocol.Automation? Find(string id)
    {
        lock (_gate) return _items.FirstOrDefault(a => a.Id == id);
    }

    public void Save(Core.Protocol.Automation automation)
    {
        lock (_gate)
        {
            _items = [.. _items.Where(a => a.Id != automation.Id), automation];
            Persist();
        }
    }

    public bool Delete(string id)
    {
        lock (_gate)
        {
            int before = _items.Count;
            _items = [.. _items.Where(a => a.Id != id)];
            if (_items.Count == before) return false;
            Persist();
            return true;
        }
    }

    private void Load()
    {
        try
        {
            if (!File.Exists(_path)) return;
            _items = JsonSerializer.Deserialize<List<Core.Protocol.Automation>>(
                File.ReadAllText(_path), Json.Options) ?? [];
        }
        catch (Exception ex)
        {
            // Same reasoning as the settings file: a bad parse must not stop the
            // app. Losing automations is recoverable; a crash loop is not.
            Log?.Invoke($"automations could not be read: {ex.Message}");
            _items = [];
        }
    }

    private void Persist()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
            string json = JsonSerializer.Serialize(_items,
                new JsonSerializerOptions(Json.Options) { WriteIndented = true });

            string temporary = _path + ".tmp";
            File.WriteAllText(temporary, json);
            File.Move(temporary, _path, overwrite: true);
        }
        catch (Exception ex)
        {
            Log?.Invoke($"automations could not be written: {ex.Message}");
        }
    }

    // ---- audit trail -------------------------------------------------------

    /// <summary>
    /// Appends one line per run. Append-only and separate from the settings file
    /// so that a run cannot be quietly removed from the record by anything that
    /// rewrites settings, and so it survives the app being killed mid-run.
    /// </summary>
    public void Record(AutoLogEntry entry)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_auditPath)!);
            File.AppendAllText(_auditPath,
                JsonSerializer.Serialize(entry, Json.Options) + Environment.NewLine, Encoding.UTF8);
        }
        catch (Exception ex)
        {
            Log?.Invoke($"audit write failed: {ex.Message}");
        }
    }

    public List<AutoLogEntry> RecentRuns(int count = 100)
    {
        try
        {
            if (!File.Exists(_auditPath)) return [];
            return [.. File.ReadLines(_auditPath)
                .Reverse()
                .Take(count)
                .Select(line => TryParse(line))
                .Where(entry => entry is not null)
                .Select(entry => entry!)];
        }
        catch { return []; }
    }

    private static AutoLogEntry? TryParse(string line)
    {
        try { return JsonSerializer.Deserialize<AutoLogEntry>(line, Json.Options); }
        catch { return null; }
    }

    // ---- integrity ---------------------------------------------------------

    /// <summary>
    /// Hashes only what can execute — the steps and the variable defaults — and
    /// deliberately not the name, icon or colour.
    ///
    /// This is what approval is bound to. Renaming an automation should not send
    /// the user back through a dialog; changing a single character of a command
    /// must. Hashing the whole record would get the first part wrong, and
    /// hashing only the command strings would miss a step being reordered.
    /// </summary>
    public static string BodyHash(Core.Protocol.Automation automation)
    {
        var body = new
        {
            steps = automation.Steps,
            vars = automation.Variables.OrderBy(v => v.Key).ToDictionary(v => v.Key, v => v.Value),
            requireUnlocked = automation.RequireUnlocked,
        };

        byte[] utf8 = JsonSerializer.SerializeToUtf8Bytes(body, Json.Options);
        return Convert.ToHexString(SHA256.HashData(utf8)).ToLowerInvariant()[..32];
    }
}
