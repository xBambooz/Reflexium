package com.bambooz.reflexium.compat;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.debug.DebugSettings;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The experimental Iris Vulkan build draws OptiFine-format packs on Minecraft's own RenderPearl device, into
 * the main render target Reflexium sizes, with gbuffer projections taken from the jittered level matrix. What
 * it needs is one temporal owner: the reviewed pack's own jitter and full-scene resolve are removed from the
 * source while upscaling is selected, and its hand gets Reflexium's jitter and coverage mask
 * (mixin.compat.iris). Iris implementation classes are not a stable public API.
 */
public final class IrisCompat {
	private static final String MOD_ID = "iris";
	public static final String SUPPORTED = "1.12.0-snapshot+mc26.3-local";
	private static final String PACK = "ComplementaryReimagined_r5.9.3";
	/** lib/antialiasing/jitter.glsl, hashed with Vitrail's reader convention (LF lines, trailing empty line kept). */
	private static final String JITTER = "lib/antialiasing/jitter.glsl";
	private static final String JITTER_SHA256 = "60872f102a808d3f5a6e8a5c0391bb1fb149bb08abb262047cb27e05577d877b";
	private static final String JITTER_LINE = "#if TAA_JITTER > 0";
	/**
	 * BSL's own "retro filter removes AA" block undefines both TAA and FXAA. Every BSL projection jitter call and
	 * the composite6/composite7 resolves sit behind those defines, so enabling the block leaves Reflexium as the
	 * only jitter and temporal resolve. Option parsing still sees the original defines, so saved options are kept.
	 */
	private static final String BSL = "BSL_v10.1.8";
	private static final String BSL_SETTINGS = "lib/settings.glsl";
	private static final String BSL_SETTINGS_SHA256 = "808a7fb287dc081d889b3a8abb36c39590c12bff53bd751e839737e3a1cd13aa";
	private static final String BSL_AA_LINE = "#ifdef RETRO_FILTER";
	private static volatile boolean bslAaRemoved;
	private static final java.util.Map<String, String> BSL_SAMPLING = java.util.Map.of(
			"lib/atmospherics/clouds.glsl", "6195aa898de9c38fbb470ca69b87af5399aedb76bc482ce500143a019e9f96ac",
			"lib/atmospherics/lightShafts.glsl", "e0cb5aaae178edd574d4c6d731c796c94e61ac4bcd9788234bd3c7472860baf2",
			"lib/lighting/ambientOcclusion.glsl", "7fa271b2780efef4fc176a3b4a7e2c466ec087b5a3d2c27fe0c025222a14751b",
			"lib/reflections/raytrace.glsl", "f1ddde3723110de60fd5f30d3bee60b2f48528fc18b94c3861bf85f199094d14",
			"program/deferred1.glsl", "5a6e17ed30c584acc80fa9547a471d8eed138cc62c20173ff1eac1310f5f75c2");
	/**
	 * Pack options that select the pack's own temporal AA, post-process AA or pre-upscale sharpening. Upscaling replaces
	 * all three: TAA would accumulate before DLSS/FSR, FXAA blurs its jittered input (NVIDIA DLSS guide 3.1), and a
	 * render-size sharpen amplifies aliasing the upscaler then has to resolve; Reflexium sharpens after reconstruction.
	 * Matched by option name, so any pack exposing them in its menu needs no Reflexium change.
	 */
	private static final java.util.Set<String> TEMPORAL_OPTIONS = java.util.Set.of("TAA", "TAA_ENABLED", "ENABLE_TAA",
			"TAA_ENABLE", "USE_TAA", "TEMPORAL_AA", "TEMPORAL_ANTIALIASING", "TEMPORAL_ANTI_ALIASING", "TAAU", "TAA_UPSCALING",
			"FXAA", "FXAA_ENABLED", "ENABLE_FXAA", "USE_FXAA", "IMAGE_SHARPENING", "CONTRAST_ADAPTATIVE_SHARPENING",
			"CONTRAST_ADAPTIVE_SHARPENING");
	/** Value options and their off value (Complementary family: TAA_DEFINE 0, FXAA_DEFINE -1); used only if the pack lists it. */
	private static final java.util.Map<String, List<String>> TEMPORAL_VALUE_OPTIONS = java.util.Map.of("TAA_DEFINE", List.of("0"),
			"FXAA_DEFINE", List.of("-1", "0"), "IMAGE_SHARPENING", List.of("0"),
			"SHARPENING", List.of("0", "0.0", "0.00"), "SHARPEN", List.of("0"));
	private static volatile List<String> optionsOverridden = List.of();
	/** One of the overridden options was the pack's temporal AA itself, not only FXAA or sharpening. */
	private static volatile boolean optionsTaaRemoved;

