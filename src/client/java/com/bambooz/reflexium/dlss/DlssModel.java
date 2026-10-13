package com.bambooz.reflexium.dlss;

import net.minecraft.network.chat.Component;
import java.util.Locale;

/** NGX render presets. Auto lets the installed NVIDIA runtime select per-quality defaults. */
public enum DlssModel {
	AUTO(0), TRANSFORMER(11), CNN(5);
	private final int preset;
	DlssModel(int preset) { this.preset = preset; }
	public int preset() { return preset; }
	public String serializedName() { return name().toLowerCase(Locale.ROOT); }
	public Component label() { return Component.translatable("reflexium.dlss_model." + serializedName()); }
	public static DlssModel parse(String value, DlssModel fallback) {
		try { return value == null ? fallback : valueOf(value.trim().toUpperCase(Locale.ROOT)); }
		catch (IllegalArgumentException ignored) { return fallback; }
	}
}
