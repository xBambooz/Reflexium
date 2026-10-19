package com.bambooz.reflexium.config;

import net.minecraft.network.chat.Component;

public enum ShadowDistanceCap {
	OFF(0, "off"),
	DIST_256(256, "256"),
	DIST_192(192, "192"),
	DIST_128(128, "128");

	private final int value;
	private final String serializedName;

	ShadowDistanceCap(int value, String serializedName) {
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
		return Component.translatable("reflexium.shadow_distance_cap." + serializedName);
	}

	public static ShadowDistanceCap fromValue(int value) {
		for (ShadowDistanceCap cap : values()) {
			if (cap.value == value) {
				return cap;
			}
		}
		return OFF;
	}
}
