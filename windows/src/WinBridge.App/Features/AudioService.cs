using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using WinBridge.App.Server;
using WinBridge.App.Storage;
using WinBridge.Core.Protocol;

namespace WinBridge.App.Features;

/// <summary>
/// Moves audio in both directions: what this machine is playing, its microphone,
/// and whatever the phone sends back.
///
/// PCM on the wire, not a codec. At 48 kHz stereo that is 1.5 Mbit/s, which a
/// LAN does not notice, and it costs no encode or decode latency at either end —
/// the whole point of the exercise is that the sound lines up with the picture.
/// It is also why audio is refused over Bluetooth rather than quietly degraded.
///
/// There is no virtual sound card here and there cannot be: a device other apps
/// can select is a signed kernel driver, not something a user-mode app can
/// conjure. What the render-device setting gives instead is a route — point it
/// at a virtual cable and the phone microphone shows up as a recording device
/// everywhere else on the system.
/// </summary>
public sealed class AudioService(BridgeStore store) : IDisposable
{
    private readonly ConcurrentDictionary<byte, CaptureStream> _captures = new();
    private readonly ConcurrentDictionary<byte, RenderStream> _renders = new();

    public event Action<string>? Log;

    public List<AudioDevice> Devices()
    {
        var devices = new List<AudioDevice>();
        try
        {
            var enumerator = Wasapi.CreateEnumerator();

            foreach (var flow in new[] { Wasapi.EDataFlow.Render, Wasapi.EDataFlow.Capture })
            {
                string? defaultId = null;
                if (enumerator.GetDefaultAudioEndpoint(flow, Wasapi.ERole.Multimedia, out var fallback) == 0)
                    fallback.GetId(out defaultId);

                if (enumerator.EnumAudioEndpoints(flow, Wasapi.DEVICE_STATE_ACTIVE, out var collection) != 0)
                    continue;

                collection.GetCount(out uint count);
                for (uint index = 0; index < count; index++)
                {
                    if (collection.Item(index, out var device) != 0) continue;
                    device.GetId(out string id);
                    devices.Add(new AudioDevice
                    {
                        Id = id,
                        Name = Wasapi.FriendlyName(device),
                        Flow = flow == Wasapi.EDataFlow.Render ? "render" : "capture",
                        IsDefault = id == defaultId,
                    });
                }
            }
        }
        catch (Exception ex)
        {
            Log?.Invoke($"audio device enumeration failed: {ex.Message}");
        }

        return devices;
    }

    // ---- capture (this machine -> phone) -----------------------------------

    public AudioInfo StartCapture(IPeerLink link, AudioStart request)
    {
        byte streamId = StreamIds.FromName(request.Stream);
        bool loopback = streamId == StreamIds.PcAudio;

        if (streamId != StreamIds.PcAudio && streamId != StreamIds.PcMic)
            return Refused(request, "unknown capture stream");

        var settings = store.Settings;
        if (loopback && !settings.Audio.ToPhone) return Refused(request, "PC audio sharing is off");
        if (!loopback && !settings.Audio.MicToPhone) return Refused(request, "PC microphone sharing is off");

        if (settings.Audio.LanOnly && link.Carrier == "bluetooth")
            return Refused(request, "audio needs the LAN link; Bluetooth cannot carry it");

        StopCapture(streamId);

        try
        {
            string? deviceId = request.Device
                ?? (loopback ? settings.Audio.LoopbackDevice : settings.Audio.CaptureDevice);

            var stream = new CaptureStream(
                streamId, loopback, deviceId,
                Math.Clamp(request.Rate, 8000, 48000),
                Math.Clamp(request.Channels, 1, 2),
                Math.Clamp(request.FrameMs, 5, 100),
                link,
                message => Log?.Invoke(message));

            _captures[streamId] = stream;
            stream.Start(() => _captures.TryRemove(streamId, out _));

            Log?.Invoke($"{request.Stream} -> {link.PeerName} at {stream.Rate} Hz, {stream.Channels} ch");
            return stream.Describe();
        }
        catch (Exception ex)
        {
            return Refused(request, ex.Message);
        }
    }

