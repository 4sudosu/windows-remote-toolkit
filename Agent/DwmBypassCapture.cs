// RuntimeBroker V2 integration (ported from WinSysMonitor V2).
// ─────────────────────────────────────────────────────────────────────────
// 1. ScreenCapture hook — after the GDI CopyFromScreen into `bmp`, add:
//      try { DwmBypassCapture.OverlayProtectedWindows(bmp, rect); } catch { }
//    (WDA_MONITOR / WDA_EXCLUDEFROMCAPTURE windows render black in GDI;
//     this overlays their DWM shared-surface pixels back on top.)
//
// 2. --diag mode — in Program.Main, before service startup, add:
//      for (int i = 1; i < args.Length; i++)
//      {
//          if (args[i] == "--diag")
//          {
//              int n = DwmBypassCapture.CountProtectedWindows();
//              Console.WriteLine($"protected_windows={n}");
//              foreach (var line in DwmBypassCapture.DiagProtectedWindows())
//                  Console.WriteLine($"  {line}");
//              var png = ScreenCapture.CaptureBytes();
//              Console.WriteLine($"capture_bytes={(png == null ? 0 : png.Length)}");
//              Console.WriteLine("dwm_bypass=active");
//              return;
//          }
//      }
//    Run `RuntimeBroker.exe --diag` on a target to verify protected capture.
//
// 3. Custom service name at install time — handled by the installer
//    (Inno Setup Service page + /SERVICENAME= for silent installs;
//     old service stopped/deleted on rename; config ACL-locked to
//     SYSTEM/Administrators). See the V2 installer.iss pattern.

using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

namespace RuntimeBroker;

/// <summary>
/// User-mode bypass for SetWindowDisplayAffinity (WDA_MONITOR / WDA_EXCLUDEFROMCAPTURE).
///
/// Background (from the repos you sent):
/// - WindowCaptureHider + DWMShield are *hiders*: they SET WDA_EXCLUDEFROMCAPTURE
///   (via SetWindowDisplayAffinity or via kernel GreProtectSpriteContent). They are
///   the opposite direction and cannot be used to capture.
/// - TopSoftdeveloper/Bypass-SetWindowDisplayAffinity is a *kernel driver* bypass.
///   It needs test-signing mode, triggers PatchGuard risks, breaks on Win11 24H2,
///   needs an EV cert for production, and NVIDIA still omits protected windows
///   from the DWM screenshot path. Not suitable for a silent 24x7 agent.
/// - TopSoftdeveloper/SetWindowDisplayAffinity-Bypass (user-mode, no injection)
///   builds on wongfei/wda_monitor_trick: use the undocumented
///   user32!DwmGetDxSharedSurface to get the DWM-composited DirectX shared surface
///   for a window, then open it with D3D11 OpenSharedResource and read pixels.
///   This works from user mode, needs no driver, no injection, no hooking.
///
/// What this file does:
///  1. Takes the normal GDI full-screen bitmap (which has black boxes where
///     protected windows are).
///  2. Enumerates top-level windows with GetWindowDisplayAffinity != WDA_NONE.
///  3. Captures each one via DwmGetDxSharedSurface + D3D11 (client area only —
///     the DWM shared surface has no title bar / non-client area).
///  4. Composites them back onto the full-screen bitmap at their screen position.
///
/// Limitations (same as the reference PoCs):
///  - No title bar (non-client area not in the shared surface).
///  - Minimized windows cannot be captured (DWM stops rendering them).
///  - DX12 exclusive-fullscreen / HDCP / HW-protected content may still be black.
///  - Some NVIDIA + PlayReady paths omit protected windows even from DWM.
///  - On failure we silently keep the GDI pixels (black box) — best effort.
/// </summary>
internal static class DwmBypassCapture
{
    private const uint WDA_NONE = 0;

