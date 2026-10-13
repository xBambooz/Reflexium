package com.bambooz.reflexium.gui;

import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.dlss.UpscaleQuality;
import com.bambooz.reflexium.dlss.UpscalingRenderer;
import com.bambooz.reflexium.dlss.Upscaler;
import com.bambooz.reflexium.dlss.DlssModel;
import com.bambooz.reflexium.reflex.ReflexController;
import com.bambooz.reflexium.reflex.ReflexMode;
import com.mojang.serialization.Codec;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Option widgets shared by Video Settings and the Mod Menu config screen. */
public final class ReflexiumOptions {
	private static final Codec<ReflexMode> MODE_CODEC =
			Codec.STRING.xmap(value -> ReflexMode.parse(value, ReflexMode.OFF), ReflexMode::serializedName);
	private static final Codec<UpscaleQuality> DLSS_CODEC =
			Codec.STRING.xmap(value -> UpscaleQuality.parse(value, UpscaleQuality.QUALITY), UpscaleQuality::serializedName);

	private ReflexiumOptions() {
	}

	public static OptionInstance<ReflexMode> reflexMode() {
		return new OptionInstance<>(
				"reflexium.option.reflex_mode",
				OptionInstance.cachedConstantTooltip(Component.translatable("reflexium.option.reflex_mode.tooltip")),
				(caption, value) -> value.label(),
				new OptionInstance.Enum<>(List.of(ReflexMode.values()), MODE_CODEC),
				MODE_CODEC,
				ReflexiumConfig.get().reflexMode(),
				value -> ReflexController.get().setMode(value));
	}

	public static OptionInstance<UpscaleQuality> upscaleQuality() {
		return new OptionInstance<>(
				"reflexium.option.quality",
				OptionInstance.cachedConstantTooltip(Component.translatable("reflexium.option.quality.tooltip")),
				(caption, value) -> value.label(),
				new OptionInstance.Enum<>(List.of(UpscaleQuality.values()), DLSS_CODEC),
				DLSS_CODEC,
				ReflexiumConfig.get().upscaleQuality(),
				ReflexiumOptions::setUpscaleQuality);
	}

	/** Persists the DLSS preset and lets a previously failed DLSS retry. */
	public static void setUpscaleQuality(UpscaleQuality mode) {
		ReflexiumConfig.get().setUpscaleQuality(mode);
		UpscalingRenderer.get().onModeChanged();
	}

	public static OptionInstance<Upscaler> upscaler() {
		Codec<Upscaler> codec = Codec.STRING.xmap(v -> Upscaler.parse(v, Upscaler.AUTO), Upscaler::serializedName);
		return new OptionInstance<>("reflexium.option.upscaler",
				OptionInstance.cachedConstantTooltip(Component.translatable("reflexium.option.upscaler.tooltip")),
				(caption, value) -> value.label(), new OptionInstance.Enum<>(List.of(Upscaler.values()), codec),
				ReflexiumConfig.get().upscaler(), ReflexiumOptions::setUpscaler);
	}
	public static void setUpscaler(Upscaler value) {
		ReflexiumConfig.get().setUpscaler(value);
		UpscalingRenderer.get().onModeChanged();
	}
	public static OptionInstance<DlssModel> dlssModel() {
		Codec<DlssModel> codec = Codec.STRING.xmap(v -> DlssModel.parse(v, DlssModel.AUTO), DlssModel::serializedName);
		return new OptionInstance<>("reflexium.option.dlss_model",
				OptionInstance.cachedConstantTooltip(Component.translatable("reflexium.option.dlss_model.tooltip")),
				(caption, value) -> value.label(), new OptionInstance.Enum<>(List.of(DlssModel.values()), codec),
				ReflexiumConfig.get().dlssModel(), ReflexiumOptions::setDlssModel);
	}
	public static void setDlssModel(DlssModel value) {
		ReflexiumConfig.get().setDlssModel(value);
		UpscalingRenderer.get().onModeChanged();
	}
	public static OptionInstance<Integer> fsrSharpness() {
		return new OptionInstance<>("reflexium.option.fsr_sharpness",
				OptionInstance.cachedConstantTooltip(Component.translatable("reflexium.option.fsr_sharpness.tooltip")),
				(caption, value) -> Component.literal(value + "%"), new OptionInstance.IntRange(0, 100),
				ReflexiumConfig.get().fsrSharpness(), ReflexiumConfig.get()::setFsrSharpness);
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
