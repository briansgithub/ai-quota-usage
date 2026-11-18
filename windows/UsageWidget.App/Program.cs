using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using UsageWidget;

namespace UsageWidget.App;

public static class Program
{
    public const string AppUserModelId = "Brian.UsageWidget";
    public const string MutexName = @"Local\UsageWidget.SingleInstance";
    private static Mutex? _mutex;

    [STAThread]
    public static void Main(string[] args)
    {
        var testNotification = args.Any(a => string.Equals(a, "--test-notification", StringComparison.OrdinalIgnoreCase));
        _mutex = new Mutex(true, MutexName, out var created);
        NativeMethods.SetCurrentProcessExplicitAppUserModelID(AppUserModelId);
        if (testNotification)
        {
            if (!created)
            {
                Directory.CreateDirectory(Paths.DataDir);
                File.WriteAllText(Paths.TestNotificationFlag, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds().ToString());
                return;
            }
            ToastService.Show(QuotaThresholdEngine.TestTitle, QuotaThresholdEngine.TestBody);
            return;
        }
        if (!created) return;
        var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
        var host = new AppHost();
        app.Startup += (_, _) => host.Start();
        app.Exit += (_, _) => host.Dispose();
        app.Run();
    }
}

internal static class NativeMethods
{
    [DllImport("shell32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern int SetCurrentProcessExplicitAppUserModelID(string appID);
}
