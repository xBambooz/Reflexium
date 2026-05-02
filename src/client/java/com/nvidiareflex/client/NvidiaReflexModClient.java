package com.nvidiareflex.client;

import com.nvidiareflex.NvidiaReflexMod;
import net.fabricmc.api.ClientModInitializer;

public class NvidiaReflexModClient implements ClientModInitializer {

	private static final ReflexConfig config = new ReflexConfig();
	private static boolean modEnabled = false;

	@Override
	public void onInitializeClient() {
		// Gate 1: Windows only
		if (!GpuDetector.isWindows()) {
			NvidiaReflexMod.LOGGER.info("Nvidia Reflex Mod disabled: not running on Windows");
			return;
		}

		modEnabled = true;
		config.load();

		NvidiaReflexMod.LOGGER.info(
			"Nvidia Reflex Mod initialized (saved mode: {}). Support is validated against the active Vulkan device at runtime.",
			config.getMode().getDisplayName()
		);
	}

	public static boolean isModEnabled() {
		return modEnabled;
	}

	public static ReflexConfig getConfig() {
		return config;
	}
}
