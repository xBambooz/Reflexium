package com.nvidiareflex;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NvidiaReflexMod implements ModInitializer {
	public static final String MOD_ID = "nvidia-reflex-mod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Client-only mod — all logic is in the client entrypoint
		LOGGER.info("Nvidia Reflex Mod loaded (common init)");
	}
}
