package com.bambooz.reflexium.gui;

import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.reflex.ReflexController;
import com.bambooz.reflexium.reflex.ReflexMode;
import com.mojang.serialization.Codec;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Option widgets shared by Video Settings and the Mod Menu config screen. */
public final class ReflexiumOptions {
	private static final Codec<ReflexMode> MODE_CODEC =
			Codec.STRING.xmap(value -> ReflexMode.parse(value, ReflexMode.OFF), ReflexMode::serializedName);

	private ReflexiumOptions() {
	}

	public static OptionInstance<ReflexMode> reflexMode() {
		return new OptionInstance<>(
				"reflexium.option.reflex_mode",
				OptionInstance.cachedConstantTooltip(Component.translatable("reflexium.option.reflex_mode.tooltip")),
				(caption, value) -> Options.genericValueLabel(caption, value.label()),
				new OptionInstance.Enum<>(List.of(ReflexMode.values()), MODE_CODEC),
				MODE_CODEC,
				ReflexiumConfig.get().reflexMode(),
				value -> ReflexController.get().setMode(value));
	}

	public static OptionInstance<Boolean> frameLimiter() {
		ReflexiumConfig config = ReflexiumConfig.get();
		return OptionInstance.createBoolean("reflexium.option.frame_limiter",
				OptionInstance.cachedConstantTooltip(Component.translatable("reflexium.option.frame_limiter.tooltip")),
				config.reflexFrameLimiter(), config::setReflexFrameLimiter);
	}

	public static OptionInstance<Boolean> dlssDetection() {
		ReflexiumConfig config = ReflexiumConfig.get();
		return OptionInstance.createBoolean("reflexium.option.dlss_detection",
				OptionInstance.cachedConstantTooltip(Component.translatable("reflexium.option.dlss_detection.tooltip")),
				config.dlssDetection(), config::setDlssDetection);
	}
}
