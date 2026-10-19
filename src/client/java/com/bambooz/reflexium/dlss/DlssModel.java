package com.bambooz.reflexium.dlss;

import net.minecraft.network.chat.Component;
import java.util.Locale;

/**
 * Requested NGX render model. Auto lets the installed NVIDIA runtime select per-quality defaults.
 * Transformer requests K through Performance after the 1440p K/M quality/cost comparison,
 * and L for Ultra Performance. NGX does not report which model it actually chose.
 */
public enum DlssModel {
	AUTO, TRANSFORMER, CNN;

	/** NVSDK_NGX_DLSS_Hint_Render_Preset values: E=5, K=11, L=12, M=13. */
	public static final int PRESET_CNN = 5;
	public static final int PRESET_K = 11;
	public static final int PRESET_L = 12;
	public static final int PRESET_M = 13;

	public int preset(UpscaleQuality quality) {
		return switch (this) {
			case AUTO -> 0;
			case CNN -> PRESET_CNN;
			case TRANSFORMER -> switch (quality) {
				case NATIVE_AA, QUALITY, BALANCED, PERFORMANCE -> PRESET_K;
				case ULTRA_PERFORMANCE -> PRESET_L;
			};
		};
	}

	public String serializedName() { return name().toLowerCase(Locale.ROOT); }
	public Component label() { return Component.translatable("reflexium.dlss_model." + serializedName()); }
	public static DlssModel parse(String value, DlssModel fallback) {
		try { return value == null ? fallback : valueOf(value.trim().toUpperCase(Locale.ROOT)); }
		catch (IllegalArgumentException ignored) { return fallback; }
	}
}
