package com.bambooz.reflexium.dlss;

import net.minecraft.network.chat.Component;
import java.util.Locale;

public enum FrameGeneration {
	OFF, DLSS, DLSS_MULTI, FSR;
	public String serializedName() { return name().toLowerCase(Locale.ROOT); }
	public Component label() { return Component.translatable("reflexium.frame_generation." + serializedName()); }
	public boolean nvidia() { return this == DLSS || this == DLSS_MULTI; }
	public FrameGeneration withMultiplier(int multiplier) { return nvidia() ? (multiplier > 2 ? DLSS_MULTI : DLSS) : this; }
	public static FrameGeneration parse(String value, FrameGeneration fallback) {
		try { return value == null ? fallback : valueOf(value.trim().toUpperCase(Locale.ROOT)); }
		catch (IllegalArgumentException ignored) { return fallback; }
	}
}
