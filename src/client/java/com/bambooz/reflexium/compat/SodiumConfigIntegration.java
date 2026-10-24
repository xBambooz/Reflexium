package com.bambooz.reflexium.compat;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.upscale.UpscaleQuality;
import com.bambooz.reflexium.upscale.Upscaler;
import com.bambooz.reflexium.upscale.dlss.DlssModel;
import com.bambooz.reflexium.framegen.FrameGeneration;
import com.bambooz.reflexium.framegen.FrameGenerationRenderer;
import com.bambooz.reflexium.upscale.UpscalingRenderer;
import com.bambooz.reflexium.gui.ReflexiumOptions;
import com.bambooz.reflexium.gui.ReflexiumConfigScreen;
import com.bambooz.reflexium.reflex.ReflexController;
import com.bambooz.reflexium.reflex.ReflexMode;
import com.bambooz.reflexium.vulkan.VulkanSession;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reflexium page in Sodium's Video Settings, which replaces the vanilla screen that
 * {@code VideoSettingsScreenMixin} extends. Reese's Sodium Options and other Sodium screen
 * replacements render the same registered pages. Loaded only through the
 * {@code sodium:config_api_user} entrypoint, so Sodium stays optional.
 */
public final class SodiumConfigIntegration implements ConfigEntryPoint {
	// ReflexiumConfig persists on every set, so Sodium's post-apply hook has nothing left to write.
	private static final StorageEventHandler SAVED_ON_SET = () -> {
	};

