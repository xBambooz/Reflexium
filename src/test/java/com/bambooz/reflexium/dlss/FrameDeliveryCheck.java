package com.bambooz.reflexium.dlss;

import com.bambooz.reflexium.vulkan.VulkanLifecycle;
import com.bambooz.reflexium.vulkan.VulkanSession;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.util.Set;

public final class FrameDeliveryCheck {
	public static void main(String[] args) {
		if (VulkanLifecycle.validImageCount(8, 2, 3) != 3 || VulkanLifecycle.validImageCount(3, 2, 0) != 3
				|| VulkanLifecycle.validImageCount(1, 2, 0) != 2) throw new AssertionError("surface bounds");
		for (int revision = 0; revision <= 3; revision++) {
			var session = new VulkanSession(null, null, null, 0, "", "", revision > 0, revision, false, false, Set.of());
			if (session.explicitAttributionSupported() != (revision >= 3)) throw new AssertionError("extension revision");
		}
		var stats = new PresentationStats();
		stats.record(1, 0, 0);
		for (int i = 1; i <= 100; i++) stats.record(1 + i * 10_000_000L, 1, 5);
		if (stats.realRate(1_000_000_001L) != 100 || stats.totalRate(1_000_000_001L) != 600) throw new AssertionError("matching submission window");
		stats.reset();
		if (stats.totalRate(1_000_000_002L) != 0) throw new AssertionError("suppression reset");
		stats.record(2_000_000_001L, 1, 0); stats.record(3_000_000_001L, 99, 0);
		if (stats.totalRate(3_000_000_001L) != 100 || stats.totalRate(5_000_000_001L) != 0) throw new AssertionError("native and stale window");
		var fg = FrameGenerationRenderer.get();
		try (var stack = MemoryStack.stackPush()) {
			for (int feedback : new int[]{0, 1, -1}) {
				fg.beginPresentBatch(6); fg.beginPresent(true);
				var info = fg.chainMetering(VkPresentInfoKHR.calloc(stack).sType$Default());
				VkSetPresentConfigNV.create(info.pNext()).presentConfigFeedback(feedback);
				fg.onPresentResult(info, 0);
				if (fg.batchRejected() != (feedback != 0)) throw new AssertionError("metering feedback");
				fg.endPresentBatch();
			}
			for (int failureIndex : new int[]{0, 2}) {
				fg.beginPresentBatch(6);
				for (int i = 0; i <= failureIndex; i++) {
					var info = fg.chainMetering(VkPresentInfoKHR.calloc(stack).sType$Default());
					if (info.pNext() != 0) VkSetPresentConfigNV.create(info.pNext()).presentConfigFeedback(0);
					fg.onPresentResult(info, i == failureIndex ? KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR : 0);
				}
				if (!fg.batchRejected() || fg.submittedFps() != 0) throw new AssertionError("partial batch failure");
				fg.endPresentBatch();
			}
			fg.beginPresentBatch(2);
			fg.onPresentResult(VkPresentInfoKHR.calloc(stack).sType$Default(), KHRSwapchain.VK_SUBOPTIMAL_KHR);
			if (!fg.batchRejected()) throw new AssertionError("suboptimal stops interpolation on valid surface");
			fg.endPresentBatch(); fg.invalidate(); fg.retry(); fg.skipGeneratedFrames();
			if (fg.generatedCount() != 0 || fg.submittedFps() != 0) throw new AssertionError("history/menu/resize warmup");
		}
		System.out.println("Frame delivery lifecycle checks passed");
	}
}
