package com.bambooz.reflexium.gui;

import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.upscale.dlss.DlssModel;
import com.bambooz.reflexium.framegen.FrameGeneration;
import com.bambooz.reflexium.framegen.FrameGenerationRenderer;
import com.bambooz.reflexium.upscale.UpscaleQuality;
import com.bambooz.reflexium.upscale.Upscaler;
import com.bambooz.reflexium.upscale.UpscalingRenderer;
import com.bambooz.reflexium.reflex.ReflexController;
import com.bambooz.reflexium.reflex.ReflexMode;
import com.mojang.serialization.Codec;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.stream.IntStream;

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

	public static OptionInstance<FrameGeneration> frameGeneration() {
		Codec<FrameGeneration> codec = Codec.STRING.xmap(v -> FrameGeneration.parse(v, ReflexiumConfig.DEFAULT_FRAME_GENERATION), FrameGeneration::serializedName);
		return new OptionInstance<>("reflexium.option.frame_generation",
				OptionInstance.cachedConstantTooltip(gateTooltip("reflexium.option.frame_generation.tooltip")),
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
				OptionInstance.cachedConstantTooltip(gateTooltip("reflexium.option.fg_multiplier.tooltip")),
				(caption, value) -> Component.literal(value == 0 ? "Auto" : value + "x"),
				new OptionInstance.Enum<>(IntStream.concat(IntStream.of(0), IntStream.rangeClosed(2, maximum)).boxed().toList(), Codec.INT),
				Math.min(maximum, ReflexiumConfig.get().frameGenerationMultiplier()), ReflexiumOptions::setFrameGenerationMultiplier);
	}

	/** The tooltip plus the hardware/runtime reason when this adapter cannot offer the option. */
	public static Component gateTooltip(String key) {
		Component tooltip = Component.translatable(key);
		var gate = FrameGenerationRenderer.get().gate();
		if (gate.reasonKey().isEmpty()) return tooltip;
		return tooltip.copy().append("\n\n").append(Component.translatable(gate.reasonKey()));
	}
	public static void addFrameGeneration(OptionsList list) {
		list.addBig(frameGeneration());
		OptionInstance<Integer> multiplier = frameGenerationMultiplier();
		list.addBig(multiplier);
		list.findOption(multiplier).active = FrameGenerationRenderer.get().isDlssFgSupported();
	}
	public static void setFrameGenerationMultiplier(int value) {
		ReflexiumConfig.get().setFrameGenerationMultiplier(value); FrameGenerationRenderer.get().retry();
	}
}
