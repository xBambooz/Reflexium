package com.bambooz.reflexium.compat;

import org.slf4j.LoggerFactory;

/** Meshelium 1.6.2's raw terrain fragment path cannot execute a shader pack's gbuffers. */
public final class MesheliumCompat {
	private static boolean stoodDown;
	private MesheliumCompat() {}

	public static boolean delegateTerrain() {
		boolean pack = ShaderPacks.isDrawingPack();
		if (pack != stoodDown) {
			LoggerFactory.getLogger("Reflexium").info(pack
					? "Meshelium compatibility: delegating opaque terrain to Sodium/Iris while a shader pack is active. Mesh shader acceleration is unavailable for this path."
					: "Meshelium compatibility: shader pack disabled, mesh terrain rendering restored.");
			stoodDown = pack;
		}
		return pack;
	}
}
