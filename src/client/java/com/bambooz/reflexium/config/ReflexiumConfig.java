package com.bambooz.reflexium.config;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.dlss.UpscaleQuality;
import com.bambooz.reflexium.dlss.Upscaler;
import com.bambooz.reflexium.dlss.DlssModel;
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
	private volatile boolean dlssDetection = true;
	private volatile UpscaleQuality upscaleQuality = UpscaleQuality.QUALITY;
	private volatile Upscaler upscaler = Upscaler.AUTO;
	private volatile DlssModel dlssModel = DlssModel.AUTO;
	private volatile int fsrSharpness = 20;
	private volatile boolean betterF3ModulePlaced = false;

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

	/** Enable NGX device extensions and probe DLSS feature support. Takes effect on the next game start. */
	public boolean dlssDetection() {
		return dlssDetection;
	}

	public void setDlssDetection(boolean enabled) {
		dlssDetection = enabled;
		save();
	}

	/** DLSS Super Resolution preset. Needs DLSS detection (device extensions) and the Vulkan renderer. */
	public UpscaleQuality upscaleQuality() {
		return upscaleQuality;
	}

	public void setUpscaleQuality(UpscaleQuality mode) {
		upscaleQuality = mode;
		save();
	}

	public Upscaler upscaler() { return upscaler; }
	public void setUpscaler(Upscaler value) { upscaler = value; save(); }
	public DlssModel dlssModel() { return dlssModel; }
	public void setDlssModel(DlssModel value) { dlssModel = value; save(); }
	public int fsrSharpness() { return fsrSharpness; }
	public void setFsrSharpness(int value) { fsrSharpness = Math.clamp(value, 0, 100); save(); }

	/** Reflexium's BetterF3 module was added to the BetterF3 layout once; from then on the player's layout wins. */
	public boolean betterF3ModulePlaced() {
		return betterF3ModulePlaced;
	}

	public void setBetterF3ModulePlaced(boolean placed) {
		betterF3ModulePlaced = placed;
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

		// reflex_frame_limiter (removed) is not read, so it is dropped from the file on the next save.
		config.reflexMode = ReflexMode.parse(props.getProperty("reflex_mode", props.getProperty("mode")), config.reflexMode);
		config.dlssDetection = parseBoolean(props, "dlss_detection", config.dlssDetection);
		config.upscaleQuality = UpscaleQuality.parse(props.getProperty("upscale_quality", props.getProperty("dlss_mode")), config.upscaleQuality);
		config.upscaler = Upscaler.parse(props.getProperty("upscaler"),
				"off".equalsIgnoreCase(props.getProperty("dlss_mode")) ? Upscaler.OFF : config.upscaler);
		config.dlssModel = DlssModel.parse(props.getProperty("dlss_model"), config.dlssModel);
		try { config.fsrSharpness = Math.clamp(Integer.parseInt(props.getProperty("fsr_sharpness", "20")), 0, 100); }
		catch (NumberFormatException ignored) { /* Keep the default for malformed config values. */ }
		config.betterF3ModulePlaced = parseBoolean(props, "betterf3_module_placed", config.betterF3ModulePlaced);
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
		props.setProperty("dlss_detection", Boolean.toString(dlssDetection));
		props.setProperty("upscale_quality", upscaleQuality.serializedName());
		props.setProperty("upscaler", upscaler.serializedName());
		props.setProperty("dlss_model", dlssModel.serializedName());
		props.setProperty("fsr_sharpness", Integer.toString(fsrSharpness));
		props.setProperty("betterf3_module_placed", Boolean.toString(betterF3ModulePlaced));
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
