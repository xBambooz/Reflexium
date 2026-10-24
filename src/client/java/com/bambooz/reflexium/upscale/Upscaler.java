package com.bambooz.reflexium.upscale;

import net.minecraft.network.chat.Component;
import java.util.Locale;

public enum Upscaler {
	OFF, AUTO, DLSS, FSR;

	public String serializedName() { return name().toLowerCase(Locale.ROOT); }
	public Component label() { return Component.translatable("reflexium.upscaler." + serializedName()); }
	public static Upscaler parse(String value, Upscaler fallback) {
		try { return value == null ? fallback : valueOf(value.trim().toUpperCase(Locale.ROOT)); }
		catch (IllegalArgumentException ignored) { return fallback; }
	}
	public Upscaler resolve(boolean dlss, boolean fsr) {
		return this == AUTO ? dlss ? DLSS : fsr ? FSR : OFF : this;
	}
}
