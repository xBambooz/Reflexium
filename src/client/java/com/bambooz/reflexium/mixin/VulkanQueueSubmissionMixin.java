package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.vulkan.VulkanLifecycle;
import org.lwjgl.vulkan.VkSubmitInfo2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.bambooz.reflexium.debug.FrameProfiler;
import org.lwjgl.vulkan.VkQueue;

/** Tags every queue submission with the current Reflex frame id (VkLatencySubmissionPresentIdNV). */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanQueue$Submission")
public abstract class VulkanQueueSubmissionMixin {
	@WrapOperation(method = "close", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSynchronization2;vkQueueSubmit2KHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkSubmitInfo2$Buffer;J)I"))
	private int reflexium$timeQueue(VkQueue queue, VkSubmitInfo2.Buffer submits, long fence, Operation<Integer> original) {
		long timing = FrameProfiler.start(FrameProfiler.QUEUE, null);
		try { return original.call(queue, submits, fence); }
		finally { FrameProfiler.end(FrameProfiler.QUEUE, timing, null); }
	}
	@ModifyArg(method = "close", index = 1, at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRSynchronization2;vkQueueSubmit2KHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkSubmitInfo2$Buffer;J)I"))
	private VkSubmitInfo2.Buffer reflexium$tagSubmission(VkSubmitInfo2.Buffer submits) {
		return VulkanLifecycle.chainSubmissionIds(submits);
	}
}
