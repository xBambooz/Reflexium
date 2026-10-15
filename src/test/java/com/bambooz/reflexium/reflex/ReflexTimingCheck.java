package com.bambooz.reflexium.reflex;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkLatencyTimingsFrameReportNV;
import static org.lwjgl.system.MemoryUtil.memPutLong;

public final class ReflexTimingCheck {
	public static void main(String[] args) {
		var state = new PacingState();
		state.reset();
		if (!state.shouldApply(ReflexMode.ON, 8334)) throw new AssertionError("initial configure");
		state.configuration(ReflexMode.ON, 8334, 0);
		if (state.ownsCap(ReflexMode.ON, 8334)) throw new AssertionError("configuration is not completed sleep");
		state.sleepResult(0);
		if (!state.ownsCap(ReflexMode.ON, 8334) || state.ownsCap(ReflexMode.ON, 10000)) throw new AssertionError("cap ownership must match effective interval");
		state.configuration(ReflexMode.ON_BOOST, 8334, -1);
		if (state.applied != ReflexMode.ON || state.canSleep() || state.ownsCap(ReflexMode.ON_BOOST, 8334)
				|| state.shouldApply(ReflexMode.ON_BOOST, 8334)) throw new AssertionError("failed config must retain old record, fall back, and not spam");
		for (int result : new int[]{2, -4}) {
			state.reset(); // explicit mode retry or new swapchain/device
			state.configuration(ReflexMode.ON_BOOST, 8334, 0);
			state.sleepResult(result);
			for (int frame = 0; frame < 10; frame++) if (state.canSleep() || state.ownsCap(ReflexMode.ON_BOOST, 8334)) throw new AssertionError("failed wait must stay in fallback");
		}
		state.reset(); state.configuration(ReflexMode.OFF, 0, 0); state.sleepResult(0);
		if (!state.canSleep() || state.ownsCap(ReflexMode.OFF, 0)) throw new AssertionError("Off reporting sleep never owns cap");
		state.reset(); state.configuration(ReflexMode.ON, 8334, 0); state.sleepResult(0);
		if (!state.ownsCap(ReflexMode.ON, 8334)) throw new AssertionError("lifecycle recovery");
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
		System.out.println("Reflex GPU-complete latency checks passed");
	}
}
