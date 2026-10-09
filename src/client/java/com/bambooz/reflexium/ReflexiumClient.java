package com.bambooz.reflexium;

import com.bambooz.reflexium.compat.ApertureCompat;
import com.bambooz.reflexium.config.ReflexiumConfig;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

public final class ReflexiumClient implements ClientModInitializer {
	public static final String MOD_ID = "reflexium";
	public static final Logger LOGGER = LoggerFactory.getLogger("Reflexium");

	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

	@Override
	public void onInitializeClient() {
		ReflexiumConfig config = ReflexiumConfig.get();
		LOGGER.info("Reflexium loaded (Reflex: {}, frame limiter: {}, DLSS detection: {}, Aperture: {})",
				config.reflexMode().displayName(),
				config.reflexFrameLimiter() ? "Reflex" : "vanilla",
				config.dlssDetection() ? "on" : "off",
				ApertureCompat.status());
	}

	/** NVIDIA Reflex and NGX are only shipped for Windows drivers in this mod. */
	public static boolean isSupportedPlatform() {
		return WINDOWS;
	}
}
