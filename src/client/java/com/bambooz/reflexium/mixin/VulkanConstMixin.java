package com.bambooz.reflexium.mixin;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives RGBA8 render targets storage image usage (VK_IMAGE_USAGE_STORAGE_BIT).
 * This allows DLSS SR, FSR 3.1, and Frame Generation to write directly into render targets,
 * eliminating intermediate output images, per-frame copies, and extra pipeline flushes.
 */
@Mixin(VulkanConst.class)
public abstract class VulkanConstMixin {
	@Inject(method = "textureUsageToVk", at = @At("RETURN"), cancellable = true)
	private static void reflexium$addStorageUsage(int usage, GpuFormat format, CallbackInfoReturnable<Integer> cir) {
		// Only RGBA8 render targets (the main target family): storage support for RGBA8_UNORM is mandatory in
		// Vulkan, while other formats (RGB8, 16-bit RGB) may not support storage on AMD or Intel drivers.
		if (format == GpuFormat.RGBA8_UNORM && (usage & 8) != 0) {
			cir.setReturnValue(cir.getReturnValue() | VK10.VK_IMAGE_USAGE_STORAGE_BIT);
		}
	}
}