    public void StopCapture(byte streamId)
    {
        if (_captures.TryRemove(streamId, out var stream)) stream.Stop();
    }

    public void StopAllFor(IPeerLink link)
    {
        foreach (var (id, stream) in _captures)
            if (ReferenceEquals(stream.Link, link)) StopCapture(id);
    }

    // ---- render (phone -> this machine) ------------------------------------

    public AudioInfo StartRender(AudioStart request)
    {
        byte streamId = StreamIds.FromName(request.Stream);
        if (streamId != StreamIds.PhoneAudio && streamId != StreamIds.PhoneMic)
            return Refused(request, "unknown render stream");

        var settings = store.Settings;
        if (streamId == StreamIds.PhoneAudio && !settings.Audio.FromPhone)
            return Refused(request, "phone audio playback is off");
        if (streamId == StreamIds.PhoneMic && !settings.Audio.MicFromPhone)
            return Refused(request, "phone microphone playback is off");

        StopRender(streamId);

        try
        {
            string? deviceId = request.Device ?? (streamId == StreamIds.PhoneAudio
                ? settings.Audio.RenderDevice
                : settings.Audio.MicRenderDevice);

            var stream = new RenderStream(
                streamId, deviceId,
                Math.Clamp(request.Rate, 8000, 48000),
                Math.Clamp(request.Channels, 1, 2),
                message => Log?.Invoke(message));

            _renders[streamId] = stream;
            stream.Start(() => _renders.TryRemove(streamId, out _));

            Log?.Invoke($"playing {request.Stream} on {stream.DeviceName}");
            return stream.Describe();
        }
        catch (Exception ex)
        {
            return Refused(request, ex.Message);
        }
    }

    public void StopRender(byte streamId)
    {
        if (_renders.TryRemove(streamId, out var stream)) stream.Stop();
    }

    /// <summary>Hands an arriving audio packet to whichever render stream owns it.</summary>
    public void Feed(in MediaPacket packet)
    {
        if (_renders.TryGetValue(packet.Stream, out var stream)) stream.Enqueue(packet.Payload.Span);
    }

    private AudioInfo Refused(AudioStart request, string reason)
    {
        Log?.Invoke($"audio refused ({request.Stream}): {reason}");
        return new AudioInfo { Stream = request.Stream, Active = false, Reason = reason };
    }

    public void Dispose()
    {
        foreach (var stream in _captures.Values) stream.Stop();
        foreach (var stream in _renders.Values) stream.Stop();
        _captures.Clear();
        _renders.Clear();
    }

    // ---- capture stream ----------------------------------------------------

