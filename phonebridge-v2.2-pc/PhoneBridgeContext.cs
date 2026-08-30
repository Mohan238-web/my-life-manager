using Microsoft.Win32;
using System.Diagnostics;
using System.Drawing;
using System.IO.Compression;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;

namespace PhoneBridgeV22;

internal sealed class PhoneBridgeContext : ApplicationContext
{
    private const int StreamPort = 8989;
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string RunValue = "PhoneBridge";
    private static readonly byte[] PayloadMarker = new byte[]
        { 0x50,0x42,0x32,0x32,0x43,0x4F,0x52,0x45,0x50,0x41,0x59,0x4C,0x4F,0x41,0x44,0x21 }; // PB22COREPAYLOAD!
    private const string CoreSha256 = "de76ba7529b1b9d169c740b3391eebbdd57754e89c6473f1f8906d866f7ecd2a";

    private readonly string _installDir;
    private readonly string _installedExe;
    private readonly string _coreDir;
    private readonly string _coreExe;
    private readonly NotifyIcon _tray;
    private readonly ToolStripMenuItem _status;
    private readonly ToolStripMenuItem _startup;
    private readonly System.Windows.Forms.Timer _timer;
    private Process? _core;
    private bool _showCore;
    private bool _exiting;

    public PhoneBridgeContext(string[] args)
    {
        _installDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PhoneBridge");
        _installedExe = Path.Combine(_installDir, "PhoneBridge.exe");
        _coreDir = Path.Combine(_installDir, "Core");
        _coreExe = Path.Combine(_coreDir, "PhoneBridge-Core-v1.9.exe");

        Directory.CreateDirectory(_installDir);

        if (!IsInstalledCopy())
        {
            InstallSelfAndRestart();
            _tray = null!;
            _status = null!;
            _startup = null!;
            _timer = null!;
            return;
        }

        RemoveLegacyStartupEntries();
        SetStartup(true);
        EnsureCoreExtracted();

        _status = new ToolStripMenuItem("Status: starting…") { Enabled = false };
        _startup = new ToolStripMenuItem("Start with Windows") { CheckOnClick = true, Checked = IsStartupEnabled() };
        _startup.CheckedChanged += (_, _) => SetStartup(_startup.Checked);

        var menu = new ContextMenuStrip();
        menu.Items.Add(_status);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Open PhoneBridge", null, (_, _) => OpenCore());
        menu.Items.Add("Restart PhoneBridge engine", null, (_, _) => RestartCore());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(_startup);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Exit PhoneBridge", null, (_, _) => ExitAll());

        Icon appIcon;
        try { appIcon = Icon.ExtractAssociatedIcon(Environment.ProcessPath!) ?? SystemIcons.Application; }
        catch { appIcon = SystemIcons.Application; }

        _tray = new NotifyIcon
        {
            Visible = true,
            Text = "PhoneBridge",
            Icon = appIcon,
            ContextMenuStrip = menu
        };
        _tray.DoubleClick += (_, _) => OpenCore();

        _timer = new System.Windows.Forms.Timer { Interval = 1500 };
        _timer.Tick += (_, _) => Tick();
        _timer.Start();

        StartCore(showWindow: false);
        _tray.ShowBalloonTip(1600, "PhoneBridge", "Ready in the Windows hidden-icons area.", ToolTipIcon.Info);
    }

    private bool IsInstalledCopy()
    {
        try
        {
            return string.Equals(
                Path.GetFullPath(Environment.ProcessPath!),
                Path.GetFullPath(_installedExe),
                StringComparison.OrdinalIgnoreCase);
        }
        catch { return false; }
    }

