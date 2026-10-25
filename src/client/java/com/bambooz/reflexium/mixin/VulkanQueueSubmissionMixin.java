package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.debug.FrameProfiler;
import com.bambooz.reflexium.reflex.ReflexController;
import com.bambooz.reflexium.vulkan.FrameTrace;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Profiles queue submissions and records their batch shape for the frame trace. */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanQueue$Submission")
public abstract class VulkanQueueSubmissionMixin {
	@Unique private static long reflexium$traceCalls;
	@WrapOperation(method = "close", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSynchronization2;vkQueueSubmit2KHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkSubmitInfo2$Buffer;J)I"))
	private int reflexium$timeQueue(VkQueue queue, VkSubmitInfo2.Buffer submits, long fence, Operation<Integer> original) {
		long id = ReflexController.get().currentFrameId();
		if (FrameTrace.on(id)) {
			long call = ++reflexium$traceCalls;
			for (int i = submits.position(); i < submits.limit(); i++) {
				FrameTrace.event(id, "submit call=" + call + " batch=" + (i - submits.position()) + "/" + submits.remaining()
						+ " queue=" + queue.address() + " fence=" + (fence != 0));
			}
		}
		long timing = FrameProfiler.start(FrameProfiler.QUEUE, null);
		try { return original.call(queue, submits, fence); }
		finally { FrameProfiler.end(FrameProfiler.QUEUE, timing, null); }
	}
}
