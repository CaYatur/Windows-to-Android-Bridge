using WinBridge.App.Features;
using WinBridge.App.Localization;
using WinBridge.Core.Protocol;
using Model = WinBridge.Core.Protocol;

namespace WinBridge.App.Server;

/// <summary>
/// One connected phone.
///
/// Two loops run concurrently: a receive loop handling commands, and a push loop
/// sending state at whatever rate the client asked for. The client owns the
/// rate — the foreground UI wants 1 Hz, a widget wants 30 s — because only the
/// phone knows who is actually looking.
///
/// Feature work does not live here. This routes a message to the service that
/// owns it and gets out of the way; a router that also knew how to encode a
/// screen tile would be impossible to follow once there were twenty message
/// types, and there are now sixty.
/// </summary>
public sealed class ClientSession(
    ProtocolSession session,
    string carrier,
    HostServices services) : IPeerLink
{
    private readonly Dictionary<string, int> _rates = new()
    {
        ["media"] = 0,      // 0 == push on change only
        ["system"] = 2000,
        ["volume"] = 0,
    };

    private readonly SemaphoreSlim _rateLock = new(1, 1);
    private DateTime _lastSystemPush = DateTime.MinValue;
    private DateTime _lastMediaPush = DateTime.MinValue;

    private MediaState? _lastMedia;
    private VolumeState? _lastVolume;
    private volatile bool _mediaDirty = true;
    private CancellationToken _sessionToken = CancellationToken.None;

    public string PeerName => session.PeerName;
    public string PeerDeviceId => session.PeerDeviceId;
    public string Carrier => carrier;
    public DateTimeOffset ConnectedAt { get; } = DateTimeOffset.UtcNow;

    /// <summary>What the phone told us it can do. Null until it says.</summary>
    public FeatureSet? PeerFeatures { get; private set; }

    /// <summary>Where the phone last reported its battery and ringer state.</summary>
    public Model.PhoneState? PhoneState { get; private set; }

    public event Action<string>? Log;

    // ---- IPeerLink ---------------------------------------------------------

    public Task SendJsonAsync<T>(T message, CancellationToken ct) => session.SendJsonAsync(message, ct);
    public Task SendBlobAsync(string id, ReadOnlyMemory<byte> data, CancellationToken ct) =>
        session.SendBlobAsync(id, data, ct);
    public Task SendXferAsync(XferChunk chunk, CancellationToken ct) => session.SendXferAsync(chunk, ct);
    public bool TrySendMedia(in MediaPacket packet) => session.TrySendMedia(packet);

    // ---- lifecycle ---------------------------------------------------------

    public async Task RunAsync(CancellationToken ct)
    {
        void OnMediaChanged() => _mediaDirty = true;
        services.Media.Changed += OnMediaChanged;

        using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _sessionToken = linked.Token;

        try
        {
            await session.SendJsonAsync(HostSnapshot(), linked.Token);
            await session.SendJsonAsync(services.DescribePeer(), linked.Token);
            await SendFeaturesAsync(linked.Token);
            await ReconcileAudioAsync(linked.Token);

            var receive = ReceiveLoopAsync(linked.Token);
            var push = PushLoopAsync(linked.Token);

            await Task.WhenAny(receive, push);
            linked.Cancel();
            await Task.WhenAll(
                receive.ContinueWith(_ => { }, TaskScheduler.Default),
                push.ContinueWith(_ => { }, TaskScheduler.Default));
        }
        finally
        {
            services.Media.Changed -= OnMediaChanged;
            services.Screen.Stop(this);
            services.Audio.StopAllFor(this);
            services.Files.CancelAllFor(this);
            services.Notifications.Clear(this);
            services.PhoneMirror.Close(this);
        }
    }

    /// <summary>
    /// Re-sent whenever a setting changes, not only at connect. A phone showing
    /// a mirror button for a machine that has since turned sharing off is worse
    /// than one that never showed it.
    /// </summary>
    public Task SendFeaturesAsync(CancellationToken ct) =>
        session.SendJsonAsync(services.DescribeFeatures(carrier, services.Input.Available), ct);

    /// <summary>
    /// Asks the phone for the streams this machine wants to listen to, and stops
    /// the ones it no longer does.
    ///
    /// The listener drives, in both directions. Whoever wants to hear something
    /// asks for it; the producer answers with `audio.info` carrying the format it
    /// actually managed, and the listener opens its sink from that. Having the
    /// producer decide would mean guessing whether anyone is listening, and
    /// having both decide would open every stream twice.
    /// </summary>
    public async Task ReconcileAudioAsync(CancellationToken ct)
    {
        var settings = services.Store.Settings.Audio;
        await RequestAsync(StreamIds.PhoneAudio, settings.FromPhone, ct);
        await RequestAsync(StreamIds.PhoneMic, settings.MicFromPhone, ct);
    }

    private async Task RequestAsync(byte stream, bool wanted, CancellationToken ct)
    {
        string name = StreamIds.Name(stream);
        var settings = services.Store.Settings.Audio;

        if (wanted)
        {
            await session.SendJsonAsync(new AudioStart
            {
                Stream = name,
                Rate = settings.Rate,
                Channels = settings.Channels,
                FrameMs = settings.FrameMs,
            }, ct);
        }
        else
        {
            services.Audio.StopRender(stream);
            await session.SendJsonAsync(new AudioStop { Stream = name }, ct);
        }
    }

    private HostState HostSnapshot() => new()
    {
        Name = Environment.MachineName,
        Os = Environment.OSVersion.VersionString,
        UptimeSec = Environment.TickCount64 / 1000,
        Caps = services.Power.Caps,
    };

    private async Task ReceiveLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            InboundMessage message;
            try { message = await session.ReceiveAsync(ct); }
            catch (OperationCanceledException) { return; }
            catch (Exception ex) { Log?.Invoke($"receive ended: {ex.Message}"); return; }

            try { await HandleAsync(message, ct); }
            catch (Exception ex) { Log?.Invoke($"handling {message.JsonType} failed: {ex.Message}"); }
        }
    }

    private async Task HandleAsync(InboundMessage message, CancellationToken ct)
    {
        // Media and bulk frames are hot paths — they arrive tens of times a
        // second — so they are dispatched before the JSON switch is even
        // considered.
        switch (message.Inner)
        {
            case InnerType.Media:
            {
                var packet = message.AsMedia();

                // Dispatched on kind, not blindly handed to the audio service:
                // the phone screen arrives on this same lane, and feeding video
                // to a render stream drops it silently.
                if (packet.Kind == MediaKind.Video) services.PhoneMirror.OnPacket(this, packet);
                else services.Audio.Feed(packet);
                return;
            }

            case InnerType.Xfer:
                await services.Files.OnChunkAsync(message.AsXfer(), ct);
                return;
        }

        if (message.JsonType is null) return;

        if (await HandleCoreAsync(message, ct)) return;
        if (await HandleTransferAsync(message, ct)) return;
        if (await HandleStreamAsync(message, ct)) return;
        if (await HandleInputAsync(message, ct)) return;
        if (await HandleAutomationAsync(message, ct)) return;
        if (await HandleSystemAsync(message, ct)) return;

        Log?.Invoke($"ignored unknown message \"{message.JsonType}\"");
    }

    // ---- v1 messages -------------------------------------------------------

    private async Task<bool> HandleCoreAsync(InboundMessage message, CancellationToken ct)
    {
        switch (message.JsonType)
        {
            case MessageTypes.Subscribe:
            {
                var sub = message.As<SubscribeMessage>();
                await _rateLock.WaitAsync(ct);
                try
                {
                    foreach (var (stream, ms) in sub.Rates) _rates[stream] = ms;
                }
                finally { _rateLock.Release(); }

                // A rate change usually means a screen just opened; answer now
                // rather than making the user watch a stale value tick over.
                await PushSystemAsync(ct);
                await PushMediaAsync(ct, force: true);
                await PushVolumeAsync(ct, force: true);
                return true;
            }

            case MessageTypes.RequestState:
                await session.SendJsonAsync(HostSnapshot(), ct);
                await SendFeaturesAsync(ct);
                await PushSystemAsync(ct);
                await PushMediaAsync(ct, force: true);
                await PushVolumeAsync(ct, force: true);
                return true;

            case MessageTypes.RequestBlob:
            {
                var request = message.As<BlobRequest>();
                if (request.Id.StartsWith("art:", StringComparison.Ordinal))
                {
                    byte[]? art = services.Media.GetArt(request.Id[4..]);
                    if (art is not null) await session.SendBlobAsync(request.Id, art, ct);
                    else await SendErrorAsync("blob_not_found", request.Id, ct);
                }
                return true;
            }

            case MessageTypes.CommandMedia:
            {
                var command = message.As<MediaCommand>();
                if (!await services.Media.ControlAsync(command.Action, command.PosMs))
                    await SendErrorAsync("media_command_failed", command.Action, ct);
                _mediaDirty = true;
                return true;
            }

            case MessageTypes.CommandVolume:
            {
                var command = message.As<VolumeCommand>();
                bool ok = command.Action switch
                {
                    "set" => services.Volume.SetLevel(command.Level),
                    "mute" => services.Volume.SetMuted(true),
                    "unmute" => services.Volume.SetMuted(false),
                    _ => false,
                };
                if (!ok) await SendErrorAsync("volume_command_failed", command.Action, ct);
                await PushVolumeAsync(ct, force: true);
                return true;
            }

            case MessageTypes.CommandPower:
            {
                var command = message.As<PowerCommand>();
                if (!IsAllowed(command.Action))
                {
                    await SendErrorAsync("power_unsupported", command.Action, ct);
                    return true;
                }
                Log?.Invoke($"{session.PeerName} requested {command.Action}");
                if (!services.Power.Execute(command.Action, command.DelaySec, out string? error))
                    await SendErrorAsync("power_command_failed", error ?? command.Action, ct);
                return true;
            }

            case MessageTypes.Ping:
                await session.SendJsonAsync(new PongMessage { Echo = message.As<PingMessage>().Echo }, ct);
                return true;

            case MessageTypesV2.ClientFeatures:
                PeerFeatures = message.As<FeatureSet>();
                Log?.Invoke($"phone features: clipboard={PeerFeatures.Clipboard.Send}/{PeerFeatures.Clipboard.Receive}, " +
                            $"screen={PeerFeatures.Screen.Send}, notifications={PeerFeatures.Notifications}");
                return true;

            case MessageTypesV2.PhoneState:
                PhoneState = message.As<Model.PhoneState>();
                return true;

            case MessageTypesV2.ClipboardSet:
                await ApplyClipboardAsync(message.As<ClipboardMessage>(), ct);
                return true;

            case MessageTypesV2.ClipboardGet:
                await SendClipboardAsync(ct);
                return true;

            default:
                return false;
        }
    }

    // ---- clipboard ---------------------------------------------------------

    private async Task ApplyClipboardAsync(ClipboardMessage clip, CancellationToken ct)
    {
        if (!services.Store.Settings.Clipboard.FromPhone)
        {
            await SendErrorAsync("clipboard_disabled", "receiving is off on the PC", ct);
            return;
        }

        bool applied = false;
        await services.OnUiThread(() => applied = services.Clipboard.Apply(clip));

        if (applied && services.Store.Settings.Clipboard.Notify)
        {
            string preview = clip.Text ?? "(image)";
            services.Notifications.Toast(
                Strings.Format("clip.copied", clip.Label ?? PeerName),
                preview.Length > 120 ? preview[..120] + "…" : preview);
        }
        if (applied) Log?.Invoke($"clipboard from {PeerName} applied");
    }

    public async Task SendClipboardAsync(CancellationToken ct)
    {
        if (!services.Store.Settings.Clipboard.ToPhone) return;

        ClipboardMessage? clip = null;
        await services.OnUiThread(() => clip = services.Clipboard.Read());
        if (clip is null) return;

        await session.SendJsonAsync(clip, ct);
    }

    /// <summary>Called by the host when the Windows clipboard changes.</summary>
    public Task PushClipboardAsync(ClipboardMessage clip, CancellationToken ct) =>
        services.Store.Settings.Clipboard.ToPhone
            ? session.SendJsonAsync(clip, ct)
            : Task.CompletedTask;

    // ---- files -------------------------------------------------------------

    private async Task<bool> HandleTransferAsync(InboundMessage message, CancellationToken ct)
    {
        switch (message.JsonType)
        {
            case MessageTypesV2.XferOffer:
                await services.Files.OnOfferAsync(this, message.As<XferOffer>(), ct);
                return true;
            case MessageTypesV2.XferAccept:
                await services.Files.OnAcceptedAsync(message.As<XferAccept>(), ct);
                return true;
            case MessageTypesV2.XferReject:
                services.Files.OnRejected(message.As<XferReject>());
                return true;
            case MessageTypesV2.XferDone:
                services.Files.OnDone(message.As<XferDone>());
                return true;
            case MessageTypesV2.XferCancel:
                services.Files.Cancel(message.As<XferCancel>().Id);
                return true;
            case MessageTypesV2.XferProgress:
                return true;   // informational; the sender already knows
            default:
                return false;
        }
    }

    // ---- screen and audio ---------------------------------------------------

    private async Task<bool> HandleStreamAsync(InboundMessage message, CancellationToken ct)
    {
        switch (message.JsonType)
        {
            case MessageTypesV2.ScreenList:
                await session.SendJsonAsync(new ScreenTargets { Items = services.Screen.Targets() }, ct);
                return true;

            case MessageTypesV2.StreamStart:
            {
                var request = message.As<StreamStart>();
                byte id = StreamIds.FromName(request.Stream);

                if (id == StreamIds.PcScreen)
                {
                    var info = services.Screen.Start(this, request);
                    await session.SendJsonAsync(info, ct);

                    // "audio: true" on a screen request means "and the sound
                    // that goes with it", so the matching stream comes up
                    // without a second round trip.
                    if (info.Active && request.Audio)
                    {
                        var audio = services.Audio.StartCapture(this, new AudioStart
                        {
                            Stream = StreamIds.Name(StreamIds.PcAudio),
                            Rate = services.Store.Settings.Audio.Rate,
                            Channels = services.Store.Settings.Audio.Channels,
                            FrameMs = services.Store.Settings.Audio.FrameMs,
                        });
                        await session.SendJsonAsync(audio, ct);
                    }
                }
                else
                {
                    // phone.screen: the phone is the sender, so all this side has
                    // to do is agree.
                    await session.SendJsonAsync(new StreamInfo
                    {
                        Stream = request.Stream,
                        Active = services.Store.Settings.Screen.ViewPhone,
                        Reason = services.Store.Settings.Screen.ViewPhone ? null : "viewing the phone is off on the PC",
                    }, ct);
                }
                return true;
            }

            case MessageTypesV2.StreamStop:
            {
                var request = message.As<StreamStop>();
                if (StreamIds.FromName(request.Stream) == StreamIds.PcScreen)
                {
                    services.Screen.Stop(this);
                    services.Audio.StopCapture(StreamIds.PcAudio);
                }
                await session.SendJsonAsync(
                    new StreamInfo { Stream = request.Stream, Active = false, Reason = "stopped" }, ct);
                return true;
            }

            case MessageTypesV2.StreamConfig:
            {
                var info = services.Screen.Configure(this, message.As<StreamConfig>());
                if (info is not null) await session.SendJsonAsync(info, ct);
                return true;
            }

            case MessageTypesV2.StreamStats:
                services.Screen.OnStats(this, message.As<StreamStats>());
                return true;

            case MessageTypesV2.StreamInfo:
            {
                // The phone describing its own screen stream: geometry for the
                // viewer, or the reason it could not start.
                var info = message.As<StreamInfo>();
                if (StreamIds.FromName(info.Stream) == StreamIds.PhoneScreen)
                    services.PhoneMirror.OnInfo(this, info);
                return true;
            }

            case MessageTypesV2.AudioStart:
            {
                var request = message.As<AudioStart>();
                byte id = StreamIds.FromName(request.Stream);
                var info = id is StreamIds.PcAudio or StreamIds.PcMic
                    ? services.Audio.StartCapture(this, request)
                    : services.Audio.StartRender(request);
                await session.SendJsonAsync(info, ct);
                return true;
            }

            case MessageTypesV2.AudioStop:
            {
                var request = message.As<AudioStop>();
                byte id = StreamIds.FromName(request.Stream);
                if (id is StreamIds.PcAudio or StreamIds.PcMic) services.Audio.StopCapture(id);
                else services.Audio.StopRender(id);
                await session.SendJsonAsync(
                    new AudioInfo { Stream = request.Stream, Active = false, Reason = "stopped" }, ct);
                return true;
            }

            case MessageTypesV2.AudioDevices:
                await session.SendJsonAsync(new AudioDevices { Items = services.Audio.Devices() }, ct);
                return true;

            case MessageTypesV2.AudioInfo:
            {
                // The phone reporting a stream it owns. This is what opens the
                // sink on this side, and it carries the format the phone
                // actually got rather than the one we asked for — a device that
                // would only give 44.1 kHz mono has to be believed, not assumed.
                var info = message.As<AudioInfo>();
                byte id = StreamIds.FromName(info.Stream);
                if (id is not (StreamIds.PhoneAudio or StreamIds.PhoneMic)) return true;

                if (info.Active)
                {
                    var opened = services.Audio.StartRender(new AudioStart
                    {
                        Stream = info.Stream,
                        Rate = info.Rate > 0 ? info.Rate : 48000,
                        Channels = info.Channels > 0 ? info.Channels : 2,
                    });
                    if (!opened.Active) Log?.Invoke($"could not play {info.Stream}: {opened.Reason}");
                }
                else
                {
                    services.Audio.StopRender(id);
                    if (info.Reason is not null) Log?.Invoke($"{info.Stream} stopped: {info.Reason}");
                }
                return true;
            }

            default:
                return false;
        }
    }

    // ---- remote input -------------------------------------------------------

    private async Task<bool> HandleInputAsync(InboundMessage message, CancellationToken ct)
    {
        bool isInput = message.JsonType is
            MessageTypesV2.InputMouse or MessageTypesV2.InputKey or MessageTypesV2.InputText or
            MessageTypesV2.InputScroll or MessageTypesV2.InputNav;

        if (!isInput) return false;

        // Two gates, and both matter: the global setting says whether this
        // machine ever accepts remote input, and the per-stream flag says whether
        // this particular viewing session was started with interaction on.
        if (!services.Store.Settings.Input.Accept)
        {
            await SendErrorAsync("input_disabled", "remote input is off on the PC", ct);
            return true;
        }
        if (!services.Screen.IsInteractive(this))
        {
            await SendErrorAsync("input_not_interactive", "this mirror session is view-only", ct);
            return true;
        }

        await services.OnUiThread(() => Inject(message));
        return true;
    }

    private void Inject(InboundMessage message)
    {
        switch (message.JsonType)
        {
            case MessageTypesV2.InputMouse:
            {
                var command = message.As<InputMouse>();

                if (command.Relative) services.Input.MoveRelative((int)command.Dx, (int)command.Dy);
                else if (services.Screen.TryMapToDesktop(this, command.X, command.Y, out int x, out int y))
                    services.Input.MoveAbsolute(x, y);

                switch (command.Action)
                {
                    case "down": services.Input.Button(command.Button, down: true); break;
                    case "up": services.Input.Button(command.Button, down: false); break;
                    case "click": services.Input.Click(command.Button); break;
                    case "double": services.Input.Click(command.Button); services.Input.Click(command.Button); break;
                    case "wheel": services.Input.Wheel(command.Delta, command.HorizontalDelta); break;
                }
                break;
            }

            case MessageTypesV2.InputKey:
                services.Input.Key(message.As<InputKey>());
                break;

            case MessageTypesV2.InputText:
                services.Input.Text(message.As<InputText>().Text);
                break;

            case MessageTypesV2.InputScroll:
            {
                var command = message.As<InputScroll>();
                if (services.Screen.TryMapToDesktop(this, command.X, command.Y, out int x, out int y))
                    services.Input.MoveAbsolute(x, y);

                // A phone reports scroll as a fraction of the surface; Windows
                // counts notches of 120.
                services.Input.Wheel((int)Math.Round(-command.Dy * 120 * 8), (int)Math.Round(command.Dx * 120 * 8));
                break;
            }

            case MessageTypesV2.InputNav:
            {
                // Windows has no back/home/recents, so these map onto the shell
                // shortcuts that do the same job.
                var command = message.As<InputNav>();
                var mapped = command.Action switch
                {
                    "back" => new InputKey { Code = "left", Mods = ["alt"] },
                    "home" => new InputKey { Code = "d", Mods = ["win"] },
                    "recents" => new InputKey { Code = "tab", Mods = ["win"] },
                    "notifications" => new InputKey { Code = "n", Mods = ["win"] },
                    "quicksettings" => new InputKey { Code = "a", Mods = ["win"] },
                    "screenshot" => new InputKey { Code = "s", Mods = ["win", "shift"] },
                    "split" => new InputKey { Code = "left", Mods = ["win"] },
                    "lock" => new InputKey { Code = "l", Mods = ["win"] },
                    _ => null,
                };
                if (mapped is not null) services.Input.Key(mapped);
                break;
            }
        }
    }

    // ---- automations --------------------------------------------------------

    private async Task<bool> HandleAutomationAsync(InboundMessage message, CancellationToken ct)
    {
        switch (message.JsonType)
        {
            case MessageTypesV2.AutoList:
                await session.SendJsonAsync(services.Automations.Catalog(PeerDeviceId), ct);
                return true;

            case MessageTypesV2.AutoGet:
            {
                var request = message.As<AutoGetRequest>();
                var found = services.Automations.Store.Find(request.Id);
                await session.SendJsonAsync(new AutoDefinition
                {
                    Automation = found,
                    Error = found is null ? "no such automation" : null,
                }, ct);
                return true;
            }

            case MessageTypesV2.AutoSave:
            {
                var saved = await services.Automations.SaveAsync(
                    message.As<AutoSaveRequest>().Automation, this, ct);
                await session.SendJsonAsync(saved, ct);
                await session.SendJsonAsync(services.Automations.Catalog(PeerDeviceId), ct);
                return true;
            }

            case MessageTypesV2.AutoDelete:
            {
                services.Automations.Store.Delete(message.As<AutoDeleteRequest>().Id);
                await session.SendJsonAsync(services.Automations.Catalog(PeerDeviceId), ct);
                return true;
            }

            case MessageTypesV2.AutoRun:
            {
                var request = message.As<AutoRunRequest>();

                // Not awaited: a run can sit on a confirmation dialog for as long
                // as the user takes, and the receive loop has to keep serving
                // heartbeats and everything else in the meantime.
                _ = Task.Run(async () =>
                {
                    var result = await services.Automations.RunAsync(
                        request, this,
                        automationEvent => _ = SafeSendAsync(automationEvent),
                        _sessionToken);

                    await SafeSendAsync(result);
                }, ct);
                return true;
            }

            case MessageTypesV2.AutoCancel:
                services.Automations.Cancel(message.As<AutoCancelRequest>().RunId);
                return true;

            case MessageTypesV2.AutoLog:
                await session.SendJsonAsync(
                    new AutoLog { Items = services.Automations.Store.RecentRuns(80) }, ct);
                return true;

            default:
                return false;
        }
    }

    private async Task SafeSendAsync<T>(T message)
    {
        try { await session.SendJsonAsync(message, _sessionToken); }
        catch (Exception ex) { Log?.Invoke($"could not send {typeof(T).Name}: {ex.Message}"); }
    }

    // ---- machine introspection and notifications ----------------------------

    private async Task<bool> HandleSystemAsync(InboundMessage message, CancellationToken ct)
    {
        switch (message.JsonType)
        {
            case MessageTypesV2.SysWindows:
                await session.SendJsonAsync(new WindowList { Items = services.SystemQuery.Windows() }, ct);
                return true;

            case MessageTypesV2.SysWindow:
            {
                if (!services.SystemQuery.Window(message.As<WindowCommand>(), out string? error))
                    await SendErrorAsync("window_command_failed", error, ct);
                return true;
            }

            case MessageTypesV2.SysProcesses:
                await session.SendJsonAsync(
                    new ProcessList { Items = services.SystemQuery.Processes(message.As<ProcessesRequest>().Top) }, ct);
                return true;

            case MessageTypesV2.SysProcess:
            {
                var command = message.As<ProcessCommand>();
                if (!services.SystemQuery.KillProcess(command.Pid, out string? error))
                    await SendErrorAsync("process_command_failed", error, ct);
                return true;
            }

            case MessageTypesV2.SysDescribe:
            {
                var request = message.As<DescribeRequest>();
                var description = await services.SystemQuery.DescribeAsync(request);

                if (request.Image)
                {
                    byte[]? jpeg = services.SystemQuery.Screenshot(request.Target);
                    if (jpeg is not null)
                    {
                        string id = "shot:" + Guid.NewGuid().ToString("N")[..12];
                        await session.SendBlobAsync(id, jpeg, ct);
                        description = description with { ImageHash = id };
                    }
                }

                await session.SendJsonAsync(description, ct);
                return true;
            }

            case MessageTypesV2.SysNotify:
            {
                var request = message.As<SysNotify>();
                services.Notifications.Toast(request.Title, request.Text, request.Level);
                return true;
            }

            case MessageTypesV2.SysOpen:
            {
                if (!services.SystemQuery.Open(message.As<SysOpen>().Target, out string? error))
                    await SendErrorAsync("open_failed", error, ct);
                return true;
            }

            case MessageTypesV2.NotifPost:
                services.Notifications.OnPost(this, message.As<NotifPost>());
                return true;

            case MessageTypesV2.NotifRemove:
                services.Notifications.OnRemoved(message.As<NotifRemove>().Key);
                return true;

            case MessageTypesV2.NotifState:
            {
                var state = message.As<NotifState>();
                if (!state.Granted && services.Store.Settings.Notifications.Enabled)
                    Log?.Invoke($"{PeerName} cannot mirror notifications: {state.Reason ?? "access not granted"}");
                return true;
            }

            default:
                return false;
        }
    }

    private bool IsAllowed(string action) => action switch
    {
        "lock" => services.Power.Caps.Lock,
        "sleep" => services.Power.Caps.Sleep,
        "hibernate" => services.Power.Caps.Hibernate,
        "shutdown" => services.Power.Caps.Shutdown,
        "restart" => services.Power.Caps.Restart,
        "logoff" => services.Power.Caps.Logoff,
        "display_off" => services.Power.Caps.DisplayOff,
        _ => false,
    };

    private Task SendErrorAsync(string code, string? detail, CancellationToken ct) =>
        session.SendJsonAsync(new ErrorMessage { Code = code, Detail = detail }, ct);

    // ---- state push --------------------------------------------------------

    private async Task PushLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await PushSystemAsync(ct);
                await PushMediaAsync(ct);
                await PushVolumeAsync(ct);
            }
            catch (OperationCanceledException) { return; }
            catch (Exception ex) { Log?.Invoke($"push failed: {ex.Message}"); return; }

            // 250 ms is the scheduling granularity, not the send rate: each
            // stream still only goes out when its own interval has elapsed.
            try { await Task.Delay(250, ct); }
            catch (OperationCanceledException) { return; }
        }
    }

    private async Task PushSystemAsync(CancellationToken ct)
    {
        int rate = Rate("system");
        if (rate < 0) return;
        if (rate > 0 && (DateTime.UtcNow - _lastSystemPush).TotalMilliseconds < rate) return;

        _lastSystemPush = DateTime.UtcNow;
        await session.SendJsonAsync(services.Metrics.Sample(), ct);
    }

    private async Task PushMediaAsync(CancellationToken ct, bool force = false)
    {
        int rate = Rate("media");
        if (rate < 0) return;

        // Even with rate 0 ("on change only"), poll occasionally: it is the only
        // way to notice a seek that GSMTC did not raise an event for.
        bool timeElapsed = (DateTime.UtcNow - _lastMediaPush).TotalMilliseconds >=
            (rate > 0 ? rate : PositionPollMs);
        if (!force && !_mediaDirty && !timeElapsed) return;

        _mediaDirty = false;
        var previousPush = _lastMediaPush;
        _lastMediaPush = DateTime.UtcNow;

        var state = await services.Media.ReadAsync();

        // The client advances the position itself between messages, so resending
        // an otherwise identical state every tick would waste a round trip --
        // over RFCOMM that matters. But suppressing *all* position-only updates
        // means a seek on the PC never reaches the phone, so the comparison is
        // against where the client would have predicted the position to be:
        // ordinary playback drifts by milliseconds, a seek by seconds.
        if (!force && _lastMedia is not null && SameTrack(_lastMedia, state))
        {
            double predicted = _lastMedia.PosMs +
                (state.Playing ? (DateTime.UtcNow - previousPush).TotalMilliseconds : 0);

            if (Math.Abs(predicted - state.PosMs) < PositionResyncToleranceMs) return;
        }

        _lastMedia = state;
        await session.SendJsonAsync(state, ct);
    }

    /// <summary>Everything except where the playhead is.</summary>
    private static bool SameTrack(MediaState a, MediaState b) =>
        a.Title == b.Title && a.Artist == b.Artist && a.Album == b.Album &&
        a.Playing == b.Playing && a.ArtHash == b.ArtHash && a.AppId == b.AppId &&
        a.CanNext == b.CanNext && a.CanPrev == b.CanPrev;

    private const double PositionResyncToleranceMs = 1500;
    private const double PositionPollMs = 2000;

    private async Task PushVolumeAsync(CancellationToken ct, bool force = false)
    {
        if (Rate("volume") < 0) return;

        var state = services.Volume.Read();
        if (!force && _lastVolume is not null &&
            _lastVolume.Level == state.Level && _lastVolume.Muted == state.Muted)
            return;

        _lastVolume = state;
        await session.SendJsonAsync(state, ct);
    }

    private int Rate(string stream)
    {
        _rateLock.Wait();
        try { return _rates.TryGetValue(stream, out int ms) ? ms : 0; }
        finally { _rateLock.Release(); }
    }
}
