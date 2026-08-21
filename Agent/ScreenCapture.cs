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
    public static int CaptureToFile(string outFile) => CaptureToFile(outFile, 0, 0);

    public static int CaptureToFile(string outFile, int maxWidth, int jpegQuality)
    {
        try
        {
            var png = CaptureBytes(maxWidth, jpegQuality);
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

    public static byte[]? CaptureBytes() => CaptureBytes(0, 0);

    /// <summary>
    /// Captures the virtual screen. Optionally downscales so the long side is
    /// <= maxWidth and encodes as JPEG with the given quality (0 = PNG lossless).
    /// Used by the live-stream path where small fast frames matter more than
    /// pixel-perfect quality.
    /// </summary>
    public static byte[]? CaptureBytes(int maxWidth, int jpegQuality)
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

            if (maxWidth > 0 && Math.Max(bmp.Width, bmp.Height) > maxWidth)
            {
                var scale = (double)maxWidth / Math.Max(bmp.Width, bmp.Height);
                var nw = Math.Max(1, (int)Math.Round(bmp.Width * scale));
                var nh = Math.Max(1, (int)Math.Round(bmp.Height * scale));
                var scaled = new Bitmap(nw, nh);
                using (var g2 = Graphics.FromImage(scaled))
                {
                    g2.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.HighQualityBicubic;
                    g2.DrawImage(bmp, 0, 0, nw, nh);
                }
                bmp.Dispose();
                using (scaled)
                {
                    return Encode(scaled, jpegQuality);
                }
            }
            return Encode(bmp, jpegQuality);
        }
        catch
        {
            return null;
        }
    }

    private static byte[] Encode(Bitmap bmp, int jpegQuality)
    {
        using var ms = new MemoryStream();
        if (jpegQuality > 0)
        {
            var encoder = GetJpegEncoder();
            var ep = new EncoderParameters(1);
            ep.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, (long)Math.Clamp(jpegQuality, 30, 95));
            bmp.Save(ms, encoder, ep);
        }
        else
        {
            bmp.Save(ms, ImageFormat.Png);
        }
        return ms.ToArray();
    }

    private static ImageCodecInfo? _jpegEncoder;
    private static ImageCodecInfo GetJpegEncoder()
    {
        if (_jpegEncoder != null) return _jpegEncoder;
        foreach (var enc in ImageCodecInfo.GetImageEncoders())
            if (enc.FormatID == ImageFormat.Jpeg.Guid) { _jpegEncoder = enc; break; }
        return _jpegEncoder!;
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