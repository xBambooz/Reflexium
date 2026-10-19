package com.bambooz.reflexium.gui;

import com.bambooz.reflexium.compat.IrisCompat;
import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.config.ShadowDistanceCap;
import com.bambooz.reflexium.config.ShadowResolutionCap;
import com.bambooz.reflexium.dlss.UpscaleQuality;
import com.bambooz.reflexium.dlss.UpscalingRenderer;
import com.bambooz.reflexium.dlss.Upscaler;
import com.bambooz.reflexium.dlss.DlssModel;
import com.bambooz.reflexium.dlss.FrameGeneration;
import com.bambooz.reflexium.dlss.FrameGenerationRenderer;
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
	private static final Codec<ShadowResolutionCap> RESOLUTION_CAP_CODEC =
			Codec.STRING.xmap(value -> {
				for (ShadowResolutionCap cap : ShadowResolutionCap.values()) {
					if (cap.serializedName().equalsIgnoreCase(value)) return cap;
				}
				return ShadowResolutionCap.OFF;
			}, ShadowResolutionCap::serializedName);
	private static final Codec<ShadowDistanceCap> DISTANCE_CAP_CODEC =
			Codec.STRING.xmap(value -> {
				for (ShadowDistanceCap cap : ShadowDistanceCap.values()) {
					if (cap.serializedName().equalsIgnoreCase(value)) return cap;
				}
				return ShadowDistanceCap.OFF;
			}, ShadowDistanceCap::serializedName);

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

	public static OptionInstance<Boolean> smartReflex() {
		return OptionInstance.createBoolean("reflexium.option.smart_reflex",
				OptionInstance.cachedConstantTooltip(Component.translatable("reflexium.option.smart_reflex.tooltip")),
				ReflexiumConfig.get().smartReflex(), ReflexiumConfig.get()::setSmartReflex);
	}
	public static OptionInstance<Boolean> postUpscaleParticles() {
		return OptionInstance.createBoolean("reflexium.option.post_upscale_particles",
				OptionInstance.cachedConstantTooltip(Component.translatable("reflexium.option.post_upscale_particles.tooltip")),
				ReflexiumConfig.get().postUpscaleParticles(), ReflexiumConfig.get()::setPostUpscaleParticles);
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
		Codec<DlssModel> codec = Codec.STRING.xmap(v -> DlssModel.parse(v, ReflexiumConfig.DEFAULT_DLSS_MODEL), DlssModel::serializedName);
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

	public static OptionInstance<ShadowResolutionCap> packShadowResolutionCap() {
		return new OptionInstance<>("reflexium.option.pack_shadow_resolution_cap",
				OptionInstance.cachedConstantTooltip(Component.translatable("reflexium.option.pack_shadow_resolution_cap.tooltip")),
				(caption, value) -> value.label(),
				new OptionInstance.Enum<>(List.of(ShadowResolutionCap.values()), RESOLUTION_CAP_CODEC),
				RESOLUTION_CAP_CODEC,
				ShadowResolutionCap.fromValue(ReflexiumConfig.get().packShadowResolutionCap()),
				ReflexiumOptions::setPackShadowResolutionCap);
	}

	public static OptionInstance<ShadowDistanceCap> packShadowDistanceCap() {
		return new OptionInstance<>("reflexium.option.pack_shadow_distance_cap",
				OptionInstance.cachedConstantTooltip(Component.translatable("reflexium.option.pack_shadow_distance_cap.tooltip")),
				(caption, value) -> value.label(),
				new OptionInstance.Enum<>(List.of(ShadowDistanceCap.values()), DISTANCE_CAP_CODEC),
				DISTANCE_CAP_CODEC,
				ShadowDistanceCap.fromValue(ReflexiumConfig.get().packShadowDistanceCap()),
				ReflexiumOptions::setPackShadowDistanceCap);
	}

	public static void setPackShadowResolutionCap(ShadowResolutionCap value) {
		setPackShadowResolutionCap(value.value());
	}

	public static void setPackShadowResolutionCap(int value) {
		ReflexiumConfig.get().setPackShadowResolutionCap(value);
		IrisCompat.reloadForOptionChange();
	}

	public static void setPackShadowDistanceCap(ShadowDistanceCap value) {
		setPackShadowDistanceCap(value.value());
	}

	public static void setPackShadowDistanceCap(int value) {
		ReflexiumConfig.get().setPackShadowDistanceCap(value);
		IrisCompat.reloadForOptionChange();
	}

	public static OptionInstance<FrameGeneration> frameGeneration() {
		Codec<FrameGeneration> codec = Codec.STRING.xmap(v -> FrameGeneration.parse(v, ReflexiumConfig.DEFAULT_FRAME_GENERATION), FrameGeneration::serializedName);
		return new OptionInstance<>("reflexium.option.frame_generation",
				OptionInstance.cachedConstantTooltip(Component.translatable("reflexium.option.frame_generation.tooltip")),
				(caption, value) -> value.label(), new OptionInstance.Enum<>(List.of(FrameGeneration.values()), codec),
				ReflexiumConfig.get().frameGeneration(), ReflexiumOptions::setFrameGeneration);
	}
	public static void setFrameGeneration(FrameGeneration value) {
		ReflexiumConfig.get().setFrameGeneration(value);
		FrameGenerationRenderer.get().retry();
	}
	public static OptionInstance<Integer> frameGenerationMultiplier() {
		int maximum = Math.max(2, FrameGenerationRenderer.get().maximumMultiplier());
		return new OptionInstance<>("reflexium.option.fg_multiplier",
				OptionInstance.cachedConstantTooltip(Component.translatable("reflexium.option.fg_multiplier.tooltip")),
				(caption, value) -> Component.literal(value == 0 ? "Auto" : value + "x"),
				new OptionInstance.Enum<>(java.util.stream.IntStream.concat(java.util.stream.IntStream.of(0), java.util.stream.IntStream.rangeClosed(2, maximum)).boxed().toList(), Codec.INT),
				Math.min(maximum, ReflexiumConfig.get().frameGenerationMultiplier()), ReflexiumOptions::setFrameGenerationMultiplier);
	}
	public static void addFrameGeneration(net.minecraft.client.gui.components.OptionsList list) {
		list.addBig(frameGeneration());
		OptionInstance<Integer> multiplier = frameGenerationMultiplier();
		list.addBig(multiplier);
		list.findOption(multiplier).active = FrameGenerationRenderer.get().isDlssFgSupported();
	}
	public static void setFrameGenerationMultiplier(int value) {
		ReflexiumConfig.get().setFrameGenerationMultiplier(value); FrameGenerationRenderer.get().retry();
	}
}
