package com.nvidiareflex.client.mixin;

import com.nvidiareflex.client.NvidiaReflexModClient;
import com.nvidiareflex.client.ReflexManager;
import com.nvidiareflex.client.ReflexMode;
import com.nvidiareflex.client.VulkanStateTracker;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Injects the Nvidia Reflex toggle into Video Settings,
 * placed after the display options section (right after Graphics API).
 * Only visible when Vulkan is active and VK_NV_low_latency2 is available.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {

	protected VideoSettingsScreenMixin() {
		super(null, null, null);
	}

	/**
	 * Inject right before the QUALITY_HEADER is added (ordinal=1, second addHeader call).
	 * This places our Reflex toggle after the display options (which include Graphics API)
	 * but before the quality options.
	 */
	@Inject(
		method = "addOptions",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/OptionsList;addHeader(Lnet/minecraft/network/chat/Component;)V",
			ordinal = 1
		)
	)
	private void nvidia_reflex$addReflexOption(CallbackInfo ci) {
		if (!NvidiaReflexModClient.isModEnabled()) {
			return;
		}

		if (!VulkanStateTracker.isVulkanActive()) {
			return;
		}

		if (!VulkanStateTracker.isLowLatencyExtensionEnabled()) {
			return;
		}

		OptionInstance<ReflexMode> reflexOption = nvidia_reflex$createReflexOption();
		this.list.addBig(reflexOption);
	}

	@Unique
	private OptionInstance<ReflexMode> nvidia_reflex$createReflexOption() {
		return new OptionInstance<>(
			"nvidia_reflex.option.reflex_mode",
			OptionInstance.cachedConstantTooltip(
				Component.translatable("nvidia_reflex.option.reflex_mode.tooltip")
			),
			(caption, value) -> Component.literal(value.getDisplayName()),
			new OptionInstance.Enum<>(
				List.of(ReflexMode.values()),
				ReflexMode.CODEC
			),
			ReflexMode.CODEC,
			NvidiaReflexModClient.getConfig().getMode(),
			value -> ReflexManager.getInstance().setMode(value)
		);
	}
}
