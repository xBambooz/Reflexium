package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.upscale.dlss.StreamlineSupport;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Streamline must be initialized before the Vulkan instance exists and asks for its instance
 * extensions while RenderPearl builds the extension list. Only active when the Streamline flag is set.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanInstance")
public abstract class VulkanInstanceMixin {
	@Inject(method = "<init>", at = @At("HEAD"))
	private static void reflexium$streamlineBeforeInstance(int version, boolean validation, boolean debug, CallbackInfo ci) {
		StreamlineSupport.beforeInstance();
	}

	@ModifyReturnValue(method = "getSupportedInstanceExtensions", at = @At("RETURN"))
	private Set<String> reflexium$streamlineInstanceExtensions(Set<String> original) {
		return StreamlineSupport.extendInstanceExtensions(original, original);
	}
}
