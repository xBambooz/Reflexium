package com.bambooz.reflexium.compat;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Vitrail runs OptiFine-format shader packs on Minecraft's own Vulkan device, queue and swapchain, so
 * Reflex needs no special handling under it (verified in docs/VITRAIL.md). This class only reports what
 * Vitrail is drawing. PackChain/PackChoice are public implementation classes, not a stable API, so they
 * are read reflectively and any failure reports the installed version alone.
 */
public final class VitrailCompat {
	private static final String MOD_ID = "vitrail";

	private static Method drawingPack;
	private static Method session;
	private static Method packFileName;
	private static boolean lookedUp;
	private static boolean broken;

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

	/** True while Vitrail draws a shader pack. The pack owns the world image, so DLSS steps aside. */
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
