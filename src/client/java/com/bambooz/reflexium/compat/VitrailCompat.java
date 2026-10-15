package com.bambooz.reflexium.compat;

import com.bambooz.reflexium.ReflexiumClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Vitrail runs OptiFine-format shader packs on Minecraft's own Vulkan device, queue and swapchain, so
 * Reflex needs no special handling under it. Upscaling needs one owner of render scale and temporal
 * sampling; the optional mixins preserve Vitrail's matrices and hand while this class handles the
 * known pack's TAA setting in memory. These implementation classes are not a stable public API.
 */
public final class VitrailCompat {
	private static final String MOD_ID = "vitrail";

	private static Method drawingPack;
	private static Method session;
	private static Method packFileName;
	private static boolean lookedUp;
	private static boolean broken;
	private static boolean temporalOwner;
	private static boolean integrationBroken;

	private VitrailCompat() {
	}

	public static boolean isLoaded() {
		return FabricLoader.getInstance().isModLoaded(MOD_ID);
	}

	public static String version() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(mod -> mod.getMetadata().getVersion().getFriendlyString())
				.orElse("not installed");
	}

	/** Only versions whose implementation hooks have been source-reviewed are enabled. */
	public static boolean isSupportedVersion() {
		String version = version();
		return isLoaded() && version.startsWith("0.12.");
	}

	public static boolean canUpscale() {
		return !isDrawingPack() || isSupportedVersion() && !broken && !integrationBroken;
	}

	public static boolean ownsTemporalSampling() { return temporalOwner && !integrationBroken; }

	public static String upscalingProblem() {
		return !isSupportedVersion() ? "Vitrail " + version() + " shader hooks are not supported"
				: "Vitrail shader integration failed (see log)";
	}

	/** Called before the frame decision, based on the selected available upscaler, not world visibility. */
	public static void configureUpscaling(boolean enabled) {
		if (!isSupportedVersion() || integrationBroken || temporalOwner == enabled) {
			return;
		}
		temporalOwner = enabled;
		ComplementaryTemporalSource.reset();
		try {
			if (lookUp() && ((Optional<?>) session.invoke(null)).isPresent()) {
				// Vitrail's flattened-unit cache keys exclude our external source policy.
				Class.forName("dev.vitrail.pack.source.OpenedPack").getMethod("forgetKept").invoke(null);
				Class.forName("dev.vitrail.render.PackChoice").getMethod("reload", java.nio.file.Path.class)
						.invoke(null, Minecraft.getInstance().gameDirectory.toPath());
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			integrationBroken = true;
			ReflexiumClient.LOGGER.error("Vitrail temporal settings could not reload; upscaling is paused", e);
		}
	}

	/** PackSession.read RETURN: rebuild only the forced layer, leaving every saved option on disk alone. */
	public static Object withTemporalOwner(Object pack) {
		ComplementaryTemporalSource.reset();
		if (!temporalOwner || integrationBroken) {
			return pack;
		}
		try {
			Class<?> type = pack.getClass();
			String name = (String) type.getMethod("packFileName").invoke(pack);
			if (!name.equals("ComplementaryReimagined_r5.9.3.zip")) return pack;
			Set<?> declared = (Set<?>) type.getMethod("declared").invoke(pack);
			if (!declared.contains("TAA_DEFINE") || !declared.contains("TAA_JITTER")) {
				return pack;
			}
			Map<?, ?> original = (Map<?, ?>) type.getMethod("forced").invoke(pack);
			Map<Object, Object> forced = new LinkedHashMap<>(original);
			Method optionValueOf = Class.forName("dev.vitrail.pack.option.OptionValue").getMethod("of", String.class);
			forced.put("TAA_DEFINE", optionValueOf.invoke(null, com.bambooz.reflexium.debug.DebugSettings.packTaaRetained() ? "1" : "0"));
			forced.put("TAA_JITTER", optionValueOf.invoke(null, "0"));
			RecordComponent[] components = type.getRecordComponents();
			Class<?>[] types = new Class<?>[components.length];
			Object[] values = new Object[components.length];
			for (int i = 0; i < components.length; i++) {
				types[i] = components[i].getType();
				values[i] = components[i].getName().equals("forced") ? Map.copyOf(forced)
						: components[i].getAccessor().invoke(pack);
			}
			ReflexiumClient.LOGGER.info("Vitrail {}: TAA-dependent sampling {} with Reflexium projection jitter", name,
					com.bambooz.reflexium.debug.DebugSettings.packTaaRetained() ? "enabled" : "disabled for diagnostic comparison");
			return type.getConstructor(types).newInstance(values);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			integrationBroken = true;
			ReflexiumClient.LOGGER.error("Vitrail pack antialiasing override failed; upscaling is paused", e);
			return pack;
		}
	}

	/**
	 * Dev harness only (DebugServer {@code set shaders}): flips the enabled flag in the active game directory's
	 * vitrail/pack.txt and asks Vitrail to read it again. Never called in normal play.
	 */
	public static void debugSetPackEnabled(boolean enabled) {
		if (!isSupportedVersion()) {
			throw new IllegalStateException("Vitrail " + version() + " is not supported");
		}
		try {
			java.nio.file.Path gameDirectory = Minecraft.getInstance().gameDirectory.toPath();
			java.nio.file.Path file = gameDirectory.resolve("vitrail").resolve("pack.txt");
			String text = java.nio.file.Files.readString(file);
			String updated = text.replaceAll("(?m)^enabled=(true|false)", "enabled=" + enabled);
			if (!updated.equals(text)) {
				java.nio.file.Files.writeString(file, updated);
			}
			Class.forName("dev.vitrail.render.PackChoice").getMethod("reload", java.nio.file.Path.class).invoke(null, gameDirectory);
		} catch (java.io.IOException | ReflectiveOperationException e) {
			throw new IllegalStateException("could not change the Vitrail pack state", e);
		}
	}

	/** Dev harness only: A/B the pack's own TAA with upscaling (DebugServer {@code set pack_taa}). */
	public static void debugSetPackTaa(boolean retained) {
		com.bambooz.reflexium.debug.DebugSettings.setPackTaaRetained(retained);
		debugReloadPack();
	}

	public static void debugSetPackResolveBypass(boolean bypass) {
		com.bambooz.reflexium.debug.DebugSettings.setPackResolveBypass(bypass);
		debugReloadPack();
	}

	private static void debugReloadPack() {
		ComplementaryTemporalSource.reset();
		try {
			Class.forName("dev.vitrail.pack.source.OpenedPack").getMethod("forgetKept").invoke(null);
			Class.forName("dev.vitrail.render.PackChoice").getMethod("reload", java.nio.file.Path.class)
					.invoke(null, Minecraft.getInstance().gameDirectory.toPath());
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("could not reload the Vitrail pack", e);
		}
	}
	/** True while Vitrail draws a shader pack, including the conservative answer when lookup fails. */
	public static boolean isDrawingPack() {
		if (!isLoaded()) {
			return false;
		}
		if (!lookUp()) {
			return true;
		}
		try {
			return (boolean) drawingPack.invoke(null);
		} catch (ReflectiveOperationException | RuntimeException e) {
			broken = true;
			return true;
		}
	}

	/** Version plus the pack being drawn. Touches Vitrail's render classes, so call it only once the game is running. */
	public static String status() {
		return isLoaded() ? version() + packStatus().map(s -> ", " + s).orElse("") : version();
	}

	private static Optional<String> packStatus() {
		if (!lookUp()) {
			return Optional.empty();
		}
		try {
			if (!(boolean) drawingPack.invoke(null)) {
				return Optional.of("no shader pack drawing");
			}
			Optional<?> current = (Optional<?>) session.invoke(null);
			if (current.isEmpty()) {
				return Optional.of("no shader pack active");
			}
			Object pack = current.get();
			String name = (String) packFileName.invoke(pack);
			return Optional.of(name);
		} catch (ReflectiveOperationException | RuntimeException e) {
			broken = true;
			return Optional.of("could not read active pack name");
		}
	}

	private static boolean lookUp() {
		if (lookedUp) {
			return !broken;
		}
		lookedUp = true;
		try {
			Class<?> chain = Class.forName("dev.vitrail.render.PackChain");
			drawingPack = chain.getMethod("drawingPack");
			Class<?> choice = Class.forName("dev.vitrail.render.PackChoice");
			session = choice.getMethod("session");
			Class<?> sessionClass = Class.forName("dev.vitrail.settings.PackSession");
			packFileName = sessionClass.getMethod("packFileName");
			return true;
		} catch (ReflectiveOperationException | LinkageError e) {
			broken = true;
			ReflexiumClient.LOGGER.warn("Vitrail reflection failed; shader pack integration disabled", e);
			return false;
		}
	}
}