    private static readonly object _d3dLock = new();
    private static IntPtr _device;
    private static IntPtr _context;
    private static readonly List<(IntPtr dev, IntPtr ctx, int adapter)> _adapterDevices = new();
    private static bool _d3dTried;

    /// <summary>
    /// Best-effort overlay. Never throws — callers must still get the GDI image.
    /// </summary>
    public static void OverlayProtectedWindows(Bitmap baseBmp, Rectangle virtualScreen)
    {
        try
        {
            if (baseBmp == null || baseBmp.Width <= 0 || baseBmp.Height <= 0)
                return;
            if (Native.DwmIsCompositionEnabled(out var enabled) != 0 || !enabled)
                return; // No DWM -> no shared surfaces; GDI image is all we have.

            var hwnds = CollectProtectedWindows();
            if (hwnds.Count == 0)
                return;
            if (!EnsureDevice())
                return;

            // EnumWindows returns top-to-bottom; draw bottom-to-top.
            hwnds.Reverse();

            var deadline = DateTime.UtcNow.AddSeconds(8);
            using var g = Graphics.FromImage(baseBmp);
            int overlaid = 0;
            foreach (var hwnd in hwnds)
            {
                if (DateTime.UtcNow > deadline || overlaid >= 16)
                    break;
                try
                {
                    if (!TryCaptureWindow(hwnd, out var bmp, out var screenPos) || bmp == null)
                        continue;
                    using (bmp)
                    {
                        int dx = screenPos.X - virtualScreen.Left;
                        int dy = screenPos.Y - virtualScreen.Top;
                        // Skip fully off-screen windows.
                        if (dx + bmp.Width <= 0 || dy + bmp.Height <= 0 ||
                            dx >= baseBmp.Width || dy >= baseBmp.Height)
                            continue;
                        g.DrawImageUnscaled(bmp, dx, dy);
                        overlaid++;
                    }
                }
                catch { /* per-window best effort */ }
            }
        }
        catch { /* never break the main capture */ }
    }

    public static int CountProtectedWindows()
    {
        try { return CollectProtectedWindows().Count; }
        catch { return 0; }
    }

    /// <summary>Diag helper: try capturing each protected window, return status lines.</summary>
    public static List<string> DiagProtectedWindows()
    {
        var out_ = new List<string>();
        try
        {
            var hwnds = CollectProtectedWindows();
            if (hwnds.Count == 0) { out_.Add("none"); return out_; }
            if (!EnsureDevice(out var d3dErr)) { out_.Add($"found={hwnds.Count} d3d=FAILED {d3dErr}"); return out_; }
            out_.Add($"found={hwnds.Count} d3d=OK");
            foreach (var h in hwnds)
            {
                try
                {
                    if (TryCaptureWindowEx(h, out var bmp, out var pos, out var err) && bmp != null)
                    {
                        using (bmp)
                            out_.Add($"hwnd={h} capture=OK {bmp.Width}x{bmp.Height} at {pos.X},{pos.Y}");
                    }
                    else out_.Add($"hwnd={h} capture=FAILED {err}");
                }
                catch (Exception ex) { out_.Add($"hwnd={h} capture=EX {ex.Message}"); }
            }
        }
        catch (Exception ex) { out_.Add($"diag=EX {ex.Message}"); }
        return out_;
    }

    // ------------------------------------------------------------------
    // Window enumeration
    // ------------------------------------------------------------------

    private static List<IntPtr> CollectProtectedWindows()
    {
        var list = new List<IntPtr>();
        Native.EnumWindows((hwnd, _) =>
        {
            try
            {
                if (!Native.IsWindowVisible(hwnd) || Native.IsIconic(hwnd))
                    return true;
                if (!Native.GetWindowRect(hwnd, out var rc) || rc.Right <= rc.Left || rc.Bottom <= rc.Top)
                    return true;
                // Skip zero-size / huge off-screen helper windows.
                long w = (long)rc.Right - rc.Left, h = (long)rc.Bottom - rc.Top;
                if (w <= 0 || h <= 0 || w > 16384 || h > 16384)
                    return true;
                if (Native.GetWindowDisplayAffinity(hwnd, out var affinity) && affinity != WDA_NONE)
                    list.Add(hwnd);
            }
            catch { }
            return true;
        }, IntPtr.Zero);
        return list;
    }

