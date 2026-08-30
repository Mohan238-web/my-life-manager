using System.Threading;

namespace PhoneBridgeV22;

internal static class Program
{
    [STAThread]
    static void Main(string[] args)
    {
        using var mutex = new Mutex(true, @"Local\PhoneBridge-v2.2-SingleInstance", out var created);
        if (!created) return;

        ApplicationConfiguration.Initialize();
        Application.Run(new PhoneBridgeContext(args));
    }
}
