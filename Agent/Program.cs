using System.ServiceProcess;

namespace RuntimeBroker;

internal static class Program
{
    [STAThread]
    static void Main()
    {
        var args = Environment.GetCommandLineArgs();

        // One-shot interactive child modes. Launched by the scheduled task in
        // the user's interactive session. No PowerShell involved — fast, and the
        // process is this EXE itself.
        for (int i = 1; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--capture" when i + 1 < args.Length:
                    if (args.Length > i + 3 && int.TryParse(args[i + 2], out var mw) && int.TryParse(args[i + 3], out var q))
                        Environment.ExitCode = ScreenCapture.CaptureToFile(args[i + 1], mw, q);
                    else
                        Environment.ExitCode = ScreenCapture.CaptureToFile(args[i + 1]);
                    return;
                case "--input" when i + 2 < args.Length:
                    Environment.ExitCode = InteractiveActions.SendText(args[i + 1], args[i + 2]);
                    return;
                case "--mouse" when i + 2 < args.Length:
                    Environment.ExitCode = InteractiveActions.SendMouse(args[i + 1], args[i + 2]);
                    return;
                case "--para" when i + 2 < args.Length:
                    Environment.ExitCode = InteractiveActions.SendParagraph(args[i + 1], args[i + 2]);
                    return;
                case "--rotate" when i + 2 < args.Length:
                    Environment.ExitCode = InteractiveActions.RotateScreen(args[i + 1], args[i + 2]);
                    return;
                case "--camera" when i + 1 < args.Length:
                    Environment.ExitCode = InteractiveActions.CapturePhoto(args[i + 1]);
                    return;
                case "--video" when i + 2 < args.Length:
                    Environment.ExitCode = InteractiveActions.RecordVideo(args[i + 1], args[i + 2]);
                    return;
                case "--mic" when i + 2 < args.Length:
                    Environment.ExitCode = InteractiveActions.RecordAudio(args[i + 1], args[i + 2]);
                    return;
                case "--playaudio" when i + 2 < args.Length:
                    Environment.ExitCode = InteractiveActions.PlayAudio(args[i + 1], args[i + 2]);
                    return;
            }
        }

        // Windows Service mode: run 24x7 as LocalSystem (installed by the admin
        // installer).
        for (int i = 0; i < args.Length; i++)
        {
            if (args[i] == "--service")
            {
                if (Environment.UserInteractive)
                    RunServiceInConsole();
                else
                    ServiceBase.Run(new ServiceBase[] { new AgentService() });
                return;
            }
        }

        // No args (e.g. launched from the Start Menu shortcut): nothing for the
        // user to interact with — the service owns the connection. Exit quietly.
    }

    private static void RunServiceInConsole()
    {
        Console.WriteLine($"RuntimeBroker (console) starting — v{AgentVersionInfo.Version}");
        var service = new AgentService();
        service.StartManually();
        Console.WriteLine("Running. Press Ctrl+C to stop.");
        var done = new ManualResetEvent(false);
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; done.Set(); };
        done.WaitOne();
        service.StopManually();
    }
}