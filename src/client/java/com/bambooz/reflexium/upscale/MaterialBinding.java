package com.bambooz.reflexium.upscale;

/**
 * Which texture bindings are color materials that receive the upscaling mip bias. The selection is made at
 * the shared render pass binding boundary, so it covers vanilla terrain (Sampler0), Sodium terrain
 * (u_BlockTex) and the Vitrail atlas/PBR paths without a dependency on Sodium.
 */
public final class MaterialBinding {
	/** Vanilla material uniform. */
	public static final int SAMPLER0 = 0;
	/** Sodium terrain atlas uniform, bound by its own chunk renderer. */
	public static final int BLOCK_TEX = 1;
	/** Vitrail geometry atlas sampler (albedo and interpolated PBR maps). */
	public static final int PACK_ATLAS = 2;
	/** Vitrail non-interpolated PBR map sampler (normal, specular and similar material maps). */
	public static final int PACK_PBR = 3;
	public static final int PATHS = 4;
	static final String[] PATH_NAMES = {"Sampler0", "u_BlockTex", "pack_atlas", "pack_pbr"};

	private MaterialBinding() {}

	/** Path index for a render pass uniform name, or -1 when the name is not a color material. */
	public static int uniformPath(String uniform) {
		if (uniform == null) return -1;
		return switch (uniform) {
			case "Sampler0" -> SAMPLER0;
			case "u_BlockTex" -> BLOCK_TEX;
			default -> -1;
		};
	}

	/** A mipmapped color texture. Depth maps, one-level targets and GUI sprites are excluded. */
	public static boolean isMipmappedColor(int mipLevels, boolean hasColorAspect) {
		return mipLevels > 1 && hasColorAspect;
	}
}