    private void InstallSelfAndRestart()
    {
        try
        {
            Directory.CreateDirectory(_installDir);
            var current = Environment.ProcessPath!;
            File.Copy(current, _installedExe, true);
            Process.Start(new ProcessStartInfo
            {
                FileName = _installedExe,
                WorkingDirectory = _installDir,
                UseShellExecute = true
            });
        }
        catch (Exception ex)
        {
            MessageBox.Show("PhoneBridge could not install itself.\n\n" + ex.Message,
                "PhoneBridge", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        ExitThread();
    }

    private void EnsureCoreExtracted()
    {
        Directory.CreateDirectory(_coreDir);
        if (File.Exists(_coreExe) && HashFile(_coreExe).Equals(CoreSha256, StringComparison.OrdinalIgnoreCase))
            return;

        var self = Environment.ProcessPath!;
        using var fs = File.OpenRead(self);
        if (fs.Length < 24) throw new InvalidDataException("PhoneBridge core payload is missing.");

        fs.Seek(-8, SeekOrigin.End);
        Span<byte> lenBytes = stackalloc byte[8];
        fs.ReadExactly(lenBytes);
        long payloadLength = BitConverter.ToInt64(lenBytes);

        fs.Seek(-(8 + PayloadMarker.Length), SeekOrigin.End);
        byte[] marker = new byte[PayloadMarker.Length];
        fs.ReadExactly(marker);
        if (!marker.SequenceEqual(PayloadMarker) || payloadLength <= 0 || payloadLength > fs.Length)
            throw new InvalidDataException("PhoneBridge core payload is invalid.");

        long payloadOffset = fs.Length - 8 - PayloadMarker.Length - payloadLength;
        fs.Seek(payloadOffset, SeekOrigin.Begin);
        using var limited = new LimitedReadStream(fs, payloadLength);
        using var gz = new GZipStream(limited, CompressionMode.Decompress, leaveOpen: true);
        using var output = File.Create(_coreExe);
        gz.CopyTo(output);
        output.Flush(true);

        if (!HashFile(_coreExe).Equals(CoreSha256, StringComparison.OrdinalIgnoreCase))
        {
            File.Delete(_coreExe);
            throw new InvalidDataException("PhoneBridge core verification failed.");
        }
    }

    private void StartCore(bool showWindow)
    {
        _showCore = showWindow;
        if (_exiting) return;

        if (_core is { HasExited: false })
        {
            if (showWindow) ShowProcessWindows(_core.Id);
            return;
        }

        try
        {
            EnsureCoreExtracted();
            _core?.Dispose();
            _core = Process.Start(new ProcessStartInfo
            {
                FileName = _coreExe,
                WorkingDirectory = _coreDir,
                UseShellExecute = true,
                WindowStyle = showWindow ? ProcessWindowStyle.Normal : ProcessWindowStyle.Minimized
            });
            if (_core is not null && !showWindow)
            {
                int id = _core.Id;
                _ = Task.Run(async () =>
                {
                    await Task.Delay(1000);
                    if (!_showCore && !_exiting) HideProcessWindows(id);
                });
            }
        }
        catch (Exception ex)
        {
            _tray?.ShowBalloonTip(3500, "PhoneBridge", "Engine start failed: " + ex.Message, ToolTipIcon.Error);
        }
    }

    private void OpenCore()
    {
        _showCore = true;
        StartCore(showWindow: true);
        if (_core is { HasExited: false })
        {
            try
            {
                _core.Refresh();
                if (_core.MainWindowHandle != IntPtr.Zero)
                {
                    ShowWindow(_core.MainWindowHandle, SW_SHOW);
                    ShowWindow(_core.MainWindowHandle, SW_RESTORE);
                    SetForegroundWindow(_core.MainWindowHandle);
                }
                else ShowProcessWindows(_core.Id);
            }
            catch { }
        }
    }

    private void RestartCore()
    {
        StopCore();
        _showCore = false;
        StartCore(showWindow: false);
    }

    private void StopCore()
    {
        try
        {
            if (_core is { HasExited: false })
            {
                _core.Kill(entireProcessTree: true);
                _core.WaitForExit(2500);
            }
        }
        catch { }
        finally
        {
            _core?.Dispose();
            _core = null;
        }
    }

    private void Tick()
    {
        if (_exiting) return;

        if (_core is null || _core.HasExited)
        {
            // Keep the proven v1.9 engine alive; do not restart it merely because
            // the visible window was closed or another app is in use.
            StartCore(showWindow: false);
        }

        bool connected = IsPhoneConnected();
        _status.Text = connected ? "Status: phone connected" : "Status: waiting for phone";
        _tray.Text = connected ? "PhoneBridge - Connected" : "PhoneBridge - Waiting";

        if (!_showCore && _core is { HasExited: false })
            HideProcessWindows(_core.Id);
    }

    private static bool IsPhoneConnected()
    {
        try
        {
            return IPGlobalProperties.GetIPGlobalProperties()
                .GetActiveTcpConnections()
                .Any(c => c.LocalEndPoint.Port == StreamPort &&
                          c.State == TcpState.Established);
        }
        catch { return false; }
    }

    private void SetStartup(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey, writable: true);
            if (enabled) key.SetValue(RunValue, $"\"{_installedExe}\" --startup");
            else key.DeleteValue(RunValue, false);
        }
        catch { }
    }