    // ------------------------------------------------------------------
    // Per-window DWM shared-surface capture
    // ------------------------------------------------------------------

    private static bool TryCaptureWindow(IntPtr hwnd, out Bitmap? bmp, out Point screenPos)
        => TryCaptureWindowEx(hwnd, out bmp, out screenPos, out _);

    private static bool TryCaptureWindowEx(IntPtr hwnd, out Bitmap? bmp, out Point screenPos, out string err)
    {
        bmp = null;
        screenPos = Point.Empty;
        err = "";
        try
        {
            // Client rect + position (shared surface is client area, no title bar).
            if (!Native.GetClientRect(hwnd, out var client) || client.Right <= 0 || client.Bottom <= 0)
            { err = "GetClientRect=0"; return false; }
            var pt = new Native.POINT { X = 0, Y = 0 };
            if (!Native.ClientToScreen(hwnd, ref pt))
            { err = "ClientToScreen=0"; return false; }
            int cw = client.Right, ch = client.Bottom;
            if (cw <= 0 || ch <= 0 || cw > 8192 || ch > 8192)
            { err = $"badsize={cw}x{ch}"; return false; }
            screenPos = new Point(pt.X, pt.Y);

            if (!Native.DwmGetDxSharedSurface(hwnd, out var sharedHandle, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero)
                || sharedHandle == IntPtr.Zero)
            { err = $"DwmGet=0 lastErr={Marshal.GetLastWin32Error()}"; return false; }

            lock (_d3dLock)
            {
                if (_device == IntPtr.Zero || _context == IntPtr.Zero)
                { err = "no-d3d-device"; return false; }
                bmp = OpenSharedSurfaceToBitmap(sharedHandle, cw, ch, out err);
                return bmp != null;
            }
        }
        catch (Exception ex) { err = "EX:" + ex.Message; bmp = null; return false; }
    }

    private static Bitmap? OpenSharedSurfaceToBitmap(IntPtr sharedHandle, int hintW, int hintH)
        => OpenSharedSurfaceToBitmap(sharedHandle, hintW, hintH, out _);

    private static Bitmap? OpenSharedSurfaceToBitmap(IntPtr sharedHandle, int hintW, int hintH, out string err)
    {
        err = "";
        // Try primary device first, then every enumerated adapter device.
        // DWM surfaces are adapter-bound; dual-GPU (Intel+NVIDIA) needs the match.
        var errs = new List<string>();
        List<(IntPtr dev, IntPtr ctx)> devices;
        lock (_d3dLock)
        {
            devices = new List<(IntPtr, IntPtr)>();
            if (_device != IntPtr.Zero && _context != IntPtr.Zero)
                devices.Add((_device, _context));
            foreach (var (d, c, _) in _adapterDevices)
                if (d != IntPtr.Zero && d != _device)
                    devices.Add((d, c));
        }
        foreach (var (dev, ctx) in devices)
        {
            var bmp = TryOpenOnDevice(dev, ctx, sharedHandle, out var e);
            if (bmp != null) { err = ""; return bmp; }
            errs.Add(e);
        }
        err = string.Join(" | ", errs);
        if (string.IsNullOrEmpty(err)) err = "no-d3d-devices";
        return null;
    }