	private static Method packInUse;
	private static Method config;
	private static Method packName;
	private static boolean lookedUp;
	private static boolean broken;
	private static volatile boolean temporalOwner;
	private static volatile boolean jitterRemoved;
	private static boolean reloadQueued;

	private IrisCompat() {
	}

	public static boolean isLoaded() {
		return FabricLoader.getInstance().isModLoaded(MOD_ID);
	}

	public static String version() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(mod -> mod.getMetadata().getVersion().getFriendlyString())
				.orElse("not installed");
	}

	/** The inspected experimental Vulkan build. Stable OpenGL Iris never reaches Reflexium's Vulkan path. */
	public static boolean isSupportedVersion() {
		// Exact: every hook below was checked against this artifact. A newer snapshot reports unsupported.
		return isLoaded() && version().equals(SUPPORTED);
	}

	public static boolean canUpscale() {
		return !isDrawingPack() || isSupportedVersion() && !broken;
	}

	public static String upscalingProblem() {
		return !isSupportedVersion() ? "Iris " + version() + " shader hooks are not supported" : "Iris shader integration failed (see log)";
	}

	public static boolean ownsTemporalSampling() { return temporalOwner && !broken; }
	private static volatile long epoch;
	/** Changes on every Iris pack load (reload, pack switch, shaders toggle); temporal history must restart. */
	public static long epoch() { return epoch; }
	/** Iris.loadShaderpack HEAD: forget the previous pack's evidence before its sources are read again. */
	public static void onPackLoading() {
		epoch++;
		ComplementaryTemporalSource.reset();
		jitterRemoved = false;
		bslAaRemoved = false;
		optionsOverridden = List.of();
		optionsTaaRemoved = false;
	}
	/** Reflexium removed this pack's temporal filters; other packs keep their own TAA and jitter. */
	public static boolean temporalVerified() {
		return jitterRemoved && ComplementaryTemporalSource.applied() || bslAaRemoved || optionsTaaRemoved;
	}
	/** The reviewed pack's projection jitter is out of the loaded source; Reflexium's offset is the only one. */
	public static boolean packJitterRemoved() { return jitterRemoved; }

	public static boolean isDrawingPack() {
		if (!isLoaded()) return false;
		if (!lookUp()) return true;
		try {
			return (boolean) packInUse.invoke(null);
		} catch (ReflectiveOperationException | RuntimeException e) {
			broken = true;
			return true;
		}
	}

	/** Called before the frame decision. A change of owner rebuilds the pack from source on the next task. */
	public static void configureUpscaling(boolean enabled) {
		if (!isSupportedVersion() || broken || temporalOwner == enabled) return;
		temporalOwner = enabled;
		reloadPack();
	}

	/** Dev harness toggle for the supported Iris implementation. */
	public static void debugSetPackEnabled(boolean enabled) {
		if (!isSupportedVersion() || !lookUp()) throw new IllegalStateException("Supported Iris is not available");
		try {
			Object options = config.invoke(null);
			options.getClass().getMethod("setShadersEnabled", boolean.class).invoke(options, enabled);
			// reload() reloads the file too, matching Iris.toggleShaders' set/save/reload sequence.
			options.getClass().getMethod("save").invoke(options);
			reloadPack();
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Could not toggle Iris shaders", e);
		}
	}

	private static void reloadPack() {
		if (reloadQueued) return;
		reloadQueued = true;
		// Iris reloads from its keybind during the tick; never tear the pipeline down inside a frame.
		// execute() would run inline on the render thread, inside the frame; schedule() always queues.
		Minecraft.getInstance().schedule(() -> {
			reloadQueued = false;
			// Iris compiles its pack at startup, before any world frame reports it as drawing.
			if (currentPackName() == null) return;
			try {
				Class.forName("net.irisshaders.iris.Iris").getMethod("reload").invoke(null);
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				broken = true;
				ReflexiumClient.LOGGER.error("Iris shader pack could not reload; upscaling is paused", e);
			}
		});
	}

	/** IncludeGraph.readFile RETURN: the same exact-hash source edits Vitrail receives, for the reviewed pack only. */
	public static String adapt(java.nio.file.Path file, String source) {
		return adapt(currentPackName(), file, source);
	}

	static String adapt(String name, java.nio.file.Path file, String source) {
		if (!ownsTemporalSampling() || source == null) return source;
		if (!PACK.equals(name) && !BSL.equals(name)) return source;
		String path = file.toString().replace('\\', '/');
		int root = path.lastIndexOf("shaders/");
		if (root < 0) return source;
		String rel = path.substring(root + "shaders/".length());
		List<String> lines = List.of(source.replace("\r\n", "\n").split("\n", -1));
		List<String> result = lines;
		if (BSL.equals(name)) {
			if (rel.equals(BSL_SETTINGS)) result = removeBslAntiAliasing(lines);
			else if (BSL_SAMPLING.getOrDefault(rel, "").equals(ComplementaryTemporalSource.sha256(lines))) {
				// Removing the pack resolve must not freeze its Bayer ray-march sampling into the SR input.
				// Change only sampling guards, never geometry jitter or the pack's temporal resolve.
				String edited = String.join("\n", lines).replace("#ifdef TAA\n\t#if TAA_MODE == 0\n\tdither =",
						"#if 1 // Reflexium: temporal effect sampling with external reconstruction\n\t#if TAA_MODE == 0\n\tdither =");
				result = List.of(edited.split("\n", -1));
			}
		} else if (rel.equals(JITTER)) {
			result = removeJitter(lines);
		} else if (DebugSettings.packResolveBypass()) {
			result = ComplementaryTemporalSource.adapt(name, rel, lines, true, DebugSettings.packPreFilters());
		}
		if (result == lines) return source;
		ReflexiumClient.LOGGER.info("Iris {}: Reflexium temporal edit applied to {}", name, rel);
		return String.join("\n", result);
	}

	/**
	 * ShaderPackOptions construction, after the option set is parsed: switch the pack's own temporal AA toggles off
	 * while Reflexium owns temporal sampling. The returned copy feeds only this compile; Iris saves its option file
	 * from the user's properties, so saved settings are kept. The reviewed packs keep their exact source edits.
	 */
	public static java.util.Map<String, String> temporalOptionOverrides(Object packOptions, java.util.Map<String, String> values) {
		if (!ownsTemporalSampling() || values == null) return values;
		String name = currentPackName();
		if (PACK.equals(name) || BSL.equals(name)) return values;
		try {
			Object set = packOptions.getClass().getMethod("getOptionSet").invoke(packOptions);
			java.util.Map<?, ?> booleans = (java.util.Map<?, ?>) set.getClass().getMethod("getBooleanOptions").invoke(set);
			java.util.Map<String, String> result = new java.util.HashMap<>(values);
			List<String> overridden = new ArrayList<>();
			for (Object key : booleans.keySet()) {
				String option = (String) key;
				if (!TEMPORAL_OPTIONS.contains(option.toUpperCase(java.util.Locale.ROOT))) continue;
				result.put(option, "false");
				overridden.add(option);
			}
			java.util.Map<?, ?> strings = (java.util.Map<?, ?>) set.getClass().getMethod("getStringOptions").invoke(set);
			for (java.util.Map.Entry<?, ?> entry : strings.entrySet()) {
				String option = (String) entry.getKey();
				List<String> offValues = TEMPORAL_VALUE_OPTIONS.get(option.toUpperCase(java.util.Locale.ROOT));
				if (offValues == null) continue;
				Object merged = entry.getValue();
				Object stringOption = merged.getClass().getMethod("getOption").invoke(merged);
				List<?> allowed = (List<?>) stringOption.getClass().getMethod("getAllowedValues").invoke(stringOption);
				for (String off : offValues) {
					if (!allowed.contains(off)) continue;
					result.put(option, off);
					overridden.add(option + "=" + off);
					break;
				}
			}
			if (overridden.isEmpty()) return values;
			optionsOverridden = List.copyOf(overridden);
			optionsTaaRemoved = overridden.stream().anyMatch(option -> option.toUpperCase(java.util.Locale.ROOT).matches(".*(TAA|TEMPORAL).*"));
			ReflexiumClient.LOGGER.info("Iris {}: pack options {} switched off while upscaling (saved options unchanged)", name, overridden);
			return result;
		} catch (ReflectiveOperationException | RuntimeException e) {
			ReflexiumClient.LOGGER.warn("Iris pack option set not readable; pack temporal AA stays as configured", e);
			return values;
		}
	}

	private static List<String> removeJitter(List<String> lines) {
		if (!JITTER_SHA256.equals(ComplementaryTemporalSource.sha256(lines))) return lines;
		List<String> result = new ArrayList<>(lines);
		for (int i = 0; i < result.size(); i++) {
			String line = result.get(i);
			if (!line.strip().equals(JITTER_LINE)) continue;
			result.set(i, line.substring(0, line.indexOf(JITTER_LINE)) + "#if 0 // Reflexium: projection jitter has one owner");
			jitterRemoved = true;
			return List.copyOf(result);
		}
		return lines;
	}

	private static List<String> removeBslAntiAliasing(List<String> lines) {
		if (!BSL_SETTINGS_SHA256.equals(ComplementaryTemporalSource.sha256(lines))) return lines;
		List<String> result = new ArrayList<>(lines);
		for (int i = 0; i < result.size(); i++) {
			String line = result.get(i);
			if (!line.strip().equals(BSL_AA_LINE)) continue;
			// SHARPEN_ENABLED is decided earlier from the original defines. With TAA and FXAA gone, final.glsl
			// then computes 0.0625 * SHARPEN = -0.0625 for the automatic -1 setting: a blur of the upscaler
			// input. Sharpening belongs after reconstruction (UpscalingRenderer's DLSS sharpen, FSR's RCAS).
			String indent = line.substring(0, line.indexOf(BSL_AA_LINE));
			result.set(i, indent + "#undef SHARPEN_ENABLED // Reflexium: sharpen after upscaling, never before\n"
					+ indent + "#if 1 // Reflexium: one jitter and temporal resolve owner");
			bslAaRemoved = true;
			return List.copyOf(result);
		}
		return lines;
	}

	private static java.lang.reflect.Field depthVersion, colorVersion;
	private static boolean versionsLookedUp;

	/**
	 * Iris re-reads the main depth texture only when the target's buffer version changes, not on a size change,
	 * so a swapped-in render-size depth would stay paired with display-size history. Tie each version to the
	 * attached texture's identity: it changes exactly when the attachment does and stays stable across frames.
	 */
	public static void syncTargetVersions(com.mojang.blaze3d.pipeline.RenderTarget target) {
		if (!isLoaded()) return;
		if (!versionsLookedUp) {
			versionsLookedUp = true;
			try {
				depthVersion = com.mojang.blaze3d.pipeline.RenderTarget.class.getDeclaredField("iris$depthBufferVersion");
				colorVersion = com.mojang.blaze3d.pipeline.RenderTarget.class.getDeclaredField("iris$colorBufferVersion");
				depthVersion.setAccessible(true);
				colorVersion.setAccessible(true);
			} catch (ReflectiveOperationException | RuntimeException e) {
				depthVersion = colorVersion = null;
				ReflexiumClient.LOGGER.warn("Iris render target versions not found; render-size targets may be stale", e);
			}
		}
		if (depthVersion == null) return;
		try {
			depthVersion.setInt(target, System.identityHashCode(target.getDepthTexture()));
			colorVersion.setInt(target, System.identityHashCode(target.getColorTexture()));
		} catch (IllegalAccessException ignored) { /* setAccessible succeeded above */ }
	}

	/** The selected pack without its archive extension, or null. */
	public static String currentPackName() {
		if (!lookUp()) return null;
		try {
			Optional<?> name = (Optional<?>) packName.invoke(config.invoke(null));
			if (name.isEmpty()) return null;
			String value = (String) name.get();
			return value.endsWith(".zip") ? value.substring(0, value.length() - 4) : value;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	public static String status() {
		if (!isLoaded()) return version();
		if (!isDrawingPack()) return version() + ", no shader pack drawing";
		String name = currentPackName();
		return version() + ", " + (name == null ? "unknown pack" : name)
				+ (temporalOwner ? temporalVerified() ? ", Reflexium jitter" : ", pack TAA not managed (unverified pack)" : "")
				+ (temporalOwner && !optionsOverridden.isEmpty() ? " (pack options off: " + String.join(", ", optionsOverridden) + ")" : "");
	}

	private static boolean lookUp() {
		if (lookedUp) return !broken;
		lookedUp = true;
		try {
			Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
			packInUse = iris.getMethod("isPackInUseQuick");
			config = iris.getMethod("getIrisConfig");
			packName = config.getReturnType().getMethod("getShaderPackName");
			// Without these, a swapped render-size depth stays paired with display-size Iris targets.
			com.mojang.blaze3d.pipeline.RenderTarget.class.getDeclaredField("iris$depthBufferVersion");
			com.mojang.blaze3d.pipeline.RenderTarget.class.getDeclaredField("iris$colorBufferVersion");
			return true;
		} catch (ReflectiveOperationException | LinkageError e) {
			broken = true;
			ReflexiumClient.LOGGER.warn("Iris reflection failed; shader pack integration disabled", e);
			return false;
		}
	}
}