    private sealed class CaptureStream(
        byte streamId, bool loopback, string? deviceId,
        int rate, int channels, int frameMs, IPeerLink link, Action<string> log)
    {
        private readonly CancellationTokenSource _stop = new();
        private uint _seq;

        public IPeerLink Link => link;
        public int Rate => rate;
        public int Channels => channels;
        public string DeviceName { get; private set; } = "default";

        public void Start(Action onEnded)
        {
            var thread = new Thread(() =>
            {
                try { Loop(); }
                catch (OperationCanceledException) { }
                catch (Exception ex) { log($"audio capture stopped: {ex.Message}"); }
                finally { onEnded(); }
            })
            {
                IsBackground = true,
                Name = "winbridge-audio-capture",
                // Above normal: a late audio buffer is a click the user hears,
                // which is far more noticeable than a late video frame.
                Priority = ThreadPriority.AboveNormal,
            };
            thread.Start();
        }

        private void Loop()
        {
            var enumerator = Wasapi.CreateEnumerator();
            IMMDeviceOrThrow(enumerator, out var device);
            DeviceName = Wasapi.FriendlyName(device);

            var iid = Wasapi.IID_IAudioClient;
            Wasapi.Check(device.Activate(ref iid, Wasapi.CLSCTX_ALL, IntPtr.Zero, out object clientObject),
                "IAudioClient activate");
            var client = (Wasapi.IAudioClient)clientObject;

            Wasapi.Check(client.GetMixFormat(out IntPtr formatPointer), "GetMixFormat");
            var (format, isFloat) = Wasapi.ReadFormat(formatPointer);

            try
            {
                // 100 ms of buffer: long enough that a scheduling hiccup does not
                // drop audio, short enough that it never becomes audible lag.
                uint flags = loopback ? Wasapi.AUDCLNT_STREAMFLAGS_LOOPBACK : 0;
                Wasapi.Check(
                    client.Initialize(Wasapi.AUDCLNT_SHAREMODE_SHARED, flags, 1_000_000, 0, formatPointer, IntPtr.Zero),
                    "IAudioClient.Initialize");
            }
            finally
            {
                Wasapi.CoTaskMemFree(formatPointer);
            }

            var captureIid = Wasapi.IID_IAudioCaptureClient;
            Wasapi.Check(client.GetService(ref captureIid, out object captureObject), "GetService(capture)");
            var capture = (Wasapi.IAudioCaptureClient)captureObject;

            Wasapi.Check(client.Start(), "IAudioClient.Start");

            var converter = new PcmConverter(format.nSamplesPerSec, format.nChannels, isFloat, format.wBitsPerSample, rate, channels);
            int framesPerPacket = Math.Max(1, rate * frameMs / 1000);
            var pending = new List<short>(framesPerPacket * channels * 2);
            var token = _stop.Token;

            try
            {
                while (!token.IsCancellationRequested)
                {
                    Wasapi.Check(capture.GetNextPacketSize(out uint available), "GetNextPacketSize");
                    if (available == 0)
                    {
                        // Half a frame: long enough not to spin, short enough
                        // that the packet cadence stays even.
                        token.WaitHandle.WaitOne(Math.Max(1, frameMs / 2));
                        continue;
                    }

                    while (available > 0 && !token.IsCancellationRequested)
                    {
                        Wasapi.Check(
                            capture.GetBuffer(out IntPtr data, out uint frames, out uint bufferFlags, out _, out _),
                            "GetBuffer");
                        try
                        {
                            if (frames > 0)
                            {
                                int bytes = (int)frames * format.nBlockAlign;
                                bool silent = (bufferFlags & Wasapi.AUDCLNT_BUFFERFLAGS_SILENT) != 0;
                                converter.Append(data, bytes, silent, pending);
                            }
                        }
                        finally
                        {
                            capture.ReleaseBuffer(frames);
                        }

                        Wasapi.Check(capture.GetNextPacketSize(out available), "GetNextPacketSize");
                    }

                    Emit(pending, framesPerPacket);
                }
            }
            finally
            {
                try { client.Stop(); } catch { }
                Marshal.ReleaseComObject(capture);
                Marshal.ReleaseComObject(client);
            }
        }

        private void Emit(List<short> pending, int framesPerPacket)
        {
            int samplesPerPacket = framesPerPacket * channels;

            while (pending.Count >= samplesPerPacket)
            {
                var payload = new byte[samplesPerPacket * 2];
                for (int index = 0; index < samplesPerPacket; index++)
                {
                    short sample = pending[index];
                    payload[index * 2] = (byte)(sample & 0xFF);
                    payload[index * 2 + 1] = (byte)((sample >> 8) & 0xFF);
                }
                pending.RemoveRange(0, samplesPerPacket);

                link.TrySendMedia(new MediaPacket(
                    MediaKind.Audio, streamId, _seq++,
                    (uint)Environment.TickCount, MediaFlags.EndOfFrame, payload));
            }
        }

        private void IMMDeviceOrThrow(Wasapi.IMMDeviceEnumerator enumerator, out Wasapi.IMMDevice device)
        {
            if (!string.IsNullOrEmpty(deviceId) && enumerator.GetDevice(deviceId, out device) == 0) return;

            // Loopback captures from a *render* endpoint; that is the whole trick.
            var flow = loopback ? Wasapi.EDataFlow.Render : Wasapi.EDataFlow.Capture;
            Wasapi.Check(enumerator.GetDefaultAudioEndpoint(flow, Wasapi.ERole.Multimedia, out device),
                "GetDefaultAudioEndpoint");
        }

        public AudioInfo Describe() => new()
        {
            Stream = StreamIds.Name(streamId),
            Active = !_stop.IsCancellationRequested,
            Rate = rate,
            Channels = channels,
            FrameMs = frameMs,
            Device = DeviceName,
        };

        public void Stop()
        {
            try { _stop.Cancel(); } catch { }
        }
    }

