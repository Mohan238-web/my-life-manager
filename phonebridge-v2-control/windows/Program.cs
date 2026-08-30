using System.Threading;

namespace PhoneBridgeTray;

internal static class Program
{
    [STAThread]
    static void Main()
    {
        using var mutex = new Mutex(true, @"Local\PhoneBridge-Tray-v2-SingleInstance", out var created);
        if (!created) return;

        ApplicationConfiguration.Initialize();
        Application.Run(new TrayApplicationContext());
    }
}
