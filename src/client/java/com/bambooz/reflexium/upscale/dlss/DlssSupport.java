package com.bambooz.reflexium.upscale.dlss;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.vulkan.VulkanSession;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Readiness check for DLSS. It deliberately makes no NGX calls: calling the driver's _nvngx.dll
 * exports directly (without NVIDIA's static nvsdk_ngx library in between) crashes inside the driver,
 * both for NVSDK_NGX_VULKAN_Init_ProjectID and GetFeatureDeviceExtensionRequirements. Running DLSS
 * needs a native bridge linked against that library; see docs/DLSS.md.
 */
public final class DlssSupport {
	/**
	 * Device extensions DLSS needs on Vulkan (NGX programming guide). Enabled up front when the GPU
	 * supports them, so a later native DLSS bridge can use Minecraft's device as-is.
	 */
	public static final Set<String> DEVICE_EXTENSIONS = Set.of(
			"VK_NVX_binary_import", "VK_NVX_image_view_handle", "VK_KHR_push_descriptor");

	private static final String[] FEATURE_DLLS = {"nvngx_dlss.dll", "nvngx_dlssd.dll", "nvngx_dlssg.dll"};

	private static volatile DlssStatus status = DlssStatus.pending();
	/** True when probe found no nvngx_dlss.dll and started the automatic download. */
	private static volatile boolean awaitingRuntime;

	private DlssSupport() {
	}

	public static DlssStatus status() {
		if (awaitingRuntime) {
			String runtime = DlssRuntimeInstaller.state().message();
			if (!runtime.isEmpty()) {
				return DlssStatus.unavailable(runtime);
			}
		}
		return status;
	}

	/** Where players put NVIDIA's nvngx_dlss*.dll files (not shipped with the mod, same as Radiance). */
	public static Path featureFolder() {
		return FabricLoader.getInstance().getGameDir().resolve("reflexium");
	}

	public static void probe(VulkanSession session) {
		awaitingRuntime = false;
		if (!session.isNvidia()) {
			status = DlssStatus.unavailable("not an NVIDIA GPU");
		} else if (locateNgxCore().isEmpty()) {
			status = DlssStatus.unavailable("driver has no NGX core");
		} else {
			Set<String> found = new LinkedHashSet<>();
			for (String dll : FEATURE_DLLS) {
				if (Files.isRegularFile(featureFolder().resolve(dll))) {
					found.add(dll);
				}
			}
			boolean extensions = session.ngxExtensions().containsAll(Set.of("VK_NVX_binary_import", "VK_NVX_image_view_handle"));
			awaitingRuntime = !found.contains(DlssRuntimeInstaller.FILE_NAME);
			if (awaitingRuntime || !found.contains(DlssRuntimeInstaller.FG_FILE_NAME)) {
				DlssRuntimeInstaller.ensureInstalledAsync();
			}
			status = DlssStatus.ready(extensions, found);
		}
		ReflexiumClient.LOGGER.info("DLSS: {}", status().summary());
	}

	/** Same lookup order as NVIDIA's NGX loader (nvsdk_ngx_loader.h). */
	static Optional<Path> locateNgxCore() {
		String[][] keys = {
				{"SYSTEM\\CurrentControlSet\\Services\\nvlddmkm\\Parameters\\NGXCore", "NGXPath"},
				{"SYSTEM\\CurrentControlSet\\Services\\nvlddmkm\\NGXCore", "NGXPath"},
				{"SOFTWARE\\NVIDIA Corporation\\Global\\NGXCore", "FullPath"},
		};
		for (String[] key : keys) {
			try {
				if (Advapi32Util.registryValueExists(WinReg.HKEY_LOCAL_MACHINE, key[0], key[1])) {
					Path dll = Path.of(Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, key[0], key[1]), "_nvngx.dll");
					if (Files.isRegularFile(dll)) {
						return Optional.of(dll);
					}
				}
			} catch (RuntimeException ignored) {
				// Missing key or access denied: try the next location.
			}
		}
		return Optional.empty();
	}
}
