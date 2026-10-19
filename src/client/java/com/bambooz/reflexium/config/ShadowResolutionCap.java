package com.bambooz.reflexium.config;

import net.minecraft.network.chat.Component;

public enum ShadowResolutionCap {
	OFF(0, "off"),
	RES_4096(4096, "4096"),
	RES_2048(2048, "2048"),
	RES_1024(1024, "1024");

	private final int value;
	private final String serializedName;

	ShadowResolutionCap(int value, String serializedName) {
		this.value = value;
		this.serializedName = serializedName;
	}

	public int value() {
		return value;
	}

	public String serializedName() {
		return serializedName;
	}

	public Component label() {
		return Component.translatable("reflexium.shadow_resolution_cap." + serializedName);
	}

	public static ShadowResolutionCap fromValue(int value) {
		for (ShadowResolutionCap cap : values()) {
			if (cap.value == value) {
				return cap;
			}
		}
		return OFF;
	}
}
