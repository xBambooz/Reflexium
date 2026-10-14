package com.bambooz.reflexium.reflex;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkLatencyTimingsFrameReportNV;
import static org.lwjgl.system.MemoryUtil.memPutLong;

public final class ReflexTimingCheck {
	public static void main(String[] args) {
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
