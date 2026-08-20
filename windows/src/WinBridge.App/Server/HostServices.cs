using WinBridge.App.Features;
using WinBridge.App.Features.Automation;
using WinBridge.App.Providers;
using WinBridge.App.Storage;
using WinBridge.Core.Protocol;

namespace WinBridge.App.Server;

/// <summary>
/// Everything a connected session is allowed to reach on this machine.
///
/// This exists because the alternative was a constructor with a dozen
/// positional parameters that every new feature had to widen, touching each
/// call site for no reason. Bundling them also makes the reach of a session
/// visible in one place: if it is not in here, a phone cannot get at it.
/// </summary>
public sealed class HostServices
{
    public required BridgeStore Store { get; init; }
    public required MediaProvider Media { get; init; }
    public required SystemMetricsProvider Metrics { get; init; }
    public required VolumeProvider Volume { get; init; }
    public required PowerProvider Power { get; init; }
    public required ClipboardBridge Clipboard { get; init; }
    public required FileTransferService Files { get; init; }
    public required ScreenService Screen { get; init; }
    public required AudioService Audio { get; init; }
    public required InputInjector Input { get; init; }
    public required SystemQueryService SystemQuery { get; init; }
    public required AutomationService Automations { get; init; }
    public required NotificationHub Notifications { get; init; }

    /// <summary>How this machine can be reached over the other carrier.</summary>
    public required Func<PeerEvent> DescribePeer { get; init; }

    /// <summary>Runs an action on the WPF dispatcher; clipboard and input are STA-bound.</summary>
    public required Func<Action, Task> OnUiThread { get; init; }

    public required Action<string> Log { get; init; }

    /// <summary>
    /// Describes what this host will accept right now. Recomputed on every send
    /// rather than cached, because the answer changes when the user flips a
    /// switch and a stale capability set means a phone offering a button that
    /// does nothing — the exact thing v1 avoided for power actions.
    /// </summary>
    public FeatureSet DescribeFeatures(string carrier, bool inputAvailable)
    {
        var settings = Store.Settings;

        // Bluetooth cannot carry mirroring or audio. Saying so up front is
        // better than letting the phone start a stream that then crawls.
        bool wideband = carrier != "bluetooth";

        return new FeatureSet
        {
            Type = MessageTypesV2.HostFeatures,
            Clipboard = new ClipboardCaps
            {
                Send = settings.Clipboard.ToPhone,
                Receive = settings.Clipboard.FromPhone,
                MaxBytes = settings.Clipboard.MaxBytes,
            },
            Files = new FileCaps
            {
                Enabled = settings.Files.Enabled,
                MaxChunk = FileTransferService.ChunkSize,
                AutoAccept = settings.Files.AutoAccept,
            },
            Screen = new ScreenCaps
            {
                Send = settings.Screen.Share,
                Receive = settings.Screen.ViewPhone,
                Targets = Screen.TargetCount,
                CarrierOk = wideband || !settings.Screen.LanOnly,
            },
            Audio = new AudioCaps
            {
                Playback = settings.Audio.ToPhone || settings.Audio.FromPhone,
                Mic = settings.Audio.MicToPhone || settings.Audio.MicFromPhone,
                CarrierOk = wideband || !settings.Audio.LanOnly,
            },
            Input = new InputCaps
            {
                Send = settings.Screen.ViewPhone,
                Receive = settings.Input.Accept && inputAvailable,
                Reason = settings.Input.Accept ? null : "disabled in settings",
            },
            Automations = settings.Automation.Enabled && !settings.Automation.PanicStop,
            Shell = settings.Automation.Shell && !settings.Automation.PanicStop,
            Notifications = settings.Notifications.Enabled,
            Describe = true,
            Ring = true,
        };
    }
}