	@Override
	public void registerConfigLate(ConfigBuilder builder) {
		ReflexiumConfig config = ReflexiumConfig.get();

		builder.registerOwnModOptions()
				.setNonTintedIcon(id("icon.png"))
				.addPage(builder.createOptionPage()
						.setName(Component.translatable("reflexium.title"))
						.addOptionGroup(builder.createOptionGroup()
								.setName(Component.translatable("reflexium.group.reflex"))
								.addOption(builder.createEnumOption(id("reflex_mode"), ReflexMode.class)
										.setName(Component.translatable("reflexium.option.reflex_mode"))
										.setTooltip(Component.translatable("reflexium.option.reflex_mode.tooltip"))
										.setElementNameProvider(ReflexMode::label)
										.setImpact(OptionImpact.LOW)
										.setDefaultValue(ReflexMode.ON)
										.setStorageHandler(SAVED_ON_SET)
										.setEnabledProvider(state -> ReflexController.get().isSupported())
										.setBinding(ReflexController.get()::setMode, config::reflexMode)))
						.addOptionGroup(builder.createOptionGroup()
								.setName(Component.translatable("reflexium.group.upscaling"))
								.addOption(builder.createEnumOption(id("upscaler"), Upscaler.class)
										.setName(Component.translatable("reflexium.option.upscaler"))
										.setTooltip(Component.translatable("reflexium.option.upscaler.tooltip"))
										.setElementNameProvider(Upscaler::label)
										.setDefaultValue(Upscaler.AUTO)
										.setAllowedValuesProvider(state -> {
											VulkanSession session = VulkanSession.current();
											if (session != null && session.isNvidia()) {
												return Set.of(Upscaler.AUTO, Upscaler.OFF, Upscaler.DLSS, Upscaler.FSR);
											} else {
												return Set.of(Upscaler.AUTO, Upscaler.OFF, Upscaler.FSR);
											}
										})
										.setStorageHandler(SAVED_ON_SET)
										.setBinding(ReflexiumOptions::setUpscaler, config::upscaler))
								.addOption(builder.createEnumOption(id("dlss_mode"), UpscaleQuality.class)
										.setName(Component.translatable("reflexium.option.quality"))
										.setTooltip(Component.translatable("reflexium.option.quality.tooltip"))
										.setElementNameProvider(UpscaleQuality::label)
										.setImpact(OptionImpact.HIGH)
										.setDefaultValue(UpscaleQuality.QUALITY)
										.setStorageHandler(SAVED_ON_SET)
										.setBinding(ReflexiumOptions::setUpscaleQuality, config::upscaleQuality))
								.addOption(builder.createEnumOption(id("dlss_model"), DlssModel.class)
										.setName(Component.translatable("reflexium.option.dlss_model"))
										.setTooltip(Component.translatable("reflexium.option.dlss_model.tooltip"))
										.setElementNameProvider(DlssModel::label)
										.setDefaultValue(ReflexiumConfig.DEFAULT_DLSS_MODEL)
										.setStorageHandler(SAVED_ON_SET)
										.setEnabledProvider(state -> {
											Upscaler u = state.readEnumOption(id("upscaler"), Upscaler.class);
											boolean dlssActive = (u == Upscaler.DLSS || (u == Upscaler.AUTO && UpscalingRenderer.get().effectiveUpscaler() == Upscaler.DLSS));
											VulkanSession session = VulkanSession.current();
											return dlssActive && UpscalingRenderer.get().dlssReady() && session != null && session.isNvidia();
										}, id("upscaler"))
										.setControlHiddenWhenDisabled(true)
										.setBinding(ReflexiumOptions::setDlssModel, config::dlssModel))
								.addOption(builder.createIntegerOption(id("fsr_sharpness"))
										.setName(Component.translatable("reflexium.option.fsr_sharpness"))
										.setTooltip(Component.translatable("reflexium.option.fsr_sharpness.tooltip"))
										.setRange(0, 100, 5)
										.setValueFormatter(value -> Component.literal(value + "%"))
										.setDefaultValue(20)
										.setStorageHandler(SAVED_ON_SET)
										.setEnabledProvider(state -> {
											Upscaler u = state.readEnumOption(id("upscaler"), Upscaler.class);
											return u != Upscaler.OFF;
										}, id("upscaler"))
										.setControlHiddenWhenDisabled(true)
										.setBinding(config::setFsrSharpness, config::fsrSharpness))
								.addOption(builder.createBooleanOption(id("post_upscale_particles"))
										.setName(Component.translatable("reflexium.option.post_upscale_particles"))
										.setTooltip(Component.translatable("reflexium.option.post_upscale_particles.tooltip"))
										.setDefaultValue(true)
										.setStorageHandler(SAVED_ON_SET)
										.setBinding(config::setPostUpscaleParticles, config::postUpscaleParticles)))
						.addOptionGroup(builder.createOptionGroup()
								.setName(Component.translatable("reflexium.group.frame_generation"))
								.addOption(builder.createEnumOption(id("frame_generation"), FrameGeneration.class)
										.setName(Component.translatable("reflexium.option.frame_generation"))
										.setTooltip(Component.translatable("reflexium.option.frame_generation.tooltip"))
										.setElementNameProvider(FrameGeneration::label)
										.setDefaultValue(ReflexiumConfig.DEFAULT_FRAME_GENERATION)
										.setStorageHandler(SAVED_ON_SET)
										.setAllowedValuesProvider(state -> {
											Upscaler u = state.readEnumOption(id("upscaler"), Upscaler.class);
											Set<FrameGeneration> allowed = new LinkedHashSet<>();
											allowed.add(FrameGeneration.OFF);
											VulkanSession session = VulkanSession.current();
											boolean nvidia = session != null && session.isNvidia();
											boolean dlssAllowed = nvidia && u != Upscaler.FSR;
											boolean fsrAllowed = u != Upscaler.DLSS;
											if (dlssAllowed) {
												if (FrameGenerationRenderer.get().isDlssFgSupported()) {
													allowed.add(FrameGeneration.DLSS);
												}
												if (FrameGenerationRenderer.get().isDlssMfgSupported()) {
													allowed.add(FrameGeneration.DLSS_MULTI);
												}
											} else if (fsrAllowed) {
												allowed.add(FrameGeneration.FSR);
											}
											return allowed;
										}, id("upscaler"))
										.setBinding(ReflexiumOptions::setFrameGeneration, config::frameGeneration))
								.addOption(builder.createIntegerOption(id("fg_multiplier"))
										.setName(Component.translatable("reflexium.option.fg_multiplier"))
										.setTooltip(Component.translatable("reflexium.option.fg_multiplier.tooltip"))
										.setRange(1, Math.max(2, FrameGenerationRenderer.get().maximumMultiplier()), 1)
										.setValueFormatter(value -> Component.literal(value == 1 ? "Auto" : value + "x"))
										.setDefaultValue(1)
										.setStorageHandler(SAVED_ON_SET)
										.setEnabledProvider(state -> {
											FrameGeneration fg = state.readEnumOption(id("frame_generation"), FrameGeneration.class);
											return fg.nvidia() && FrameGenerationRenderer.get().isDlssFgSupported();
										}, id("frame_generation"))
										.setControlHiddenWhenDisabled(true)
										.setBinding(value -> ReflexiumOptions.setFrameGenerationMultiplier(value == 1 ? 0 : value), () -> config.frameGenerationMultiplier() == 0 ? 1 : Math.min(config.frameGenerationMultiplier(), Math.max(2, FrameGenerationRenderer.get().maximumMultiplier())))))
						.addOptionGroup(builder.createOptionGroup()
								.addOption(builder.createExternalButtonOption(id("status"))
										.setName(Component.translatable("reflexium.option.status"))
										.setTooltip(Component.translatable("reflexium.option.status.tooltip"))
										.setScreenConsumer(parent -> Minecraft.getInstance().setScreenAndShow(new ReflexiumConfigScreen(parent))))));
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(ReflexiumClient.MOD_ID, path);
	}
}
