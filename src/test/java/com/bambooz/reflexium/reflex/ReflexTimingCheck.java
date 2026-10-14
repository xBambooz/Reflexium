package com.bambooz.reflexium.reflex;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkLatencyTimingsFrameReportNV;
import org.lwjgl.vulkan.VkLatencySubmissionPresentIdNV;
import org.lwjgl.vulkan.VkSubmitInfo2;
import com.bambooz.reflexium.debug.DebugSettings;
import com.bambooz.reflexium.vulkan.VulkanLifecycle;
import com.bambooz.reflexium.vulkan.VulkanSession;
import static org.lwjgl.system.MemoryUtil.memPutLong;

public final class ReflexTimingCheck {
	public static void main(String[] args) throws ReflectiveOperationException {
		var pacing = new ReflexController.SmartPacing();
		long now = 1;
		pacing.update(now, true);
		for (int i = 0; i < 199; i++) pacing.update(now += 2_500_000, true);
		if (pacing.paused) throw new AssertionError("must observe half a second before pausing");
		pacing.update(now += 2_500_000, true);
		if (!pacing.paused) throw new AssertionError("400 FPS must pause pacing");
		for (int i = 0; i < 151; i++) pacing.update(now += 3_333_333, true);
		if (!pacing.paused) throw new AssertionError("300 FPS must retain paused state");
		for (int i = 0; i < 250; i++) pacing.update(now += 4_000_000, true);
		if (pacing.paused) throw new AssertionError("250 FPS must resume pacing");
		for (int i = 0; i < 302; i++) pacing.update(now += 3_333_333, true);
		if (pacing.paused) throw new AssertionError("300 FPS must retain sleeping state");
		for (int i = 0; i < 400; i++) pacing.update(now += 2_500_000, true);
		if (!pacing.paused) throw new AssertionError("400 FPS must pause again");
		pacing.update(++now, false);
		if (pacing.paused) throw new AssertionError("FG, caps and disabled Smart Reflex must reset immediately");
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var report = VkLatencyTimingsFrameReportNV.calloc(stack).sType$Default();
			memPutLong(report.address() + VkLatencyTimingsFrameReportNV.SIMSTARTTIMEUS, 1000);
			memPutLong(report.address() + VkLatencyTimingsFrameReportNV.INPUTSAMPLETIMEUS, 1100);
			memPutLong(report.address() + VkLatencyTimingsFrameReportNV.PRESENTENDTIMEUS, 1500);
			memPutLong(report.address() + VkLatencyTimingsFrameReportNV.GPURENDERSTARTTIMEUS, 1600);
			memPutLong(report.address() + VkLatencyTimingsFrameReportNV.GPURENDERENDTIMEUS, 3100);
			var latency = ReflexController.LatencyReport.from(report);
			if (!latency.available() || latency.totalMs() != 2 || latency.gpuMs() != 1.5)
				throw new AssertionError("Latency must include GPU completion: " + latency);
			memPutLong(report.address() + VkLatencyTimingsFrameReportNV.INPUTSAMPLETIMEUS, 0);
			if (ReflexController.LatencyReport.from(report).totalMs() != 2.1) throw new AssertionError("simulation fallback");
			memPutLong(report.address() + VkLatencyTimingsFrameReportNV.DRIVERSTARTTIMEUS, 2000);
			memPutLong(report.address() + VkLatencyTimingsFrameReportNV.DRIVERENDTIMEUS, 1900);
			if (ReflexController.LatencyReport.from(report).available()) throw new AssertionError("inconsistent driver report");
			memPutLong(report.address() + VkLatencyTimingsFrameReportNV.DRIVERSTARTTIMEUS, 0);
			memPutLong(report.address() + VkLatencyTimingsFrameReportNV.GPURENDERENDTIMEUS, 0);
			if (ReflexController.LatencyReport.from(report).available()) throw new AssertionError("incomplete GPU report");
		}
		checkSubmissionAttribution();
		System.out.println("Reflex latency and submission attribution checks passed");
	}

	/** Exercises actual pNext packets without creating a Vulkan device or invoking the driver. */
	private static void checkSubmissionAttribution() throws ReflectiveOperationException {
		VulkanSession.publish(new VulkanSession(null, null, null, 0x10de, "fixture", "fixture", true, true, false, java.util.Set.of()));
		var frameId = ReflexController.class.getDeclaredField("frameId");
		var frameOpen = ReflexController.class.getDeclaredField("frameOpen");
		frameId.setAccessible(true); frameOpen.setAccessible(true);
		frameId.setLong(ReflexController.get(), 5); frameOpen.setBoolean(ReflexController.get(), true);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			if (DebugSettings.reflexSubmission) throw new AssertionError("implicit attribution must be the default");
			var submits = VkSubmitInfo2.calloc(2, stack);
			for (int i = 0; i < 2; i++) submits.get(i).sType$Default();
			VulkanLifecycle.chainSubmissionIds(submits);
			if (submits.get(0).pNext() != 0 || submits.get(1).pNext() != 0) throw new AssertionError("implicit path must omit tags");
			DebugSettings.reflexSubmission = true;
			VulkanLifecycle.chainSubmissionIds(submits);
			for (int i = 0; i < 2; i++) {
				if (VkLatencySubmissionPresentIdNV.create(submits.get(i).pNext()).presentID() != 5) throw new AssertionError("tag all batches");
			}
			long previous = submits.get(0).pNext();
			DebugSettings.reflexSubmission = false;
			VulkanLifecycle.chainSubmissionIds(submits);
			var cleared = VkLatencySubmissionPresentIdNV.create(submits.get(0).pNext());
			if (cleared.presentID() != 0 || cleared.pNext() != previous) throw new AssertionError("clear while preserving pNext");
			// A second queue can still inherit its last explicit id. Unframed submits must clear it too.
			frameOpen.setBoolean(ReflexController.get(), false);
			var anotherQueue = VkSubmitInfo2.calloc(1, stack); anotherQueue.get(0).sType$Default();
			VulkanLifecycle.chainSubmissionIds(anotherQueue);
			if (VkLatencySubmissionPresentIdNV.create(anotherQueue.get(0).pNext()).presentID() != 0) throw new AssertionError("clear every queue");
		} finally {
			DebugSettings.reflexSubmission = false;
			frameOpen.setBoolean(ReflexController.get(), false);
			VulkanSession.clear();
		}
	}
}
