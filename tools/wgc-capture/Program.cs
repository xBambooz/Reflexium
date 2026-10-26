using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Windows.Graphics;
using Windows.Graphics.Capture;
using Windows.Graphics.DirectX;
using Windows.Graphics.DirectX.Direct3D11;
using WinRT;

internal static class Program
{
	private const uint DESKTOP_ALL = 0x01FF;
	private const uint DWMWA_EXTENDED_FRAME_BOUNDS = 9;
	private const uint D3D11_USAGE_STAGING = 3;
	private const uint D3D11_CPU_ACCESS_READ = 0x20000;
	private const uint D3D11_SDK_VERSION = 7;

	private static readonly Guid IID_IGraphicsCaptureItemInterop = new("3628e81b-3cac-4c60-b7f4-23ce0e0c3356");
	private static readonly Guid IID_IGraphicsCaptureItem = new("79C3F95B-31F7-4EC2-A464-632EF5D30760");
	private static readonly Guid IID_IDirect3DDxgiInterfaceAccess = new("A9B3D012-3DF2-4EE3-B8D1-8695F457D3C1");
	private static readonly Guid IID_ID3D11Texture2D = new("6f15aaf2-d208-4e89-9ab4-489535d34f9c");

	[ComImport]
	[Guid("3628e81b-3cac-4c60-b7f4-23ce0e0c3356")]
	[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
	private interface IGraphicsCaptureItemInterop
	{
		[PreserveSig] int CreateForWindow([In] nint window, [In] in Guid iid, out nint result);
		[PreserveSig] int CreateForMonitor([In] nint monitor, [In] in Guid iid, out nint result);
	}

	[StructLayout(LayoutKind.Sequential)]
	private struct RECT { public int Left, Top, Right, Bottom; }

	[StructLayout(LayoutKind.Sequential)]
	private struct D3D11_TEXTURE2D_DESC
	{
		public uint Width, Height, MipLevels, ArraySize, Format;
		public uint SampleDescCount, SampleDescQuality, Usage, BindFlags, CPUAccessFlags, MiscFlags;
	}

	[StructLayout(LayoutKind.Sequential)]
	private struct D3D11_MAPPED_SUBRESOURCE { public nint pData; public uint RowPitch, DepthPitch; }

	[DllImport("api-ms-win-core-winrt-l1-1-0.dll", CallingConvention = CallingConvention.StdCall)]
	private static extern int RoGetActivationFactory(nint activatableClassId, in Guid iid, out nint factory);

	[DllImport("api-ms-win-core-winrt-string-l1-1-0.dll", CallingConvention = CallingConvention.StdCall, ExactSpelling = true)]
	private static extern int WindowsCreateString([MarshalAs(UnmanagedType.LPWStr)] string sourceString, int length, out nint hstring);

	[DllImport("api-ms-win-core-winrt-string-l1-1-0.dll", CallingConvention = CallingConvention.StdCall, ExactSpelling = true)]
	private static extern int WindowsDeleteString(nint hstring);

	[DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
	private static extern nint OpenDesktop(string lpszDesktop, uint dwFlags, bool fInherit, uint dwDesiredAccess);

	[DllImport("user32.dll", SetLastError = true)]
	private static extern bool SetThreadDesktop(nint hDesktop);

	[DllImport("user32.dll", SetLastError = true)]
	private static extern bool CloseDesktop(nint hDesktop);

	[DllImport("user32.dll")]
	private static extern bool EnumWindows(EnumWindowsProc callback, nint lParam);

	private delegate bool EnumWindowsProc(nint hWnd, nint lParam);

	[DllImport("user32.dll")]
	private static extern uint GetWindowThreadProcessId(nint hWnd, out uint processId);

	[DllImport("user32.dll")]
	[return: MarshalAs(UnmanagedType.Bool)]
	private static extern bool IsWindowVisible(nint hWnd);

	[DllImport("user32.dll")]
	[return: MarshalAs(UnmanagedType.Bool)]
	private static extern bool IsWindow(nint hWnd);

	[DllImport("user32.dll")]
	[return: MarshalAs(UnmanagedType.Bool)]
	private static extern bool IsIconic(nint hWnd);

	[DllImport("user32.dll")]
	[return: MarshalAs(UnmanagedType.Bool)]
	private static extern bool GetClientRect(nint hWnd, out RECT lpRect);

	[DllImport("dwmapi.dll")]
	private static extern int DwmGetWindowAttribute(nint hWnd, uint attribute, out RECT value, uint size);

	[DllImport("d3d11.dll", CallingConvention = CallingConvention.StdCall, SetLastError = true)]
	private static extern int D3D11CreateDevice(nint adapter, int driverType, nint software, uint flags,
			nint featureLevels, uint featureLevelCount, uint sdkVersion, out nint device, out int featureLevel, out nint context);

	[DllImport("d3d11.dll", EntryPoint = "CreateDirect3D11DeviceFromDXGIDevice", CallingConvention = CallingConvention.StdCall, SetLastError = true)]
	private static extern int CreateDirect3D11DeviceFromDXGIDevice(nint dxgiDevice, out nint graphicsDevice);

	private static nint pDevice;
	private static nint pContext;
	private static IDirect3DDevice? d3dDevice;

	private static int Main(string[] args)
	{
		var options = Parse(args);
		if (options.ContainsKey("help"))
		{
			Console.WriteLine("wgc-capture --pid <pid> | --hwnd <hwnd> [--out <dir>] [--count <n>] [--seconds <s>] [--png true|false] [--every <n>] [--crop x,y,w,h] [--min-interval-ms <ms>]");
			return 0;
		}

		EnsureInteractiveDesktop();
		nint hwnd = ResolveWindow(options);
		if (hwnd == 0)
		{
			Console.Error.WriteLine("No capture window found.");
			return 2;
		}
		if (!IsWindow(hwnd) || IsIconic(hwnd))
		{
			Console.Error.WriteLine("Capture window is closed or minimized.");
			return 3;
		}

		string outDir = options.GetValueOrDefault("out", Path.Combine(Environment.CurrentDirectory, "wgc-capture-out"));
		Directory.CreateDirectory(outDir);
		int count = int.TryParse(options.GetValueOrDefault("count", "120"), out int c) ? Math.Max(1, c) : 120;
		double seconds = double.TryParse(options.GetValueOrDefault("seconds", "0"), NumberStyles.Float, CultureInfo.InvariantCulture, out double s) ? s : 0;
		bool savePng = !string.Equals(options.GetValueOrDefault("png", "true"), "false", StringComparison.OrdinalIgnoreCase);
		int every = int.TryParse(options.GetValueOrDefault("every", "1"), out int e) ? Math.Max(1, e) : 1;
		double minIntervalMs = double.TryParse(options.GetValueOrDefault("min-interval-ms", "0"), NumberStyles.Float, CultureInfo.InvariantCulture, out double mi) ? Math.Max(0, mi) : 0;
		var crop = ParseCrop(options.GetValueOrDefault("crop", ""));

		var item = CreateCaptureItemForWindow(hwnd);
		if (item.Size.Width <= 0 || item.Size.Height <= 0)
		{
			Console.Error.WriteLine("Capture item has zero size.");
			return 4;
		}

		CreateDevice();
		using var pool = Direct3D11CaptureFramePool.CreateFreeThreaded(d3dDevice!,
				DirectXPixelFormat.B8G8R8A8UIntNormalized, 3, item.Size);
		using var session = pool.CreateCaptureSession(item);
		session.IsCursorCaptureEnabled = false;
		try { session.MinUpdateInterval = TimeSpan.FromMilliseconds(minIntervalMs); } catch { }
		try { session.IsBorderRequired = false; } catch { }

		var csv = new StringBuilder("index,system_relative_ms,utc_ms,width,height,png,hash\n");
		var done = new ManualResetEventSlim(false);
		var timer = new Stopwatch();
		int captured = 0;
		int pngs = 0;
		var interval = new List<double>();
		double last = double.NaN;

		pool.FrameArrived += (sender, _) =>
		{
			using var frame = sender.TryGetNextFrame();
			if (frame == null) return;
			try
			{
				double ms = frame.SystemRelativeTime.TotalMilliseconds;
				if (!double.IsNaN(last)) interval.Add((ms - last) * 1000.0);
				last = ms;
				int index = Interlocked.Increment(ref captured) - 1;
				var (width, height, bytes, stride) = ExtractFrame(frame, crop);
				string png = "";
				if (savePng && index % every == 0)
				{
					png = Path.Combine(outDir, $"frame_{index:D5}.png");
					SavePng(png, width, height, bytes, stride);
					pngs++;
				}
				lock (csv)
				{
					csv.Append(index).Append(',').Append(ms.ToString("F3", CultureInfo.InvariantCulture)).Append(',')
							.Append(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()).Append(',').Append(width).Append(',')
							.Append(height).Append(',').Append(png).Append(',').Append(Hash(bytes).ToString("x16")).Append('\n');
				}
			}
			catch (Exception ex)
			{
				Console.Error.WriteLine("frame: " + ex.Message);
			}
			if (captured >= count || seconds > 0 && timer.Elapsed.TotalSeconds >= seconds) done.Set();
		};

		timer.Start();
		session.StartCapture();
		done.Wait(TimeSpan.FromSeconds(Math.Max(10, seconds > 0 ? seconds + 10 : count / 10.0 + 20)));
		timer.Stop();
		try { session.Dispose(); } catch { }

		File.WriteAllText(Path.Combine(outDir, "frames.csv"), csv.ToString());
		var sorted = interval.OrderBy(v => v).ToArray();
		double median = sorted.Length == 0 ? 0 : sorted[sorted.Length / 2];
		double mean = sorted.Length == 0 ? 0 : sorted.Average();
		Console.WriteLine($"{{\"ok\":true,\"hwnd\":{hwnd},\"pid\":{GetPid(hwnd)},\"frames\":{captured},\"pngs\":{pngs},\"median_interval_us\":{median:F1},\"mean_interval_us\":{mean:F1},\"elapsed_s\":{timer.Elapsed.TotalSeconds:F2},\"out\":\"{outDir.Replace("\\", "\\\\")}\"}}");
		return captured > 0 ? 0 : 5;
	}

	private static (int x, int y, int w, int h) ParseCrop(string text)
	{
		var parts = text.Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);
		if (parts.Length != 4) return (0, 0, 0, 0);
		return (int.Parse(parts[0], CultureInfo.InvariantCulture), int.Parse(parts[1], CultureInfo.InvariantCulture),
				int.Parse(parts[2], CultureInfo.InvariantCulture), int.Parse(parts[3], CultureInfo.InvariantCulture));
	}

	private static Dictionary<string, string> Parse(string[] args)
	{
		var result = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
		for (int i = 0; i < args.Length; i++)
		{
			if (!args[i].StartsWith("--")) continue;
			string key = args[i][2..];
			if (key.Contains('=')) { var p = key.Split('=', 2); result[p[0]] = p[1]; continue; }
			string value = i + 1 < args.Length && !args[i + 1].StartsWith("--") ? args[++i] : "true";
			result[key] = value;
		}
		return result;
	}

	private static nint ResolveWindow(Dictionary<string, string> options)
	{
		if (options.TryGetValue("hwnd", out string? hwndText))
		{
			return long.TryParse(hwndText, NumberStyles.HexNumber, CultureInfo.InvariantCulture, out long hex) ? (nint)hex
					: long.TryParse(hwndText, out long dec) ? (nint)dec : 0;
		}
		if (!options.TryGetValue("pid", out string? pidText) || !uint.TryParse(pidText, out uint pid)) return 0;
		nint best = 0;
		long bestArea = 0;
		EnumWindows((hWnd, _) =>
		{
			GetWindowThreadProcessId(hWnd, out uint owner);
			if (owner != pid || !IsWindowVisible(hWnd) || IsIconic(hWnd)) return true;
			if (!GetClientRect(hWnd, out RECT client)) return true;
			long area = (long)(client.Right - client.Left) * (client.Bottom - client.Top);
			if (area > bestArea) { bestArea = area; best = hWnd; }
			return true;
		}, 0);
		return best;
	}

	private static uint GetPid(nint hwnd)
	{
		GetWindowThreadProcessId(hwnd, out uint pid);
		return pid;
	}

	private static void EnsureInteractiveDesktop()
	{
		nint desktop = OpenDesktop("default", 0, false, DESKTOP_ALL);
		if (desktop == 0) return;
		SetThreadDesktop(desktop);
		CloseDesktop(desktop);
	}

	private static GraphicsCaptureItem CreateCaptureItemForWindow(nint hwnd)
	{
		const string typeName = "Windows.Graphics.Capture.GraphicsCaptureItem";
		int hr = WindowsCreateString(typeName, typeName.Length, out nint hstring);
		if (hr != 0) throw new InvalidOperationException($"WindowsCreateString 0x{hr:X8}");
		try
		{
			var iid = IID_IGraphicsCaptureItemInterop;
			hr = RoGetActivationFactory(hstring, in iid, out nint factory);
			if (hr != 0) throw new InvalidOperationException($"RoGetActivationFactory 0x{hr:X8}");
			try
			{
				var interop = (IGraphicsCaptureItemInterop)Marshal.GetObjectForIUnknown(factory);
				var itemIid = IID_IGraphicsCaptureItem;
				hr = interop.CreateForWindow(hwnd, in itemIid, out nint item);
				if (hr != 0) throw new InvalidOperationException($"CreateForWindow 0x{hr:X8}");
				try { return GraphicsCaptureItem.FromAbi(item); }
				finally { Marshal.Release(item); }
			}
			finally { Marshal.Release(factory); }
		}
		finally { WindowsDeleteString(hstring); }
	}

	private static void CreateDevice()
	{
		if (d3dDevice != null) return;
		int hr = D3D11CreateDevice(0, 1, 0, 0x20, 0, 0, D3D11_SDK_VERSION, out pDevice, out _, out pContext);
		if (hr != 0) hr = D3D11CreateDevice(0, 2, 0, 0x20, 0, 0, D3D11_SDK_VERSION, out pDevice, out _, out pContext);
		if (hr != 0) throw new InvalidOperationException($"D3D11CreateDevice 0x{hr:X8}");
		hr = CreateDirect3D11DeviceFromDXGIDevice(pDevice, out nint graphicsDevice);
		if (hr != 0) throw new InvalidOperationException($"CreateDirect3D11DeviceFromDXGIDevice 0x{hr:X8}");
		try { d3dDevice = MarshalInterface<IDirect3DDevice>.FromAbi(graphicsDevice); }
		finally { Marshal.Release(graphicsDevice); }
	}

	private static unsafe nint GetTexture(IDirect3DSurface surface)
	{
		nint native = ((IWinRTObject)surface).NativeObject.ThisPtr;
		var accessIid = IID_IDirect3DDxgiInterfaceAccess;
		int hr = Marshal.QueryInterface(native, in accessIid, out nint access);
		if (hr != 0) throw new InvalidOperationException($"QueryInterface(DXGI access) 0x{hr:X8}");
		try
		{
			var vtable = *(void***)access;
			var getInterface = (delegate* unmanaged[Stdcall]<nint, Guid*, nint*, int>)vtable[3];
			var textureIid = IID_ID3D11Texture2D;
			nint texture = 0;
			hr = getInterface(access, &textureIid, &texture);
			if (hr != 0) throw new InvalidOperationException($"GetInterface(ID3D11Texture2D) 0x{hr:X8}");
			return texture;
		}
		finally { Marshal.Release(access); }
	}

	private static unsafe (int width, int height, byte[] bytes, int stride) ExtractFrame(
			Direct3D11CaptureFrame frame, (int x, int y, int w, int h) crop)
	{
		nint texture = GetTexture(frame.Surface);
		try
		{
			var textureVtable = *(void***)texture;
			var getDesc = (delegate* unmanaged[Stdcall]<nint, D3D11_TEXTURE2D_DESC*, void>)textureVtable[10];
			D3D11_TEXTURE2D_DESC desc;
			getDesc(texture, &desc);
			if (crop.w <= 0 || crop.h <= 0) crop = (0, 0, checked((int)desc.Width), checked((int)desc.Height));
			if (crop.x < 0 || crop.y < 0 || (long)crop.x + crop.w > desc.Width || (long)crop.y + crop.h > desc.Height)
				throw new ArgumentException($"crop {crop} exceeds {desc.Width}x{desc.Height}");
			var staging = desc;
			staging.MipLevels = 1;
			staging.ArraySize = 1;
			staging.Usage = D3D11_USAGE_STAGING;
			staging.BindFlags = 0;
			staging.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
			staging.MiscFlags = 0;

			var deviceVtable = *(void***)pDevice;
			var createTexture = (delegate* unmanaged[Stdcall]<nint, D3D11_TEXTURE2D_DESC*, void*, nint*, int>)deviceVtable[5];
			nint stagingTexture = 0;
			int hr = createTexture(pDevice, &staging, null, &stagingTexture);
			if (hr != 0) throw new InvalidOperationException($"CreateTexture2D(staging) 0x{hr:X8}");
			try
			{
				var contextVtable = *(void***)pContext;
				var copyResource = (delegate* unmanaged[Stdcall]<nint, nint, nint, void>)contextVtable[47];
				copyResource(pContext, stagingTexture, texture);
				var map = (delegate* unmanaged[Stdcall]<nint, nint, uint, uint, uint, D3D11_MAPPED_SUBRESOURCE*, int>)contextVtable[14];
				D3D11_MAPPED_SUBRESOURCE mapped;
				hr = map(pContext, stagingTexture, 0, 1, 0, &mapped);
				if (hr != 0) throw new InvalidOperationException($"Map(staging) 0x{hr:X8}");
				try
				{
					int width = crop.w;
					int height = crop.h;
					int stride = checked(width * 4);
					var bytes = new byte[checked(stride * height)];
					fixed (byte* destination = bytes)
					{
						byte* source = (byte*)mapped.pData;
						for (int y = 0; y < height; y++)
							Buffer.MemoryCopy(source + (long)(crop.y + y) * mapped.RowPitch + (long)crop.x * 4,
									destination + (long)y * stride, stride, stride);
					}
					return (width, height, bytes, stride);
				}
				finally
				{
					var unmap = (delegate* unmanaged[Stdcall]<nint, nint, uint, void>)contextVtable[15];
					unmap(pContext, stagingTexture, 0);
				}
			}
			finally { Marshal.Release(stagingTexture); }
		}
		finally { Marshal.Release(texture); }
	}

	private static ulong Hash(byte[] bytes)
	{
		ulong hash = 14695981039346656037UL;
		foreach (byte value in bytes)
		{
			hash ^= value;
			hash *= 1099511628211UL;
		}
		return hash;
	}

	private static void SavePng(string path, int width, int height, byte[] bytes, int stride)
	{
		var source = BitmapSource.Create(width, height, 96, 96, PixelFormats.Bgra32, null, bytes, stride);
		source.Freeze();
		var encoder = new PngBitmapEncoder();
		encoder.Frames.Add(BitmapFrame.Create(source));
		using var stream = File.Create(path);
		encoder.Save(stream);
	}
}