    // ---- render stream -----------------------------------------------------

    private sealed class RenderStream(byte streamId, string? deviceId, int rate, int channels, Action<string> log)
    {
        private readonly CancellationTokenSource _stop = new();
        private readonly ConcurrentQueue<short[]> _queue = new();
        private int _queued;

        /// <summary>
        /// About a third of a second of audio. Past this the link is behind and
        /// keeping the backlog only converts a gap into delay — for live audio,
        /// dropping and staying current is the better trade.
        /// </summary>
        private const int MaxQueuedPackets = 16;

        public string DeviceName { get; private set; } = "default";

        public void Enqueue(ReadOnlySpan<byte> pcm)
        {
            if (pcm.Length < 2) return;

            if (Volatile.Read(ref _queued) >= MaxQueuedPackets)
            {
                if (_queue.TryDequeue(out _)) Interlocked.Decrement(ref _queued);
            }

            var samples = new short[pcm.Length / 2];
            for (int index = 0; index < samples.Length; index++)
                samples[index] = (short)(pcm[index * 2] | (pcm[index * 2 + 1] << 8));

            _queue.Enqueue(samples);
            Interlocked.Increment(ref _queued);
        }

        public void Start(Action onEnded)
        {
            var thread = new Thread(() =>
            {
                try { Loop(); }
                catch (OperationCanceledException) { }
                catch (Exception ex) { log($"audio render stopped: {ex.Message}"); }
                finally { onEnded(); }
            })
            {
                IsBackground = true,
                Name = "winbridge-audio-render",
                Priority = ThreadPriority.AboveNormal,
            };
            thread.Start();
        }

