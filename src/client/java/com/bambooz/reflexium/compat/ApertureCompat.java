package com.bambooz.reflexium.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.Optional;

/**
 * Aperture is the Iris team's Vulkan shader loader (private beta as of 2026-10). It renders through
 * Minecraft's own Vulkan device and swapchain, so Reflex needs no special handling: the device
 * extensions, sleep, markers and present tagging all sit below the shader pipeline. Any queue
 * submission Aperture makes through vanilla's VulkanQueue is tagged like vanilla's own.
 * <p>
 * DLSS is different: an Aperture pack owns its render targets and final combination pass, and the
 * public pack API (Aperture-Example-Pack, iris.d.ts) exposes no jitter, motion vector or upscaler
 * hook yet. See docs/DLSS.md for the contract Reflexium needs from Aperture.
 */
public final class ApertureCompat {
	private static final String[] MOD_IDS = {"aperture", "aperture-shaders"};

	private ApertureCompat() {
	}

	public static Optional<ModContainer> mod() {
		for (String id : MOD_IDS) {
			Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer(id);
			if (mod.isPresent()) {
				return mod;
			}
		}
		return Optional.empty();
	}

	public static String status() {
		return mod().map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("not installed");
	}
}
