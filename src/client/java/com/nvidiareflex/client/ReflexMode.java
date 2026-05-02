package com.nvidiareflex.client;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

public enum ReflexMode implements StringRepresentable {
	OFF("off", "Off"),
	ON("on", "On"),
	ON_BOOST("on_boost", "On + Boost");

	public static final Codec<ReflexMode> CODEC = StringRepresentable.fromEnum(ReflexMode::values);

	private final String serializedName;
	private final String displayName;

	ReflexMode(String serializedName, String displayName) {
		this.serializedName = serializedName;
		this.displayName = displayName;
	}

	public String getDisplayName() {
		return displayName;
	}

	@Override
	public String getSerializedName() {
		return serializedName;
	}

	public ReflexMode next() {
		ReflexMode[] values = values();
		return values[(ordinal() + 1) % values.length];
	}

	public static ReflexMode fromString(String name) {
		for (ReflexMode mode : values()) {
			if (mode.serializedName.equalsIgnoreCase(name) || mode.name().equalsIgnoreCase(name)) {
				return mode;
			}
		}
		return OFF;
	}
}
