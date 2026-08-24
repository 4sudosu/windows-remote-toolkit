namespace RuntimeBroker;

/// <summary>Emergency stop state shared by the service and interactive child processes.</summary>
public static class EmergencyStop
{
    private static readonly object Sync = new();

    public static string WorkDir => PowerShellRunner.CaptureWorkDir();
    public static string StopFile => Path.Combine(WorkDir, "stop-all.flag");
    public static string ActiveFile => Path.Combine(WorkDir, "control-active.flag");
    public static bool IsStopRequested => File.Exists(StopFile);
    public static bool IsControlActive => File.Exists(ActiveFile);

    public static void BeginControl()
    {
        lock (Sync)
        {
            try { File.Delete(StopFile); } catch { }
            try { File.WriteAllText(ActiveFile, DateTime.UtcNow.ToString("O")); } catch { }
        }
    }

    public static void EndControl()
    {
        // Leave the listener armed for the next action. STOP ALL explicitly
        // clears this marker, and the next action recreates it.
    }

    public static void Request()
    {
        lock (Sync)
        {
            try { Directory.CreateDirectory(WorkDir); File.WriteAllText(StopFile, DateTime.UtcNow.ToString("O")); } catch { }
            try { File.Delete(ActiveFile); } catch { }
        }
    }
}
