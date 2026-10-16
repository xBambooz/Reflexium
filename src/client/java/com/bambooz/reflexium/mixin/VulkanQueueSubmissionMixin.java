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
	@org.spongepowered.asm.mixin.Unique private static long reflexium$traceCalls;
	@WrapOperation(method = "close", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSynchronization2;vkQueueSubmit2KHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkSubmitInfo2$Buffer;J)I"))
	private int reflexium$timeQueue(VkQueue queue, VkSubmitInfo2.Buffer submits, long fence, Operation<Integer> original) {
		submits = VulkanLifecycle.chainSubmissionIds(queue, submits);
		long id = com.bambooz.reflexium.reflex.ReflexController.get().currentFrameId();
		if (com.bambooz.reflexium.vulkan.FrameTrace.on(id)) {
			long call = ++reflexium$traceCalls;
			for (int i = submits.position(); i < submits.limit(); i++) {
				String tag = "implicit";
				long next = submits.get(i).pNext();
				while (next != 0) {
					var h = org.lwjgl.vulkan.VkBaseOutStructure.create(next);
					if (h.sType() == org.lwjgl.vulkan.NVLowLatency2.VK_STRUCTURE_TYPE_LATENCY_SUBMISSION_PRESENT_ID_NV)
						tag = Long.toString(org.lwjgl.vulkan.VkLatencySubmissionPresentIdNV.create(next).presentID());
					next = h.pNext() == null ? 0 : h.pNext().address();
				}
				com.bambooz.reflexium.vulkan.FrameTrace.event(id, "submit call=" + call + " batch=" + (i - submits.position()) + "/" + submits.remaining()
						+ " queue=" + queue.address() + " tag=" + tag + " fence=" + (fence != 0));
			}
		}
		long timing = FrameProfiler.start(FrameProfiler.QUEUE, null);
		try { return original.call(queue, submits, fence); }
		finally { FrameProfiler.end(FrameProfiler.QUEUE, timing, null); }
	}
}
