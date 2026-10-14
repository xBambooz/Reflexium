package com.bambooz.reflexium.compat;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.dlss.UpscaleQuality;
import com.bambooz.reflexium.dlss.Upscaler;
import com.bambooz.reflexium.dlss.DlssModel;
import com.bambooz.reflexium.dlss.UpscalingRenderer;
import com.bambooz.reflexium.gui.ReflexiumOptions;
import com.bambooz.reflexium.gui.ReflexiumConfigScreen;
import com.bambooz.reflexium.reflex.ReflexController;
import com.bambooz.reflexium.reflex.ReflexMode;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

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
										.setBinding(ReflexController.get()::setMode, config::reflexMode)))
						.addOptionGroup(builder.createOptionGroup()
								.setName(Component.translatable("reflexium.group.upscaling"))
								.addOption(builder.createEnumOption(id("upscaler"), Upscaler.class)
										.setName(Component.translatable("reflexium.option.upscaler"))
										.setTooltip(Component.translatable("reflexium.option.upscaler.tooltip"))
										.setElementNameProvider(Upscaler::label)
										.setDefaultValue(Upscaler.AUTO)
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
										.setDefaultValue(DlssModel.AUTO)
										.setStorageHandler(SAVED_ON_SET)
										.setBinding(ReflexiumOptions::setDlssModel, config::dlssModel)
										.setEnabled(UpscalingRenderer.get().dlssReady()))
								.addOption(builder.createIntegerOption(id("fsr_sharpness"))
										.setName(Component.translatable("reflexium.option.fsr_sharpness"))
										.setTooltip(Component.translatable("reflexium.option.fsr_sharpness.tooltip"))
										.setRange(0, 100, 5)
										.setValueFormatter(value -> Component.literal(value + "%"))
										.setDefaultValue(20)
										.setStorageHandler(SAVED_ON_SET)
										.setBinding(config::setFsrSharpness, config::fsrSharpness))
								.addOption(builder.createBooleanOption(id("dlss_detection"))
										.setName(Component.translatable("reflexium.option.dlss_detection"))
										.setTooltip(Component.translatable("reflexium.option.dlss_detection.tooltip"))
										.setFlags(OptionFlag.REQUIRES_GAME_RESTART)
										.setDefaultValue(true)
										.setStorageHandler(SAVED_ON_SET)
										.setBinding(config::setDlssDetection, config::dlssDetection)))
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
