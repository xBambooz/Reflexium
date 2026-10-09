package com.bambooz.reflexium.reflex;

import net.minecraft.network.chat.Component;

import java.util.Locale;

public enum ReflexMode {
	OFF("off"),
	ON("on"),
	ON_BOOST("on_boost");

	private final String serializedName;

	ReflexMode(String serializedName) {
		this.serializedName = serializedName;
	}

	public String serializedName() {
		return serializedName;
	}

	public Component label() {
		return Component.translatable("reflexium.reflex_mode." + serializedName);
	}

	/** Plain English name for logs and the F3 overlay. */
	public String displayName() {
		return switch (this) {
			case OFF -> "Off";
			case ON -> "On";
			case ON_BOOST -> "On + Boost";
		};
	}

	public boolean enabled() {
		return this != OFF;
	}

	public static ReflexMode parse(String value, ReflexMode fallback) {
		if (value == null) {
			return fallback;
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		for (ReflexMode mode : values()) {
			if (mode.serializedName.equals(normalized) || mode.name().toLowerCase(Locale.ROOT).equals(normalized)) {
				return mode;
			}
		}
		return fallback;
	}
}
