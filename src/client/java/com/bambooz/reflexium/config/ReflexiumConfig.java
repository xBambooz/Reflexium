package com.bambooz.reflexium.config;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.reflex.ReflexMode;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * config/reflexium.properties. Reads the pre-rename nvidia-reflex-mod.properties once if present.
 */
public final class ReflexiumConfig {
	private static final Path DIR = FabricLoader.getInstance().getConfigDir();
	private static final Path PATH = DIR.resolve("reflexium.properties");
	private static final Path LEGACY_PATH = DIR.resolve("nvidia-reflex-mod.properties");
	private static final ReflexiumConfig INSTANCE = load();

	private volatile ReflexMode reflexMode = ReflexMode.ON;
	private volatile boolean reflexFrameLimiter = true;
	private volatile boolean dlssDetection = true;

	private ReflexiumConfig() {
	}

	public static ReflexiumConfig get() {
		return INSTANCE;
	}

	public ReflexMode reflexMode() {
		return reflexMode;
	}

	public void setReflexMode(ReflexMode mode) {
		reflexMode = mode;
		save();
	}

	/** Replace vanilla's post-present sleep with the driver-paced Reflex limiter while Reflex is on. */
	public boolean reflexFrameLimiter() {
		return reflexFrameLimiter;
	}

	public void setReflexFrameLimiter(boolean enabled) {
		reflexFrameLimiter = enabled;
		save();
	}

	/** Enable NGX device extensions and probe DLSS feature support. Takes effect on the next game start. */
	public boolean dlssDetection() {
		return dlssDetection;
	}

	public void setDlssDetection(boolean enabled) {
		dlssDetection = enabled;
		save();
	}

	private static ReflexiumConfig load() {
		ReflexiumConfig config = new ReflexiumConfig();
		Path source = Files.exists(PATH) ? PATH : Files.exists(LEGACY_PATH) ? LEGACY_PATH : null;
		if (source == null) {
			config.save();
			return config;
		}

		Properties props = new Properties();
		try (Reader reader = Files.newBufferedReader(source)) {
			props.load(reader);
		} catch (IOException e) {
			ReflexiumClient.LOGGER.warn("Could not read {}, using defaults", source, e);
			return config;
		}

		config.reflexMode = ReflexMode.parse(props.getProperty("reflex_mode", props.getProperty("mode")), config.reflexMode);
		config.reflexFrameLimiter = parseBoolean(props, "reflex_frame_limiter", config.reflexFrameLimiter);
		config.dlssDetection = parseBoolean(props, "dlss_detection", config.dlssDetection);
		if (source == LEGACY_PATH) {
			config.save();
			ReflexiumClient.LOGGER.info("Migrated {} to {}", LEGACY_PATH.getFileName(), PATH.getFileName());
		}
		return config;
	}

	private static boolean parseBoolean(Properties props, String key, boolean fallback) {
		String value = props.getProperty(key);
		return value == null ? fallback : Boolean.parseBoolean(value.trim());
	}

	private synchronized void save() {
		Properties props = new Properties();
		props.setProperty("reflex_mode", reflexMode.serializedName());
		props.setProperty("reflex_frame_limiter", Boolean.toString(reflexFrameLimiter));
		props.setProperty("dlss_detection", Boolean.toString(dlssDetection));
		try {
			Files.createDirectories(DIR);
			Path temp = PATH.resolveSibling(PATH.getFileName() + ".tmp");
			try (Writer writer = Files.newBufferedWriter(temp)) {
				props.store(writer, "Reflexium. reflex_mode: off | on | on_boost");
			}
			Files.move(temp, PATH, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			ReflexiumClient.LOGGER.warn("Could not save {}", PATH, e);
		}
	}
}