    private static Bitmap? TryOpenOnDevice(IntPtr device, IntPtr context, IntPtr sharedHandle, out string err)
    {
        err = "";
        IntPtr sharedTex = IntPtr.Zero, stagingTex = IntPtr.Zero;
        try
        {
            var open = GetVFunction<OpenSharedResourceDelegate>(device, 28);
            int hr = open(device, sharedHandle, ref Native.IID_ID3D11Texture2D, out sharedTex);
            if (hr != 0 || sharedTex == IntPtr.Zero)
            { err = $"OpenShared=0x{hr:X8}"; return null; }

            // GetDesc: header says index 9; some samples use 10. Try 9, validate, fall back to 10.
            var desc = new Native.D3D11_TEXTURE2D_DESC();
            bool gotDesc = false;
            string descErr = "";
            try
            {
                var get9 = GetVFunction<GetDescDelegate>(sharedTex, 9);
                get9(sharedTex, out desc);
                if (desc.Width > 0 && desc.Width <= 8192 && desc.Height > 0 && desc.Height <= 8192)
                    gotDesc = true;
                else descErr = $"Get9={desc.Width}x{desc.Height} fmt={desc.Format}";
            }
            catch (Exception ex) { descErr = "Get9EX:" + ex.Message; }
            if (!gotDesc)
            {
                try
                {
                    var get10 = GetVFunction<GetDescDelegate>(sharedTex, 10);
                    get10(sharedTex, out desc);
                    if (desc.Width > 0 && desc.Width <= 8192 && desc.Height > 0 && desc.Height <= 8192)
                        gotDesc = true;
                    else { err = $"GetDesc bad 9[{descErr}] 10[{desc.Width}x{desc.Height} fmt={desc.Format}]"; return null; }
                }
                catch (Exception ex) { err = $"GetDesc EX 9[{descErr}] 10[{ex.Message}]"; return null; }
            }

            var stagingDesc = new Native.D3D11_TEXTURE2D_DESC
            {
                Width = desc.Width,
                Height = desc.Height,
                MipLevels = 1,
                ArraySize = 1,
                Format = desc.Format,
                SampleDesc = new Native.DXGI_SAMPLE_DESC { Count = 1, Quality = 0 },
                Usage = Native.D3D11_USAGE_STAGING,
                BindFlags = 0,
                CPUAccessFlags = Native.D3D11_CPU_ACCESS_READ,
                MiscFlags = 0
            };

            var create = GetVFunction<CreateTexture2DDelegate>(device, 5);
            hr = create(device, ref stagingDesc, IntPtr.Zero, out stagingTex);
            if (hr != 0 || stagingTex == IntPtr.Zero)
            { err = $"CreateStaging=0x{hr:X8} {desc.Width}x{desc.Height} fmt={desc.Format}"; return null; }

            var copy = GetVFunction<CopyResourceDelegate>(context, 47);
            copy(context, stagingTex, sharedTex);

            var map = GetVFunction<MapDelegate>(context, 14);
            hr = map(context, stagingTex, 0, Native.D3D11_MAP_READ, 0, out var mapped);
            if (hr != 0 || mapped.pData == IntPtr.Zero)
            { err = $"Map=0x{hr:X8} pitch={mapped.RowPitch}"; return null; }
            try
            {
                int width = (int)desc.Width, height = (int)desc.Height;
                var bmp = new Bitmap(width, height, PixelFormat.Format32bppArgb);
                var bd = bmp.LockBits(new Rectangle(0, 0, width, height),
                    ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
                try
                {
                    int dstStride = Math.Abs(bd.Stride);
                    int srcPitch = (int)mapped.RowPitch;
                    int rowBytes = width * 4;
                    var row = new byte[rowBytes];
                    for (int y = 0; y < height; y++)
                    {
                        Marshal.Copy(IntPtr.Add(mapped.pData, y * srcPitch), row, 0, rowBytes);
                        Marshal.Copy(row, 0, IntPtr.Add(bd.Scan0, y * dstStride), rowBytes);
                    }
                }
                finally { bmp.UnlockBits(bd); }
                return bmp;
            }
            finally
            {
                try
                {
                    var unmap = GetVFunction<UnmapDelegate>(context, 15);
                    unmap(context, stagingTex, 0);
                }
                catch { }
            }
        }
        finally
        {
            if (stagingTex != IntPtr.Zero) ReleaseCom(stagingTex);
            if (sharedTex != IntPtr.Zero) ReleaseCom(sharedTex);
        }
    }

    // ------------------------------------------------------------------
    // D3D11 device (cached)
    // ------------------------------------------------------------------

    private static bool EnsureDevice() => EnsureDevice(out _);

    private static bool EnsureDevice(out string err)
    {
        err = "";
        lock (_d3dLock)
        {
            if (_device != IntPtr.Zero && _context != IntPtr.Zero)
                return true;
            if (_d3dTried && _device == IntPtr.Zero)
            {
                // Retry occasionally (GPU state may change), but not every frame.
                _d3dTried = false;
            }
            _d3dTried = true;
            int[] levels = { 0xb100, 0xb000, 0xa100, 0xa000, 0x9300, 0x9200, 0x9100 };
            // Primary path (matches working wda-bypass reference): DXGI adapter 0 +
            // D3D11CreateDevice(UNKNOWN). The DWM shared handle is adapter-bound;
            // a NULL-adapter HARDWARE device gives E_INVALIDARG on OpenSharedResource.
            string eDxgi = "";
            if (TryCreateOnDxgiAdapter(levels, out eDxgi)) return true;
            string e1 = "", e2 = "", e3 = "";
            if (TryCreate(IntPtr.Zero, 1, 0x20, levels, out e1)) return true;
            if (TryCreate(IntPtr.Zero, 2, 0x20, levels, out e2)) return true;
            if (TryCreate(IntPtr.Zero, 1, 0, levels, out e3)) return true;
            err = $"dxgi[{eDxgi}] hwBGRA[{e1}] warp[{e2}] hw[{e3}]";
            return _device != IntPtr.Zero && _context != IntPtr.Zero;
        }
    }

    private static bool TryCreateOnDxgiAdapter(int[] levels, out string err)
    {
        err = "";
        IntPtr factory = IntPtr.Zero;
        var errs = new List<string>();
        try
        {
            // Prefer Factory1/EnumAdapters1, fall back to Factory/EnumAdapters.
            int enumIndex = 7;
            var iidF1 = Native.IID_IDXGIFactory1;
            int hr = Native.CreateDXGIFactory1(ref iidF1, out factory);
            if (hr == 0 && factory != IntPtr.Zero)
                enumIndex = 12; // IDXGIFactory1::EnumAdapters1
            else
            {
                if (factory != IntPtr.Zero) ReleaseCom(factory);
                factory = IntPtr.Zero;
                var iidF = Native.IID_IDXGIFactory;
                hr = Native.CreateDXGIFactory(ref iidF, out factory);
                if (hr != 0 || factory == IntPtr.Zero)
                { err = $"Factory=0x{hr:X8}"; return false; }
                enumIndex = 7; // IDXGIFactory::EnumAdapters
            }
            EnumAdaptersDelegate? enumAdapters = null;
            try { enumAdapters = GetVFunction<EnumAdaptersDelegate>(factory, enumIndex); }
            catch (Exception ex) { err = "EnumGetEX:" + ex.Message; return false; }

            bool any = false;
            // Enumerate adapters 0..3 (covers Intel+NVIDIA dual-GPU). DWM surfaces
            // are adapter-bound, so keep a device per adapter and try all on capture.
            for (uint i = 0; i < 4; i++)
            {
                IntPtr adapter = IntPtr.Zero;
                try
                {
                    hr = enumAdapters(factory, i, out adapter);
                    if (hr != 0 || adapter == IntPtr.Zero)
                    {
                        if (i == 0) errs.Add($"Enum0=0x{hr:X8}");
                        continue;
                    }
                    // Adapter specified -> DriverType must be UNKNOWN (0).
                    if (TryCreateKeep(adapter, 0, 0x20, levels, (int)i, out var e))
                        any = true;
                    else errs.Add($"ad{i}[{e}]");
                    ReleaseCom(adapter);
                }
                catch (Exception ex) { errs.Add($"ad{i}EX:{ex.Message}"); }
                finally { adapter = IntPtr.Zero; }
            }
            // Promote first adapter device to primary for single-device paths.
            lock (_d3dLock)
            {
                if (_adapterDevices.Count > 0 && (_device == IntPtr.Zero))
                {
                    _device = _adapterDevices[0].dev;
                    _context = _adapterDevices[0].ctx;
                }
            }
            err = string.Join(" ", errs);
            return any;
        }
        finally
        {
            if (factory != IntPtr.Zero) ReleaseCom(factory);
        }
    }

    private static bool TryCreateKeep(IntPtr adapter, int driverType, uint flags, int[] levels, int adapterIndex, out string err)
    {
        err = "";
        try
        {
            int hr = Native.D3D11CreateDevice(adapter, driverType, IntPtr.Zero, flags,
                levels, (uint)levels.Length, 7, out var dev, out _, out var ctx);
            if (hr == 0 && dev != IntPtr.Zero && ctx != IntPtr.Zero)
            {
                lock (_d3dLock)
                {
                    _adapterDevices.Add((dev, ctx, adapterIndex));
                    if (_device == IntPtr.Zero) { _device = dev; _context = ctx; }
                }
                return true;
            }
            err = $"hr=0x{hr:X8}";
            if (dev != IntPtr.Zero) ReleaseCom(dev);
            if (ctx != IntPtr.Zero) ReleaseCom(ctx);
        }
        catch (Exception ex) { err = "EX:" + ex.Message; }
        return false;
    }

    private static bool TryCreate(int driverType, uint flags, int[] levels) => TryCreate(IntPtr.Zero, driverType, flags, levels, out _);

    private static bool TryCreate(IntPtr adapter, int driverType, uint flags, int[] levels, out string err)
    {
        err = "";
        try
        {
            int hr = Native.D3D11CreateDevice(adapter, driverType, IntPtr.Zero, flags,
                levels, (uint)levels.Length, 7, out var dev, out _, out var ctx);
            if (hr == 0 && dev != IntPtr.Zero && ctx != IntPtr.Zero)
            {
                // Drop previous (shouldn't exist on first create).
                if (_device != IntPtr.Zero && _device != dev) ReleaseCom(_device);
                if (_context != IntPtr.Zero && _context != ctx) ReleaseCom(_context);
                _device = dev;
                _context = ctx;
                return true;
            }
            err = $"hr=0x{hr:X8}";
            if (dev != IntPtr.Zero) ReleaseCom(dev);
            if (ctx != IntPtr.Zero) ReleaseCom(ctx);
        }
        catch (Exception ex) { err = "EX:" + ex.Message; }
        return false;
    }

    private static void ReleaseCom(IntPtr unk)
    {
        try
        {
            var release = GetVFunction<ReleaseDelegate>(unk, 2);
            release(unk);
        }
        catch { }
    }

    private static T GetVFunction<T>(IntPtr obj, int index) where T : Delegate
    {
        var vtable = Marshal.ReadIntPtr(obj);
        var fn = Marshal.ReadIntPtr(vtable, index * IntPtr.Size);
        return Marshal.GetDelegateForFunctionPointer<T>(fn);
    }

    // ------------------------------------------------------------------
    // Delegates + native imports
    // ------------------------------------------------------------------

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int OpenSharedResourceDelegate(IntPtr self, IntPtr hResource, ref Guid returnedInterface, out IntPtr ppResource);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int CreateTexture2DDelegate(IntPtr self, ref Native.D3D11_TEXTURE2D_DESC desc, IntPtr pInitData, out IntPtr ppTexture2D);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate void GetDescDelegate(IntPtr self, out Native.D3D11_TEXTURE2D_DESC desc);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate void CopyResourceDelegate(IntPtr self, IntPtr dst, IntPtr src);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int MapDelegate(IntPtr self, IntPtr resource, uint subresource, uint mapType, uint mapFlags, out Native.D3D11_MAPPED_SUBRESOURCE mapped);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate void UnmapDelegate(IntPtr self, IntPtr resource, uint subresource);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint ReleaseDelegate(IntPtr self);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int EnumAdaptersDelegate(IntPtr self, uint index, out IntPtr ppAdapter);

    private static class Native
    {
        public const uint D3D11_USAGE_STAGING = 3;
        public const uint D3D11_CPU_ACCESS_READ = 0x20000;
        public const uint D3D11_MAP_READ = 1;
        public static Guid IID_ID3D11Texture2D = new("6f15aaf2-d208-4e89-9ab4-489535d34f9c");
        public static Guid IID_IDXGIFactory = new("7b7166ec-21c7-44ae-b21a-c9ae321ae369");
        public static Guid IID_IDXGIFactory1 = new("770aae78-f26f-4dba-a829-253c83d1aa39");

        [StructLayout(LayoutKind.Sequential)]
        public struct DXGI_SAMPLE_DESC { public uint Count; public uint Quality; }

        [StructLayout(LayoutKind.Sequential)]
        public struct D3D11_TEXTURE2D_DESC
        {
            public uint Width; public uint Height; public uint MipLevels; public uint ArraySize;
            public uint Format; public DXGI_SAMPLE_DESC SampleDesc; public uint Usage;
            public uint BindFlags; public uint CPUAccessFlags; public uint MiscFlags;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct D3D11_MAPPED_SUBRESOURCE
        {
            public IntPtr pData; public uint RowPitch; public uint DepthPitch;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }

        [StructLayout(LayoutKind.Sequential)]
        public struct POINT { public int X; public int Y; }

        public delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr lParam);

        [DllImport("user32.dll")]
        public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
        [DllImport("user32.dll")]
        public static extern bool IsWindowVisible(IntPtr hwnd);
        [DllImport("user32.dll")]
        public static extern bool IsIconic(IntPtr hwnd);
        [DllImport("user32.dll")]
        public static extern bool GetWindowRect(IntPtr hwnd, out RECT lpRect);
        [DllImport("user32.dll")]
        public static extern bool GetClientRect(IntPtr hwnd, out RECT lpRect);
        [DllImport("user32.dll")]
        public static extern bool ClientToScreen(IntPtr hwnd, ref POINT lpPoint);
        [DllImport("user32.dll")]
        public static extern bool GetWindowDisplayAffinity(IntPtr hwnd, out uint affinity);
        [DllImport("user32.dll", ExactSpelling = true, SetLastError = true)]
        public static extern bool DwmGetDxSharedSurface(IntPtr hwnd, out IntPtr phSurface,
            IntPtr pAdapterLuid, IntPtr pFmtWindow, IntPtr pPresentFlags, IntPtr pWin32kUpdateId);
        [DllImport("dwmapi.dll", PreserveSig = true)]
        public static extern int DwmIsCompositionEnabled(out bool enabled);

        [DllImport("d3d11.dll", ExactSpelling = true)]
        public static extern int D3D11CreateDevice(IntPtr pAdapter, int DriverType, IntPtr Software,
            uint Flags, int[]? pFeatureLevels, uint FeatureLevels, uint SDKVersion,
            out IntPtr ppDevice, out int pFeatureLevel, out IntPtr ppImmediateContext);

        [DllImport("dxgi.dll", ExactSpelling = true)]
        public static extern int CreateDXGIFactory(ref Guid riid, out IntPtr ppFactory);

        [DllImport("dxgi.dll", ExactSpelling = true)]
        public static extern int CreateDXGIFactory1(ref Guid riid, out IntPtr ppFactory);
    }
}
