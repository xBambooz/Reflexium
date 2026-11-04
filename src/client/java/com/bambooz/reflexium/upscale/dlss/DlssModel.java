package com.bambooz.reflexium.upscale.dlss;

import com.bambooz.reflexium.upscale.UpscaleQuality;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Requested DLSS render model. Auto lets Streamline request the runtime's per-mode default.
 * Transformer uses the guide's defaults: K for DLAA/Quality/Balanced, M for Performance and
 * L for Ultra Performance. The plugin does not report which model it actually chose.
 */
public enum DlssModel {
	AUTO, TRANSFORMER, CNN;

	/** {@code sl::DLSSPreset} values: E=5, K=11, L=12, M=13. */
	public static final int PRESET_CNN = 5;
	public static final int PRESET_K = 11;
	public static final int PRESET_L = 12;
	public static final int PRESET_M = 13;

	public int preset(UpscaleQuality quality) {
		return switch (this) {
			case AUTO -> 0;
			case CNN -> PRESET_CNN;
			case TRANSFORMER -> switch (quality) {
				case NATIVE_AA, QUALITY, BALANCED -> PRESET_K;
				case PERFORMANCE -> PRESET_M;
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