        private void Loop()
        {
            var enumerator = Wasapi.CreateEnumerator();
            Wasapi.IMMDevice device;
            if (string.IsNullOrEmpty(deviceId) || enumerator.GetDevice(deviceId, out device!) != 0)
            {
                Wasapi.Check(
                    enumerator.GetDefaultAudioEndpoint(Wasapi.EDataFlow.Render, Wasapi.ERole.Multimedia, out device),
                    "GetDefaultAudioEndpoint");
            }
            DeviceName = Wasapi.FriendlyName(device);

            var iid = Wasapi.IID_IAudioClient;
            Wasapi.Check(device.Activate(ref iid, Wasapi.CLSCTX_ALL, IntPtr.Zero, out object clientObject),
                "IAudioClient activate");
            var client = (Wasapi.IAudioClient)clientObject;

            Wasapi.Check(client.GetMixFormat(out IntPtr formatPointer), "GetMixFormat");
            var (format, isFloat) = Wasapi.ReadFormat(formatPointer);

            try
            {
                Wasapi.Check(
                    client.Initialize(Wasapi.AUDCLNT_SHAREMODE_SHARED, 0, 2_000_000, 0, formatPointer, IntPtr.Zero),
                    "IAudioClient.Initialize");
            }
            finally
            {
                Wasapi.CoTaskMemFree(formatPointer);
            }

            Wasapi.Check(client.GetBufferSize(out uint bufferFrames), "GetBufferSize");

            var renderIid = Wasapi.IID_IAudioRenderClient;
            Wasapi.Check(client.GetService(ref renderIid, out object renderObject), "GetService(render)");
            var render = (Wasapi.IAudioRenderClient)renderObject;

            Wasapi.Check(client.Start(), "IAudioClient.Start");

            var converter = new PcmConverter(
                (uint)rate, (ushort)channels, false, 16,
                (int)format.nSamplesPerSec, format.nChannels);

            var carry = new List<short>();
            var token = _stop.Token;

            try
            {
                while (!token.IsCancellationRequested)
                {
                    while (carry.Count < bufferFrames * format.nChannels && _queue.TryDequeue(out var packet))
                    {
                        Interlocked.Decrement(ref _queued);
                        converter.AppendSamples(packet, carry);
                    }

                    Wasapi.Check(client.GetCurrentPadding(out uint padding), "GetCurrentPadding");
                    uint free = bufferFrames - padding;

                    int availableFrames = carry.Count / format.nChannels;
                    uint framesToWrite = (uint)Math.Min(free, availableFrames);

                    if (framesToWrite == 0)
                    {
                        token.WaitHandle.WaitOne(5);
                        continue;
                    }

                    Wasapi.Check(render.GetBuffer(framesToWrite, out IntPtr buffer), "render GetBuffer");
                    int written = (int)framesToWrite * format.nChannels;
                    WriteSamples(buffer, carry, written, isFloat, format.wBitsPerSample);
                    render.ReleaseBuffer(framesToWrite, 0);

                    carry.RemoveRange(0, written);
                }
            }
            finally
            {
                try { client.Stop(); } catch { }
                Marshal.ReleaseComObject(render);
                Marshal.ReleaseComObject(client);
            }
        }

        private static void WriteSamples(IntPtr buffer, List<short> source, int count, bool isFloat, int bits)
        {
            if (isFloat)
            {
                var floats = new float[count];
                for (int index = 0; index < count; index++) floats[index] = source[index] / 32768f;
                Marshal.Copy(floats, 0, buffer, count);
                return;
            }

            if (bits == 16)
            {
                var shorts = new short[count];
                source.CopyTo(0, shorts, 0, count);
                Marshal.Copy(shorts, 0, buffer, count);
                return;
            }

            // 32-bit integer PCM, the only other shared-mode format seen in
            // practice. Anything else would be silence, so say so rather than
            // writing noise.
            var ints = new int[count];
            for (int index = 0; index < count; index++) ints[index] = source[index] << 16;
            Marshal.Copy(ints, 0, buffer, count);
        }

        public AudioInfo Describe() => new()
        {
            Stream = StreamIds.Name(streamId),
            Active = !_stop.IsCancellationRequested,
            Rate = rate,
            Channels = channels,
            Device = DeviceName,
        };

        public void Stop()
        {
            try { _stop.Cancel(); } catch { }
        }
    }
}

