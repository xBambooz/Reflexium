package com.bambooz.reflexium.compat;

import org.slf4j.LoggerFactory;

/** Opt-in diagnostic for comparing forced delegation with Meshelium's own pass eligibility checks. */
public final class MesheliumCompat {
	private static boolean stoodDown;
	/** Runtime diagnostic only, never persisted to player settings. */
	public static boolean debugDelegation;
	private MesheliumCompat() {}

	public static boolean delegateTerrain() {
		boolean pack = debugDelegation && ShaderPacks.isDrawingPack();
		if (pack != stoodDown) {
			LoggerFactory.getLogger("Reflexium").info(pack
					? "Meshelium compatibility: delegating opaque terrain to Sodium/Iris while a shader pack is active. Mesh shader acceleration is unavailable for this path."
					: "Meshelium compatibility: mesh terrain enabled (pack inactive or diagnostic override).");
			stoodDown = pack;
		}
		return pack;
	}
}
