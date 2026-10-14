package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.debug.FrameProfiler;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanSubmitTimingMixin {
	@WrapMethod(method = "awaitSubmitCompletion")
	private boolean reflexium$wait(long index, long timeout, Operation<Boolean> original) {
		int label = timeout == 0 ? FrameProfiler.FENCE_POLL : FrameProfiler.FENCE;
		long timing = FrameProfiler.start(label, null);
		try { return original.call(index, timeout); }
		finally { FrameProfiler.end(label, timing, null); }
	}
	@com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "submit", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandPool;reset()V"))
	private void reflexium$timeReset(com.mojang.renderpearl.backend.vulkan.VulkanCommandPool pool, Operation<Void> original) {
		long timing = FrameProfiler.start(FrameProfiler.POOL_RESET, null);
		try { original.call(pool); }
		finally { FrameProfiler.end(FrameProfiler.POOL_RESET, timing, null); }
	}
	@Unique private long reflexium$submitStart;
	@Inject(method = "submit", at = @At("HEAD"))
	private void reflexium$startSubmit(CallbackInfo ci) {
		reflexium$submitStart = FrameProfiler.start(FrameProfiler.SUBMIT, null);
	}
	@Inject(method = "submit", at = @At("RETURN"))
	private void reflexium$endSubmit(CallbackInfo ci) {
		FrameProfiler.end(FrameProfiler.SUBMIT, reflexium$submitStart, null);
	}
}