/// <summary>
/// Converts between a device mix format and the 16-bit PCM on the wire, holding
/// its resampling phase across calls.
///
/// The phase has to persist: resampling each buffer independently restarts the
/// interpolation at every packet boundary, which adds a small discontinuity
/// forty or fifty times a second. That is audible as a buzz, and it is the
/// classic way a naive resampler sounds broken while looking correct.
/// </summary>
internal sealed class PcmConverter(
    uint sourceRate, ushort sourceChannels, bool sourceIsFloat, int sourceBits,
    int targetRate, int targetChannels)
{
    private double _phase;
    private float[] _previous = new float[Math.Max(1, targetChannels)];
    private bool _hasPrevious;

    private readonly double _step = (double)sourceRate / Math.Max(1, targetRate);

    public void Append(IntPtr data, int byteCount, bool silent, List<short> into)
    {
        int bytesPerSample = sourceBits / 8;
        int frames = byteCount / Math.Max(1, bytesPerSample * (int)sourceChannels);
        if (frames <= 0) return;

        var mixed = new float[frames * targetChannels];

        if (silent)
        {
            Array.Clear(mixed);
        }
        else
        {
            var raw = new byte[byteCount];
            Marshal.Copy(data, raw, 0, byteCount);
            Mix(raw, frames, bytesPerSample, mixed);
        }

        Resample(mixed, frames, into);
    }

    public void AppendSamples(short[] interleaved, List<short> into)
    {
        int frames = interleaved.Length / Math.Max(1, (int)sourceChannels);
        if (frames <= 0) return;

        var mixed = new float[frames * targetChannels];
        for (int frame = 0; frame < frames; frame++)
        {
            for (int channel = 0; channel < targetChannels; channel++)
            {
                int sourceChannel = Math.Min(channel, sourceChannels - 1);
                float value = interleaved[frame * sourceChannels + sourceChannel] / 32768f;

                // Down-mixing to mono averages rather than dropping a channel:
                // dropping one silences anything hard-panned the other way.
                if (targetChannels == 1 && sourceChannels > 1)
                {
                    float sum = 0;
                    for (int c = 0; c < sourceChannels; c++) sum += interleaved[frame * sourceChannels + c] / 32768f;
                    value = sum / sourceChannels;
                }
                mixed[frame * targetChannels + channel] = value;
            }
        }

        Resample(mixed, frames, into);
    }

    private void Mix(byte[] raw, int frames, int bytesPerSample, float[] mixed)
    {
        for (int frame = 0; frame < frames; frame++)
        {
            for (int channel = 0; channel < targetChannels; channel++)
            {
                float value;
                if (targetChannels == 1 && sourceChannels > 1)
                {
                    float sum = 0;
                    for (int c = 0; c < sourceChannels; c++)
                        sum += ReadSample(raw, (frame * sourceChannels + c) * bytesPerSample, bytesPerSample);
                    value = sum / sourceChannels;
                }
                else
                {
                    int sourceChannel = Math.Min(channel, sourceChannels - 1);
                    value = ReadSample(raw, (frame * sourceChannels + sourceChannel) * bytesPerSample, bytesPerSample);
                }
                mixed[frame * targetChannels + channel] = value;
            }
        }
    }

    private float ReadSample(byte[] raw, int offset, int bytesPerSample)
    {
        if (offset + bytesPerSample > raw.Length) return 0;

        if (sourceIsFloat && bytesPerSample == 4) return BitConverter.ToSingle(raw, offset);
        if (bytesPerSample == 2) return BitConverter.ToInt16(raw, offset) / 32768f;
        if (bytesPerSample == 4) return BitConverter.ToInt32(raw, offset) / 2147483648f;
        if (bytesPerSample == 3)
        {
            int value = raw[offset] | (raw[offset + 1] << 8) | ((sbyte)raw[offset + 2] << 16);
            return value / 8388608f;
        }
        return 0;
    }

    private void Resample(float[] mixed, int frames, List<short> into)
    {
        if (Math.Abs(_step - 1.0) < 0.0001)
        {
            foreach (float sample in mixed) into.Add(Clip(sample));
            return;
        }

        while (_phase < frames)
        {
            int index = (int)_phase;
            double fraction = _phase - index;

            for (int channel = 0; channel < targetChannels; channel++)
            {
                float current = mixed[index * targetChannels + channel];
                float next = index + 1 < frames
                    ? mixed[(index + 1) * targetChannels + channel]
                    : current;

                float previous = _hasPrevious && index == 0 ? _previous[channel] : current;
                float from = index == 0 && _hasPrevious ? previous : current;
                into.Add(Clip((float)(from + (next - from) * fraction)));
            }

            _phase += _step;
        }

        for (int channel = 0; channel < targetChannels; channel++)
            _previous[channel] = mixed[(frames - 1) * targetChannels + channel];
        _hasPrevious = true;

        _phase -= frames;
    }

    private static short Clip(float value) =>
        (short)Math.Clamp((int)Math.Round(value * 32767f), short.MinValue, short.MaxValue);
}
