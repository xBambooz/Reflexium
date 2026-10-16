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
	private static final String PACK = "ComplementaryReimagined_r5.9.3";
	/** lib/antialiasing/jitter.glsl, hashed with Vitrail's reader convention (LF lines, trailing empty line kept). */
	private static final String JITTER = "lib/antialiasing/jitter.glsl";
	private static final String JITTER_SHA256 = "60872f102a808d3f5a6e8a5c0391bb1fb149bb08abb262047cb27e05577d877b";
	private static final String JITTER_LINE = "#if TAA_JITTER > 0";

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
		return isLoaded() && version().startsWith("1.12.0-snapshot");
	}

	public static boolean canUpscale() {
		return !isDrawingPack() || isSupportedVersion() && !broken;
	}

	public static String upscalingProblem() {
		return !isSupportedVersion() ? "Iris " + version() + " shader hooks are not supported" : "Iris shader integration failed (see log)";
	}

	public static boolean ownsTemporalSampling() { return temporalOwner && !broken; }
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

	private static void reloadPack() {
		if (reloadQueued) return;
		reloadQueued = true;
		// Iris reloads from its keybind during the tick; never tear the pipeline down inside a frame.
		Minecraft.getInstance().execute(() -> {
			reloadQueued = false;
			// Iris compiles its pack at startup, before any world frame reports it as drawing.
			if (currentPackName() == null) return;
			ComplementaryTemporalSource.reset();
			jitterRemoved = false;
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
		if (!ownsTemporalSampling() || source == null) return source;
		String name = currentPackName();
		if (!PACK.equals(name)) return source;
		String path = file.toString().replace('\\', '/');
		int root = path.lastIndexOf("shaders/");
		if (root < 0) return source;
		String rel = path.substring(root + "shaders/".length());
		List<String> lines = List.of(source.replace("\r\n", "\n").split("\n", -1));
		List<String> result = lines;
		if (rel.equals(JITTER)) {
			result = removeJitter(lines);
		} else if (DebugSettings.packResolveBypass()) {
			result = ComplementaryTemporalSource.adapt(name, rel, lines, true, DebugSettings.packPreFilters());
		}
		if (result == lines) return source;
		ReflexiumClient.LOGGER.info("Iris {}: Reflexium temporal edit applied to {}", name, rel);
		return String.join("\n", result);
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
				+ (temporalOwner ? jitterRemoved ? ", Reflexium jitter" : ", pack jitter retained" : "");
	}

	private static boolean lookUp() {
		if (lookedUp) return !broken;
		lookedUp = true;
		try {
			Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
			packInUse = iris.getMethod("isPackInUseQuick");
			config = iris.getMethod("getIrisConfig");
			packName = config.getReturnType().getMethod("getShaderPackName");
			return true;
		} catch (ReflectiveOperationException | LinkageError e) {
			broken = true;
			ReflexiumClient.LOGGER.warn("Iris reflection failed; shader pack integration disabled", e);
			return false;
		}
	}
}
