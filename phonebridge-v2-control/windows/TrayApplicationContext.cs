using Microsoft.Win32;
using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;

namespace PhoneBridgeTray;

internal sealed class TrayApplicationContext : ApplicationContext
{
    private const int PhoneBridgePort = 8989;
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string TrayRunValue = "PhoneBridgeTray";

    private readonly NotifyIcon _tray;
    private readonly ToolStripMenuItem _statusItem;
    private readonly ToolStripMenuItem _startupItem;
    private readonly System.Windows.Forms.Timer _timer;
    private readonly ControlServer _control;
    private readonly string _phoneBridgeExe;
    private bool _allowVisibleWindow;

    public TrayApplicationContext()
    {
        _phoneBridgeExe = Path.Combine(AppContext.BaseDirectory, "PhoneBridge-v1.9.exe");

        TakeOverWindowsStartup();

        _statusItem = new ToolStripMenuItem("Status: starting...") { Enabled = false };
        _startupItem = new ToolStripMenuItem("Start with Windows") { CheckOnClick = true, Checked = IsTrayAutostartEnabled() };
        _startupItem.CheckedChanged += (_, _) => SetTrayAutostart(_startupItem.Checked);

        var menu = new ContextMenuStrip();
        menu.Items.Add(_statusItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Open PhoneBridge", null, (_, _) => OpenPhoneBridge());
        menu.Items.Add("Start / Connect", null, (_, _) => StartPhoneBridge());
        menu.Items.Add("Disconnect / Stop", null, (_, _) => StopPhoneBridge());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Pair mobile Quick Toggle", null, (_, _) => ShowPairing());
        menu.Items.Add(_startupItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Exit PhoneBridge", null, (_, _) => ExitEverything());

        _tray = new NotifyIcon
        {
            Text = "PhoneBridge",
            Icon = SystemIcons.Application,
            ContextMenuStrip = menu,
            Visible = true
        };
        _tray.DoubleClick += (_, _) => OpenPhoneBridge();

        _control = new ControlServer(
            startPhoneBridge: () => BeginInvoke(StartPhoneBridge),
            stopPhoneBridge: () => BeginInvoke(StopPhoneBridge),
            isRunning: IsPhoneBridgeRunning,
            isConnected: IsPhoneConnected);

        try { _control.Start(); }
        catch (Exception ex)
        {
            MessageBox.Show(
                "PhoneBridge tray could not open the local mobile-control ports 8990/8991.\n\n" +
                "Allow PhoneBridge-Tray through Windows Firewall on Private networks, then restart it.\n\n" + ex.Message,
                "PhoneBridge",
                MessageBoxButtons.OK,
                MessageBoxIcon.Warning);
        }

        _timer = new System.Windows.Forms.Timer { Interval = 1500 };
        _timer.Tick += (_, _) => RefreshStatus();
        _timer.Start();

        StartPhoneBridge();
        _tray.ShowBalloonTip(1800, "PhoneBridge", "Running in the Windows hidden-icons area.", ToolTipIcon.Info);
    }

    private void BeginInvoke(Action action)
    {
        if (SynchronizationContext.Current is not null)
        {
            action();
            return;
        }
        var invoker = new System.Windows.Forms.Control();
        invoker.CreateControl();
        invoker.BeginInvoke(action);
    }

    private void TakeOverWindowsStartup()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath, writable: true);
            if (key is not null)
            {
                foreach (var name in key.GetValueNames())
                {
                    var value = Convert.ToString(key.GetValue(name)) ?? "";
                    if (name.Equals("PhoneBridge", StringComparison.OrdinalIgnoreCase) &&
                        value.Contains("PhoneBridge", StringComparison.OrdinalIgnoreCase))
                    {
                        key.DeleteValue(name, false);
                    }
                }
            }
            SetTrayAutostart(true);
        }
        catch { }
    }

    private void SetTrayAutostart(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath, writable: true)
                           ?? Registry.CurrentUser.CreateSubKey(RunKeyPath, writable: true);
            if (enabled)
                key.SetValue(TrayRunValue, $"\"{Environment.ProcessPath}\" --startup");
            else
                key.DeleteValue(TrayRunValue, false);
        }
        catch
        {
            if (enabled)
                _tray?.ShowBalloonTip(2000, "PhoneBridge", "Windows startup setting could not be changed.", ToolTipIcon.Warning);
        }
    }

    private bool IsTrayAutostartEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath);
            var value = Convert.ToString(key?.GetValue(TrayRunValue)) ?? "";
            return value.Contains(Environment.ProcessPath ?? "", StringComparison.OrdinalIgnoreCase);
        }
        catch { return false; }
    }

    private IEnumerable<Process> PhoneBridgeProcesses()
    {
        var name = Path.GetFileNameWithoutExtension(_phoneBridgeExe);
        Process[] processes;
        try { processes = Process.GetProcessesByName(name); }
        catch { yield break; }

        foreach (var p in processes)
            yield return p;
    }

    private bool IsPhoneBridgeRunning() => PhoneBridgeProcesses().Any(p => !p.HasExited);

    private bool IsPhoneConnected()
    {
        try
        {
            return IPGlobalProperties.GetIPGlobalProperties()
                .GetActiveTcpConnections()
                .Any(c => c.LocalEndPoint.Port == PhoneBridgePort &&
                          c.State == TcpState.Established);
        }
        catch { return false; }
    }

    private void StartPhoneBridge()
    {
        _allowVisibleWindow = false;
        if (!File.Exists(_phoneBridgeExe))
        {
            _tray.ShowBalloonTip(
                3500,
                "PhoneBridge file missing",
                "Keep PhoneBridge-Tray.exe in the same folder as PhoneBridge-v1.9.exe.",
                ToolTipIcon.Error);
            return;
        }

        if (!IsPhoneBridgeRunning())
        {
            try
            {
                var p = Process.Start(new ProcessStartInfo
                {
                    FileName = _phoneBridgeExe,
                    WorkingDirectory = AppContext.BaseDirectory,
                    UseShellExecute = true,
                    WindowStyle = ProcessWindowStyle.Minimized
                });

                if (p is not null)
                    _ = Task.Run(async () =>
                    {
                        await Task.Delay(900);
                        HideProcessWindows(p.Id);
                    });
            }
            catch (Exception ex)
            {
                _tray.ShowBalloonTip(3000, "PhoneBridge", "Could not start PhoneBridge: " + ex.Message, ToolTipIcon.Error);
            }
        }
        else
        {
            foreach (var p in PhoneBridgeProcesses()) HideProcessWindows(p.Id);
        }
        RefreshStatus();
    }

    private void StopPhoneBridge()
    {
        _allowVisibleWindow = false;
        foreach (var p in PhoneBridgeProcesses())
        {
            try
            {
                if (!p.HasExited) p.Kill(entireProcessTree: true);
            }
            catch { }
            finally { p.Dispose(); }
        }
        RefreshStatus();
    }

    private void OpenPhoneBridge()
    {
        _allowVisibleWindow = true;
        if (!IsPhoneBridgeRunning()) StartPhoneBridge();
        _allowVisibleWindow = true;

        foreach (var p in PhoneBridgeProcesses())
        {
            try
            {
                p.Refresh();
                if (p.MainWindowHandle != IntPtr.Zero)
                {
                    ShowWindow(p.MainWindowHandle, SW_SHOW);
                    ShowWindow(p.MainWindowHandle, SW_RESTORE);
                    SetForegroundWindow(p.MainWindowHandle);
                }
                else
                {
                    ShowProcessWindows(p.Id);
                }
            }
            catch { }
        }
    }

    private void RefreshStatus()
    {
        var running = IsPhoneBridgeRunning();
        var connected = running && IsPhoneConnected();

        _statusItem.Text = connected
            ? "Status: Phone connected"
            : running
                ? "Status: waiting for phone"
                : "Status: stopped";

        _tray.Text = connected
            ? "PhoneBridge - Connected"
            : running
                ? "PhoneBridge - Waiting"
                : "PhoneBridge - Off";

        if (running && !_allowVisibleWindow)
        {
            foreach (var p in PhoneBridgeProcesses()) HideProcessWindows(p.Id);
        }
    }

    private void ShowPairing()
    {
        var pin = _control.BeginPairing();
        var ips = ControlServer.LocalIPv4Addresses();
        var ipText = ips.Length == 0 ? "No local IPv4 address found" : string.Join(", ", ips);

        MessageBox.Show(
            $"On the phone add the PhoneBridge Quick Toggle, tap it, then pair once.\n\n" +
            $"PC: {Environment.MachineName}\nIP: {ipText}\nPairing PIN: {pin}\n\n" +
            "The PIN expires in 2 minutes. After pairing, the Quick Toggle remembers this PC.",
            "Pair PhoneBridge Quick Toggle",
            MessageBoxButtons.OK,
            MessageBoxIcon.Information);
    }

    private void ExitEverything()
    {
        _timer.Stop();
        StopPhoneBridge();
        _control.Dispose();
        _tray.Visible = false;
        _tray.Dispose();
        ExitThread();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _timer?.Dispose();
            _control?.Dispose();
            _tray?.Dispose();
        }
        base.Dispose(disposing);
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
            }
            return true;
        }, IntPtr.Zero);

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
