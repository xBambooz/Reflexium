package com.nvidiareflex.client;

import com.nvidiareflex.NvidiaReflexMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Persists the user's Reflex mode choice across game sessions.
 * Config file: config/nvidia-reflex-mod.properties
 */
public class ReflexConfig {
	private static final Path CONFIG_PATH = FabricLoader.getInstance()
			.getConfigDir().resolve("nvidia-reflex-mod.properties");

	private ReflexMode mode = ReflexMode.OFF;

	public ReflexMode getMode() {
		return mode;
	}

	public void setMode(ReflexMode mode) {
		this.mode = mode;
		save();
	}

	public void load() {
		if (!Files.exists(CONFIG_PATH)) {
			return;
		}
		try (var reader = Files.newBufferedReader(CONFIG_PATH)) {
			Properties props = new Properties();
			props.load(reader);
			mode = ReflexMode.fromString(props.getProperty("mode", "OFF"));
		} catch (IOException e) {
			NvidiaReflexMod.LOGGER.warn("Failed to load Reflex config", e);
		}
	}

	public void save() {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			Properties props = new Properties();
			props.setProperty("mode", mode.name());
			try (var writer = Files.newBufferedWriter(CONFIG_PATH)) {
				props.store(writer, "Nvidia Reflex Mod Configuration");
			}
		} catch (IOException e) {
			NvidiaReflexMod.LOGGER.warn("Failed to save Reflex config", e);
		}
	}
}
