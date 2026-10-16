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
		for (int result : new int[]{-4, -1000001004}) { // VK_ERROR_DEVICE_LOST, VK_ERROR_OUT_OF_DATE_KHR
			state.reset(); // explicit mode retry or new swapchain/device
			state.configuration(ReflexMode.ON_BOOST, 8334, 0);
			state.sleepResult(result);
			state.signalObserved();
			for (int frame = 0; frame < 10; frame++) if (state.canSleep() || state.ownsCap(ReflexMode.ON_BOOST, 8334)) throw new AssertionError("failed wait must stay in fallback");
		}
		// VK_TIMEOUT: the signal is still owed. Suspend new sleeps without failing, resume once it is observed.
		state.reset(); state.configuration(ReflexMode.ON, 8334, 0); state.sleepResult(0);
		long timeouts = state.timeouts;
		state.sleepResult(PacingState.VK_TIMEOUT);
		if (state.failed || !state.suspended || state.timeouts != timeouts + 1) throw new AssertionError("timeout is transient");
		for (int frame = 0; frame < 10; frame++) if (state.canSleep() || state.ownsCap(ReflexMode.ON, 8334)) throw new AssertionError("no sleep or cap ownership while the signal is pending");
		state.signalObserved();
		if (!state.canSleep() || state.recoveries != 1) throw new AssertionError("observed signal must resume pacing without a mode change");
		if (state.ownsCap(ReflexMode.ON, 8334)) throw new AssertionError("cap ownership needs a new completed sleep");
		state.sleepResult(0);
		if (!state.ownsCap(ReflexMode.ON, 8334) || state.timeoutStreak != 0) throw new AssertionError("completed sleep clears the streak");
		state.signalObserved();
		if (state.recoveries != 1) throw new AssertionError("only suspended states count as recoveries");
		// Isolated timeouts separated by completed sleeps never latch.
		for (int i = 0; i < 20; i++) {
			state.sleepResult(PacingState.VK_TIMEOUT); state.signalObserved(); state.sleepResult(0);
			if (!state.canSleep()) throw new AssertionError("isolated timeout " + i + " must not latch");
		}
		// Every resumed sleep timing out again is a genuine failure: latch, stay diagnosable, even after the signal.
		for (int i = 1; i <= PacingState.MAX_TIMEOUT_STREAK; i++) {
			state.sleepResult(PacingState.VK_TIMEOUT);
			if (state.failed != (i == PacingState.MAX_TIMEOUT_STREAK)) throw new AssertionError("streak " + i);
			state.signalObserved();
		}
		if (state.canSleep() || state.lastResult != PacingState.VK_TIMEOUT) throw new AssertionError("timeout streak must fall back and keep its result");
		// Mode change retries; lifetime counters survive for diagnostics.
		long recoveries = state.recoveries, failures = state.failures, configurations = state.configurations;
		state.sleepResult(PacingState.VK_TIMEOUT);
		if (state.failures != failures) throw new AssertionError("a latched failure counts once, not per later result");
		state.reset(); state.configuration(ReflexMode.ON, 8334, 0);
		if (!state.canSleep() || state.recoveries != recoveries || state.timeouts == 0) throw new AssertionError("reset retries and keeps counters");
		if (state.failures != failures || state.configurations != configurations + 1) throw new AssertionError("measured-span failure and configuration counters");
		state.configuration(ReflexMode.ON, 8334, -1);
		if (state.failures != failures + 1 || state.configurationFailures == 0) throw new AssertionError("failed configuration counts as a failure");
		state.reset(); state.configuration(ReflexMode.ON, 8334, 0);
		// A reset while the driver still owes a signal must not re-enable sleep until it is observed (controller sets this on poll).
		state.suspended = true;
		if (state.canSleep()) throw new AssertionError("pending signal after reset");
		state.signalObserved();
		if (!state.canSleep()) throw new AssertionError("pending signal after reset recovers");
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
		System.out.println("Reflex GPU-complete latency and sleep timeout recovery checks passed");
	}
}