    private bool IsStartupEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            var v = Convert.ToString(key?.GetValue(RunValue)) ?? "";
            return v.Contains(_installedExe, StringComparison.OrdinalIgnoreCase);
        }
        catch { return false; }
    }

    private void RemoveLegacyStartupEntries()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: true);
            if (key is null) return;
            foreach (var name in key.GetValueNames())
            {
                if (name.Equals(RunValue, StringComparison.OrdinalIgnoreCase)) continue;
                var value = Convert.ToString(key.GetValue(name)) ?? "";
                if (value.Contains("PhoneBridge", StringComparison.OrdinalIgnoreCase))
                    key.DeleteValue(name, false);
            }
        }
        catch { }
    }

    private void ExitAll()
    {
        _exiting = true;
        _timer.Stop();
        StopCore();
        _tray.Visible = false;
        _tray.Dispose();
        ExitThread();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _timer?.Dispose();
            _tray?.Dispose();
            _core?.Dispose();
        }
        base.Dispose(disposing);
    }

    private static string HashFile(string path)
    {
        using var fs = File.OpenRead(path);
        return Convert.ToHexString(SHA256.HashData(fs)).ToLowerInvariant();
    }

    private static void HideProcessWindows(int processId) =>
        EnumWindows((hWnd, _) =>
        {
            GetWindowThreadProcessId(hWnd, out var pid);
            if (pid == (uint)processId && IsWindowVisible(hWnd)) ShowWindow(hWnd, SW_HIDE);
            return true;
        }, IntPtr.Zero);

    private static void ShowProcessWindows(int processId) =>
        EnumWindows((hWnd, _) =>
        {
            GetWindowThreadProcessId(hWnd, out var pid);
            if (pid == (uint)processId)
            {
                ShowWindow(hWnd, SW_SHOW);
                ShowWindow(hWnd, SW_RESTORE);
                SetForegroundWindow(hWnd);
            }
            return true;
        }, IntPtr.Zero);

    private sealed class LimitedReadStream : Stream
    {
        private readonly Stream _base;
        private long _remaining;
        public LimitedReadStream(Stream baseStream, long remaining) { _base = baseStream; _remaining = remaining; }
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => _remaining;
        public override long Position { get => 0; set => throw new NotSupportedException(); }
        public override int Read(byte[] buffer, int offset, int count)
        {
            if (_remaining <= 0) return 0;
            int n = _base.Read(buffer, offset, (int)Math.Min(count, _remaining));
            _remaining -= n;
            return n;
        }
        public override int Read(Span<byte> buffer)
        {
            if (_remaining <= 0) return 0;
            int n = _base.Read(buffer[..(int)Math.Min(buffer.Length, _remaining)]);
            _remaining -= n;
            return n;
        }
        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }

    private const int SW_HIDE = 0;
    private const int SW_SHOW = 5;
    private const int SW_RESTORE = 9;
    private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] private static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
    [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr hWnd);
}
