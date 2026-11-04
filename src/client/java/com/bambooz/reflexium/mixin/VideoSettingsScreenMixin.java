package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.upscale.UpscalingRenderer;
import com.bambooz.reflexium.upscale.dlss.StreamlineSupport;
import com.bambooz.reflexium.gui.ReflexiumOptions;
import com.bambooz.reflexium.reflex.ReflexController;
import com.bambooz.reflexium.reflex.ReflexMode;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Puts the Reflex and DLSS options at the end of the display section (after Graphics API) when the driver supports them. */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
	@Unique private OptionInstance<ReflexMode> reflexium$reflexOption;
	private VideoSettingsScreenMixin(Screen lastScreen, Options options, Component title) {
		super(lastScreen, options, title);
	}

	@Inject(method = "addOptions", at = @At(value = "INVOKE", ordinal = 1,
			target = "Lnet/minecraft/client/gui/components/OptionsList;addHeader(Lnet/minecraft/network/chat/Component;)V"))
	private void reflexium$addReflexOption(CallbackInfo ci) {
		if (StreamlineSupport.reflexAvailable()) {
			reflexium$reflexOption = ReflexiumOptions.reflexMode();
			list.addBig(reflexium$reflexOption);
		}
		if (UpscalingRenderer.get().isReady()) {
			list.addBig(ReflexiumOptions.upscaler());
			list.addBig(ReflexiumOptions.upscaleQuality());
			list.addSmall(ReflexiumOptions.dlssModel(), ReflexiumOptions.fsrSharpness());
			ReflexiumOptions.addFrameGeneration(list);
		}
	}

	@Inject(method = "tick", at = @At("RETURN"))
	@SuppressWarnings("unchecked")
	private void reflexium$refreshReflex(CallbackInfo ci) {
		if (reflexium$reflexOption == null) return;
		if (list.findOption(reflexium$reflexOption) instanceof CycleButton<?> button) {
			// Refresh the displayed value without saving a forced On over the stored Off preference.
			((CycleButton<ReflexMode>) button).setValue(ReflexController.get().mode());
			button.active = ReflexController.get().isSupported();
		}
	}
}
