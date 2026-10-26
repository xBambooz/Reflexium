package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.debug.FrameProfiler;
import com.bambooz.reflexium.reflex.ReflexController;
import com.bambooz.reflexium.vulkan.FrameTrace;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandPool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanSubmitTimingMixin {
	@Shadow private long currentSubmitIndex;
	@Shadow private long completedSubmitIndex;
	@WrapMethod(method = "awaitSubmitCompletion")
	private boolean reflexium$wait(long index, long timeout, Operation<Boolean> original) {
		int label = timeout == 0 ? FrameProfiler.FENCE_POLL : FrameProfiler.FENCE;
		long timing = FrameProfiler.start(label, null);
		long worldTiming = timeout != 0 && FrameProfiler.inWorld() ? FrameProfiler.start(FrameProfiler.WORLD_FENCE, null) : 0;
		boolean traced = timeout != 0 && reflexium$traced();
		if (traced) reflexium$trace("completion wait index=" + index + " completed=" + completedSubmitIndex);
		boolean done = false;
		try { return done = original.call(index, timeout); }
		finally {
			FrameProfiler.end(label, timing, null);
			FrameProfiler.end(FrameProfiler.WORLD_FENCE, worldTiming, null);
			if (traced) reflexium$trace("completion wait exit index=" + index + " done=" + done);
		}
	}
	@Unique private static boolean reflexium$traced() {
		return FrameTrace.on(ReflexController.get().currentFrameId());
	}
	@Unique private static void reflexium$trace(String event) {
		FrameTrace.event(ReflexController.get().currentFrameId(), event);
	}
	@WrapOperation(method = "submit", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandPool;reset()V"))
	private void reflexium$timeReset(VulkanCommandPool pool, Operation<Void> original) {
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
