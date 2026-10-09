package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.vulkan.VulkanLifecycle;
import org.lwjgl.vulkan.VkSubmitInfo2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Tags every queue submission with the current Reflex frame id (VkLatencySubmissionPresentIdNV). */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanQueue$Submission")
public abstract class VulkanQueueSubmissionMixin {
	@ModifyArg(method = "close", index = 1, at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRSynchronization2;vkQueueSubmit2KHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkSubmitInfo2$Buffer;J)I"))
	private VkSubmitInfo2.Buffer reflexium$tagSubmission(VkSubmitInfo2.Buffer submits) {
		return VulkanLifecycle.chainSubmissionIds(submits);
	}
}
