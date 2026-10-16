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
	@org.spongepowered.asm.mixin.Shadow private long currentSubmitIndex;
	@org.spongepowered.asm.mixin.Shadow private long completedSubmitIndex;
	@WrapMethod(method = "awaitSubmitCompletion")
	private boolean reflexium$wait(long index, long timeout, Operation<Boolean> original) {
		int label = timeout == 0 ? FrameProfiler.FENCE_POLL : FrameProfiler.FENCE;
		long timing = FrameProfiler.start(label, null);
		boolean traced = timeout != 0 && reflexium$traced();
		if (traced) reflexium$trace("completion wait index=" + index + " completed=" + completedSubmitIndex);
		boolean done = false;
		try { return done = original.call(index, timeout); }
		finally {
			FrameProfiler.end(label, timing, null);
			if (traced) reflexium$trace("completion wait exit index=" + index + " done=" + done);
		}
	}
	@Unique private static boolean reflexium$traced() {
		return com.bambooz.reflexium.vulkan.FrameTrace.on(com.bambooz.reflexium.reflex.ReflexController.get().currentFrameId());
	}
	@Unique private static void reflexium$trace(String event) {
		com.bambooz.reflexium.vulkan.FrameTrace.event(com.bambooz.reflexium.reflex.ReflexController.get().currentFrameId(), event);
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
		if (reflexium$traced()) reflexium$trace("encoder submit begin index=" + currentSubmitIndex + " completed=" + completedSubmitIndex);
	}
	@Inject(method = "submit", at = @At("RETURN"))
	private void reflexium$endSubmit(CallbackInfo ci) {
		FrameProfiler.end(FrameProfiler.SUBMIT, reflexium$submitStart, null);
		if (reflexium$traced()) reflexium$trace("encoder submit end index=" + currentSubmitIndex + " completed=" + completedSubmitIndex);
	}
}
