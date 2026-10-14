package com.bambooz.reflexium.mixin;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ensures all color textures support storage image usage (VK_IMAGE_USAGE_STORAGE_BIT).
 * This allows DLSS SR, FSR 3.1, and Frame Generation to write directly into render targets,
 * eliminating intermediate output images, per-frame copies, and extra pipeline flushes.
 */
@Mixin(VulkanConst.class)
public abstract class VulkanConstMixin {
	@Inject(method = "textureUsageToVk", at = @At("RETURN"), cancellable = true)
	private static void reflexium$addStorageUsage(int usage, GpuFormat format, CallbackInfoReturnable<Integer> cir) {
		if (format.hasColorAspect()) {
			cir.setReturnValue(cir.getReturnValue() | VK10.VK_IMAGE_USAGE_STORAGE_BIT);
		}
	}
}
