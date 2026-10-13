package com.bambooz.reflexium.dlss;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/** DLSS Super Resolution preset. {@code ngxValue} is NVSDK_NGX_PerfQuality_Value. */
public enum UpscaleQuality {
	NATIVE_AA("native_aa", 5),
	QUALITY("quality", 2),
	BALANCED("balanced", 1),
	PERFORMANCE("performance", 0),
	ULTRA_PERFORMANCE("ultra_performance", 3);

	private final String serializedName;
	private final int ngxValue;

	UpscaleQuality(String serializedName, int ngxValue) {
		this.serializedName = serializedName;
		this.ngxValue = ngxValue;
	}

	public String serializedName() {
		return serializedName;
	}

	public int ngxValue() {
		return ngxValue;
	}

	public int[] fsrSize(int width, int height) {
		double ratio = switch (this) {
			case NATIVE_AA -> 1.0;
			case QUALITY -> 1.5;
			case BALANCED -> 1.7;
			case PERFORMANCE -> 2.0;
			case ULTRA_PERFORMANCE -> 3.0;
		};
		return new int[] {Math.max(1, (int) (width / ratio)), Math.max(1, (int) (height / ratio))};
	}

	public Component label() {
		return Component.translatable("reflexium.quality." + serializedName);
	}

	public String displayName() {
		return switch (this) {
			case NATIVE_AA -> "Native AA";
			case QUALITY -> "Quality";
			case BALANCED -> "Balanced";
			case PERFORMANCE -> "Performance";
			case ULTRA_PERFORMANCE -> "Ultra Performance";
		};
	}

	public static UpscaleQuality parse(String value, UpscaleQuality fallback) {
		if (value == null) {
			return fallback;
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		if (normalized.equals("dlaa")) return NATIVE_AA;
		for (UpscaleQuality mode : values()) {
			if (mode.serializedName.equals(normalized)) {
				return mode;
			}
		}
		return fallback;
	}
}
