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
		return isLoaded() && version().startsWith("0.12.");
	}

	public static boolean canUpscale() {
		return !isDrawingPack() || isSupportedVersion() && !broken && !integrationBroken;
	}

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
		try {
			if (lookUp() && ((Optional<?>) session.invoke(null)).isPresent()) {
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
		if (!temporalOwner || integrationBroken) {
			return pack;
		}
		try {
			Class<?> type = pack.getClass();
			String name = (String) type.getMethod("packFileName").invoke(pack);
			Set<?> declared = (Set<?>) type.getMethod("declared").invoke(pack);
			// Complementary's TAA_DEFINE=0 disables both its jitter and its final TAA resolve.
			// ponytail: other packs keep their TAA; add a reviewed mapping when testing that pack.
			if (!(name.startsWith("ComplementaryReimagined_") || name.startsWith("ComplementaryUnbound_"))
					|| !declared.contains("TAA_DEFINE")) {
				return pack;
			}
			Map<?, ?> original = (Map<?, ?>) type.getMethod("forced").invoke(pack);
			Map<Object, Object> forced = new LinkedHashMap<>(original);
			forced.put("TAA_DEFINE", Class.forName("dev.vitrail.pack.option.OptionValue")
					.getMethod("of", String.class).invoke(null, "0"));
			RecordComponent[] components = type.getRecordComponents();
			Class<?>[] types = new Class<?>[components.length];
			Object[] values = new Object[components.length];
			for (int i = 0; i < components.length; i++) {
				types[i] = components[i].getType();
				values[i] = components[i].getName().equals("forced") ? Map.copyOf(forced)
						: components[i].getAccessor().invoke(pack);
			}
			ReflexiumClient.LOGGER.info("Vitrail {}: Reflexium owns TAA while upscaling (TAA_DEFINE=0)", name);
			return type.getConstructor(types).newInstance(values);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			integrationBroken = true;
			ReflexiumClient.LOGGER.error("Vitrail pack TAA override failed; upscaling is paused", e);
			return pack;
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
				return Optional.of("drawing a shader pack");
			}
			if (packFileName == null) {
				packFileName = current.get().getClass().getMethod("packFileName");
			}
			return Optional.of("drawing " + packFileName.invoke(current.get()));
		} catch (ReflectiveOperationException | RuntimeException e) {
			broken = true;
			return Optional.empty();
		}
	}

	private static boolean lookUp() {
		if (!lookedUp) {
			lookedUp = true;
			try {
				drawingPack = Class.forName("dev.vitrail.render.PackChain").getMethod("drawingPack");
				session = Class.forName("dev.vitrail.render.PackChoice").getMethod("session");
			} catch (ReflectiveOperationException | LinkageError e) {
				broken = true;
			}
		}
		return !broken;
	}
}
