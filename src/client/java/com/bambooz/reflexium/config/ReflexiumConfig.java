package com.bambooz.reflexium.config;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.dlss.UpscaleQuality;
import com.bambooz.reflexium.dlss.Upscaler;
import com.bambooz.reflexium.dlss.DlssModel;
import com.bambooz.reflexium.dlss.FrameGeneration;
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
	public static final DlssModel DEFAULT_DLSS_MODEL = DlssModel.CNN;
	public static final FrameGeneration DEFAULT_FRAME_GENERATION = FrameGeneration.DLSS;
	private static final ReflexiumConfig INSTANCE = load();

	private volatile ReflexMode reflexMode = ReflexMode.ON;
	/** Shared with Sodium's reset-to-default; docs/SMART_REFLEX.md documents off. */
	public static final boolean DEFAULT_SMART_REFLEX = false;
	private volatile boolean smartReflex = DEFAULT_SMART_REFLEX;
	private volatile UpscaleQuality upscaleQuality = UpscaleQuality.QUALITY;
	private volatile Upscaler upscaler = Upscaler.AUTO;
	private volatile DlssModel dlssModel = DEFAULT_DLSS_MODEL;
	private volatile int fsrSharpness = 20;
	private volatile boolean postUpscaleParticles = true;
	private volatile FrameGeneration frameGeneration = DEFAULT_FRAME_GENERATION;
	private volatile boolean fgForceMultiplier;
 private volatile int frameGenerationMultiplier = 0; // 0 = Auto
	private volatile boolean betterF3ModulePlaced = false;
	/** Opt-in upper bounds for a shader pack's shadow map size and shadow distance while upscaling; 0 keeps the pack's own value. */
	private volatile int packShadowResolutionCap = 0;
	private volatile int packShadowDistanceCap = 0;

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

	public int packShadowResolutionCap() { return packShadowResolutionCap; }
	public int packShadowDistanceCap() { return packShadowDistanceCap; }
	public void setPackShadowCaps(int resolution, int distance) {
		packShadowResolutionCap = Math.clamp(resolution, 0, 8192);
		packShadowDistanceCap = Math.clamp(distance, 0, 1024);
		save();
	}
	public void setPackShadowResolutionCap(int value) {
		packShadowResolutionCap = Math.clamp(value, 0, 8192);
		save();
	}
	public void setPackShadowDistanceCap(int value) {
		packShadowDistanceCap = Math.clamp(value, 0, 1024);
		save();
	}

	public boolean smartReflex() { return smartReflex; }
	public boolean postUpscaleParticles() { return postUpscaleParticles; }
	public void setPostUpscaleParticles(boolean value) { postUpscaleParticles = value; save(); }
	public void setSmartReflex(boolean value) { smartReflex = value; save(); }

	/** DLSS Super Resolution preset. Needs the Vulkan renderer. */
	public UpscaleQuality upscaleQuality() {
		return upscaleQuality;
	}

	public void setUpscaleQuality(UpscaleQuality mode) {
		upscaleQuality = mode;
		save();
	}

	public Upscaler upscaler() { return upscaler; }
	public void setUpscaler(Upscaler value) {
		upscaler = value;
		if (value == Upscaler.FSR && (frameGeneration == FrameGeneration.DLSS || frameGeneration == FrameGeneration.DLSS_MULTI)) {
			frameGeneration = FrameGeneration.OFF;
		} else if (value == Upscaler.DLSS && frameGeneration == FrameGeneration.FSR) {
			frameGeneration = FrameGeneration.OFF;
		}
		save();
	}

	public DlssModel dlssModel() { return dlssModel; }
	public void setDlssModel(DlssModel value) { dlssModel = value; save(); }
	public int fsrSharpness() { return fsrSharpness; }
	public void setFsrSharpness(int value) { fsrSharpness = Math.clamp(value, 0, 100); save(); }
	public FrameGeneration frameGeneration() { return frameGeneration; }
	public void setFrameGeneration(FrameGeneration value) {
		if (upscaler == Upscaler.FSR && (value == FrameGeneration.DLSS || value == FrameGeneration.DLSS_MULTI)) {
			value = FrameGeneration.OFF;
		} else if (upscaler == Upscaler.DLSS && value == FrameGeneration.FSR) {
			value = FrameGeneration.OFF;
		}
		frameGeneration = value;
		if (value == FrameGeneration.DLSS) frameGenerationMultiplier = 0;
		else if (value == FrameGeneration.DLSS_MULTI && frameGenerationMultiplier != 0) frameGenerationMultiplier = Math.max(3, frameGenerationMultiplier);
		save();
	}

	public boolean fgForceMultiplier() { return fgForceMultiplier; }
 public void setFgForceMultiplier(boolean value) { fgForceMultiplier = value; save(); }
 public int frameGenerationMultiplier() { return frameGenerationMultiplier; }
	public void setFrameGenerationMultiplier(int value) {
		frameGenerationMultiplier = value == 0 ? 0 : Math.clamp(value, 2, 8);
		frameGeneration = frameGeneration.withMultiplier(frameGenerationMultiplier);
		save();
	}

	/**
	 * Preserves Auto (0), clamps fixed multipliers into 2..maximum once the driver reports its limit. Called on NGX init, because
	 * load runs before the GPU is known. 2 is the floor because it is DLSS FG, which every supported GPU runs.
	 */
	public void limitFrameGenerationMultiplier(int maximum) {
		int clamped = frameGenerationMultiplier == 0 ? 0 : Math.clamp(frameGenerationMultiplier, 2, Math.max(2, maximum));
		if (clamped != frameGenerationMultiplier) {
			frameGenerationMultiplier = clamped;
			frameGeneration = frameGeneration.withMultiplier(clamped);
			save();
		}
	}

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
		config.smartReflex = parseBoolean(props, "smart_reflex", config.smartReflex);
		config.postUpscaleParticles = parseBoolean(props, "post_upscale_particles", config.postUpscaleParticles);
		config.upscaleQuality = UpscaleQuality.parse(props.getProperty("upscale_quality", props.getProperty("dlss_mode")), config.upscaleQuality);
		config.upscaler = Upscaler.parse(props.getProperty("upscaler"),
				"off".equalsIgnoreCase(props.getProperty("dlss_mode")) ? Upscaler.OFF : config.upscaler);
		config.dlssModel = DlssModel.parse(props.getProperty("dlss_model"), config.dlssModel);
		config.frameGeneration = FrameGeneration.parse(props.getProperty("frame_generation"), config.frameGeneration);
		try { int multiplier = Integer.parseInt(props.getProperty("frame_generation_multiplier", "0")); config.frameGenerationMultiplier = multiplier == 0 ? 0 : Math.clamp(multiplier, 2, 8); }
		catch (NumberFormatException ignored) { /* Keep the default for malformed config values. */ }
		config.fgForceMultiplier = Boolean.parseBoolean(props.getProperty("fg_force_multiplier", "false"));
 config.frameGeneration = config.frameGeneration.withMultiplier(config.frameGenerationMultiplier);
		try { config.fsrSharpness = Math.clamp(Integer.parseInt(props.getProperty("fsr_sharpness", "20")), 0, 100); }
		catch (NumberFormatException ignored) { /* Keep the default for malformed config values. */ }
		config.betterF3ModulePlaced = parseBoolean(props, "betterf3_module_placed", config.betterF3ModulePlaced);
		try { config.packShadowResolutionCap = Math.clamp(Integer.parseInt(props.getProperty("pack_shadow_resolution_cap", "0")), 0, 8192); }
		catch (NumberFormatException ignored) { /* Keep the default for malformed config values. */ }
		try { config.packShadowDistanceCap = Math.clamp(Integer.parseInt(props.getProperty("pack_shadow_distance_cap", "0")), 0, 1024); }
		catch (NumberFormatException ignored) { /* Keep the default for malformed config values. */ }
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
		props.setProperty("smart_reflex", Boolean.toString(smartReflex));
		props.setProperty("post_upscale_particles", Boolean.toString(postUpscaleParticles));
		props.setProperty("upscale_quality", upscaleQuality.serializedName());
		props.setProperty("upscaler", upscaler.serializedName());
		props.setProperty("dlss_model", dlssModel.serializedName());
		props.setProperty("fsr_sharpness", Integer.toString(fsrSharpness));
		props.setProperty("frame_generation", frameGeneration.serializedName());
		props.setProperty("fg_force_multiplier", Boolean.toString(fgForceMultiplier));
 props.setProperty("frame_generation_multiplier", Integer.toString(frameGenerationMultiplier));
		props.setProperty("betterf3_module_placed", Boolean.toString(betterF3ModulePlaced));
		props.setProperty("pack_shadow_resolution_cap", Integer.toString(packShadowResolutionCap));
		props.setProperty("pack_shadow_distance_cap", Integer.toString(packShadowDistanceCap));
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
