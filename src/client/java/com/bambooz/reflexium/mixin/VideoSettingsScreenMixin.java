package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.gui.ReflexiumOptions;
import com.bambooz.reflexium.vulkan.VulkanSession;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Puts the Reflex mode at the end of the display section (after Graphics API) when the driver supports it. */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
	private VideoSettingsScreenMixin(Screen lastScreen, Options options, Component title) {
		super(lastScreen, options, title);
	}

	@Inject(method = "addOptions", at = @At(value = "INVOKE", ordinal = 1,
			target = "Lnet/minecraft/client/gui/components/OptionsList;addHeader(Lnet/minecraft/network/chat/Component;)V"))
	private void reflexium$addReflexOption(CallbackInfo ci) {
		VulkanSession session = VulkanSession.current();
		if (session != null && session.lowLatency2()) {
			list.addSmall(ReflexiumOptions.reflexMode(), ReflexiumOptions.frameLimiter());
		}
	}
}
