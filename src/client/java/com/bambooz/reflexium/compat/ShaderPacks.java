package com.bambooz.reflexium.compat;

/** Either supported shader loader. Vitrail-only routes (HDR boundary, pack surface motion) stay in VitrailCompat. */
public final class ShaderPacks {
	private ShaderPacks() {}

	public static boolean anyLoaded() { return VitrailCompat.isLoaded() || IrisCompat.isLoaded(); }
	public static boolean isDrawingPack() { return VitrailCompat.isDrawingPack() || IrisCompat.isDrawingPack(); }
	public static boolean canUpscale() { return VitrailCompat.canUpscale() && IrisCompat.canUpscale(); }

	public static String upscalingProblem() {
		return !VitrailCompat.canUpscale() ? VitrailCompat.upscalingProblem() : IrisCompat.upscalingProblem();
	}

	public static void configureUpscaling(boolean enabled) {
		VitrailCompat.configureUpscaling(enabled);
		IrisCompat.configureUpscaling(enabled);
	}

	public static String status() {
		if (!IrisCompat.isLoaded()) return VitrailCompat.status();
		if (!VitrailCompat.isLoaded()) return "Iris " + IrisCompat.status();
		return VitrailCompat.status() + "; Iris " + IrisCompat.status();
	}
}
