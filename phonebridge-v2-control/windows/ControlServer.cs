using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;

namespace PhoneBridgeTray;

internal sealed class ControlServer : IDisposable
{
    public const int DiscoveryPort = 8990;
    public const int ControlPort = 8991;

    private readonly Action _startPhoneBridge;
    private readonly Action _stopPhoneBridge;
    private readonly Func<bool> _isRunning;
    private readonly Func<bool> _isConnected;
    private readonly CancellationTokenSource _cts = new();
    private readonly string _tokenFile;

    private UdpClient? _udp;
    private TcpListener? _tcp;
    private string? _pairPin;
    private DateTime _pairExpiresUtc;
    private readonly object _pairLock = new();

    public ControlServer(Action startPhoneBridge, Action stopPhoneBridge, Func<bool> isRunning, Func<bool> isConnected)
    {
        _startPhoneBridge = startPhoneBridge;
        _stopPhoneBridge = stopPhoneBridge;
        _isRunning = isRunning;
        _isConnected = isConnected;

        var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PhoneBridge");
        Directory.CreateDirectory(dir);
        _tokenFile = Path.Combine(dir, "quicktile.token");
    }

    public bool HasPairedTile => File.Exists(_tokenFile) && !string.IsNullOrWhiteSpace(ReadToken());

    public void Start()
    {
        _udp = new UdpClient(new IPEndPoint(IPAddress.Any, DiscoveryPort));
        _udp.EnableBroadcast = true;
        _tcp = new TcpListener(IPAddress.Any, ControlPort);
        _tcp.Start();

        _ = Task.Run(() => DiscoveryLoop(_cts.Token));
        _ = Task.Run(() => ControlLoop(_cts.Token));
    }

    public string BeginPairing()
    {
        lock (_pairLock)
        {
            _pairPin = RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");
            _pairExpiresUtc = DateTime.UtcNow.AddMinutes(2);
            return _pairPin;
        }
    }

    public static string[] LocalIPv4Addresses()
    {
        return NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up &&
                        n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
            .SelectMany(n => n.GetIPProperties().UnicastAddresses)
            .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork)
            .Select(a => a.Address.ToString())
            .Distinct()
            .ToArray();
    }

    private async Task DiscoveryLoop(CancellationToken ct)
    {
        if (_udp is null) return;
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var r = await _udp.ReceiveAsync(ct);
                var text = Encoding.UTF8.GetString(r.Buffer).Trim();
                if (!text.StartsWith("PB_DISCOVER_V2", StringComparison.Ordinal)) continue;

                var reply = $"PB_HERE_V2|{Uri.EscapeDataString(Environment.MachineName)}|{(HasPairedTile ? "paired" : "unpaired")}";
                var bytes = Encoding.UTF8.GetBytes(reply);
                await _udp.SendAsync(bytes, bytes.Length, r.RemoteEndPoint);
            }
            catch (OperationCanceledException) { break; }
            catch { await Task.Delay(250, ct).ContinueWith(_ => { }, TaskScheduler.Default); }
        }
    }

    private async Task ControlLoop(CancellationToken ct)
    {
        if (_tcp is null) return;
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var client = await _tcp.AcceptTcpClientAsync(ct);
                _ = Task.Run(() => HandleClient(client, ct), ct);
            }
            catch (OperationCanceledException) { break; }
            catch { await Task.Delay(250, ct).ContinueWith(_ => { }, TaskScheduler.Default); }
        }
    }

    private async Task HandleClient(TcpClient client, CancellationToken ct)
    {
        using (client)
        {
            client.ReceiveTimeout = 4000;
            client.SendTimeout = 4000;
            using var stream = client.GetStream();
            using var reader = new StreamReader(stream, Encoding.UTF8, false, 1024, leaveOpen: true);
            using var writer = new StreamWriter(stream, new UTF8Encoding(false), 1024, leaveOpen: true) { AutoFlush = true };

            string? line;
            try { line = await reader.ReadLineAsync(ct); }
            catch { return; }

            if (string.IsNullOrWhiteSpace(line)) return;
            var parts = line.Split('|');
            var cmd = parts[0].Trim().ToUpperInvariant();

            if (cmd == "PAIR")
            {
                var supplied = parts.Length > 1 ? parts[1].Trim() : "";
                bool ok;
                lock (_pairLock)
                {
                    ok = !string.IsNullOrWhiteSpace(_pairPin) &&
                         DateTime.UtcNow <= _pairExpiresUtc &&
                         CryptographicOperations.FixedTimeEquals(
                             Encoding.UTF8.GetBytes(supplied),
                             Encoding.UTF8.GetBytes(_pairPin!));
                    if (ok)
                    {
                        _pairPin = null;
                        _pairExpiresUtc = DateTime.MinValue;
                    }
                }

                if (!ok)
                {
                    await writer.WriteLineAsync("ERR|PIN");
                    return;
                }

                var token = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
                File.WriteAllText(_tokenFile, token, Encoding.ASCII);
                await writer.WriteLineAsync("OK|" + token);
                return;
            }

            var suppliedToken = parts.Length > 1 ? parts[1].Trim() : "";
            if (!TokenMatches(suppliedToken))
            {
                await writer.WriteLineAsync("ERR|AUTH");
                return;
            }

            switch (cmd)
            {
                case "STATUS":
                    await writer.WriteLineAsync($"STATUS|{(_isConnected() ? "1" : "0")}|{(_isRunning() ? "1" : "0")}");
                    break;
                case "ON":
                    _startPhoneBridge();
                    await writer.WriteLineAsync("OK|ON");
                    break;
                case "OFF":
                    _stopPhoneBridge();
                    await writer.WriteLineAsync("OK|OFF");
                    break;
                default:
                    await writer.WriteLineAsync("ERR|CMD");
                    break;
            }
        }
    }

    private bool TokenMatches(string supplied)
    {
        var saved = ReadToken();
        if (string.IsNullOrWhiteSpace(saved) || string.IsNullOrWhiteSpace(supplied)) return false;
        try
        {
            return CryptographicOperations.FixedTimeEquals(
                Encoding.ASCII.GetBytes(saved),
                Encoding.ASCII.GetBytes(supplied));
        }
        catch { return false; }
    }

    private string? ReadToken()
    {
        try { return File.Exists(_tokenFile) ? File.ReadAllText(_tokenFile, Encoding.ASCII).Trim() : null; }
        catch { return null; }
    }

    public void Dispose()
    {
        _cts.Cancel();
        try { _udp?.Close(); } catch { }
        try { _tcp?.Stop(); } catch { }
        _udp?.Dispose();
        _cts.Dispose();
    }
}
