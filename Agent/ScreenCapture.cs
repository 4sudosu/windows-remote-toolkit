using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

namespace RuntimeBroker;

/// <summary>
/// Full-screen capture that runs inside this process (the agent EXE), launched
/// by the scheduled task in the interactive user's session. DPI-aware so the
/// bitmap matches the PHYSICAL resolution (SystemInformation.VirtualScreen
/// returns scaled/logical pixels when the process is not DPI-aware, cropping
/// the image on scaled displays).
/// </summary>
internal static class ScreenCapture
{
    public static int CaptureToFile(string outFile)
    {
        try
        {
            var png = CaptureBytes();
            if (png == null) return 2;
            File.WriteAllText(outFile, Convert.ToBase64String(png));
            return 0;
        }
        catch (Exception ex)
        {
            try { File.WriteAllText(outFile, "ERR: " + ex.Message); } catch { }
            return 1;
        }
    }

    public static byte[]? CaptureBytes()
    {
        try
        {
            var rect = PhysicalVirtualScreen();
            if (rect.Width <= 0 || rect.Height <= 0) return null;
            using var bmp = new Bitmap(rect.Width, rect.Height);
            using (var g = Graphics.FromImage(bmp))
            {
                g.CopyFromScreen(rect.Left, rect.Top, 0, 0, rect.Size);
            }
            using var ms = new MemoryStream();
            bmp.Save(ms, ImageFormat.Png);
            return ms.ToArray();
        }
        catch
        {
            return null;
        }
    }

    private static Rectangle PhysicalVirtualScreen()
    {
        try { SetProcessDPIAware(); } catch { }
        return new Rectangle(
            GetSystemMetrics(SM_XVIRTUALSCREEN),
            GetSystemMetrics(SM_YVIRTUALSCREEN),
            GetSystemMetrics(SM_CXVIRTUALSCREEN),
            GetSystemMetrics(SM_CYVIRTUALSCREEN));
    }

    private const int SM_XVIRTUALSCREEN = 76;
    private const int SM_YVIRTUALSCREEN = 77;
    private const int SM_CXVIRTUALSCREEN = 78;
    private const int SM_CYVIRTUALSCREEN = 79;

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int nIndex);

    [DllImport("user32.dll")]
    private static extern bool SetProcessDPIAware();
}