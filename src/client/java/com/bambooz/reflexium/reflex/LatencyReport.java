package com.bambooz.reflexium.reflex;

import org.lwjgl.vulkan.VkLatencyTimingsFrameReportNV;

import java.util.Locale;

/** One driver frame report reduced to the F3 latency line: input sample (or simulation start) to GPU completion. */
record LatencyReport(boolean available, String reason, double totalMs, double simMs, double submitMs, double gpuMs) {
	static LatencyReport from(VkLatencyTimingsFrameReportNV report) {
		long start = report.inputSampleTimeUs() > 0 ? report.inputSampleTimeUs() : report.simStartTimeUs();
		if (start <= 0 || report.gpuRenderEndTimeUs() <= start || report.gpuRenderStartTimeUs() <= 0
				|| report.gpuRenderEndTimeUs() < report.gpuRenderStartTimeUs()) return unavailable("incomplete driver data");
		if (report.driverStartTimeUs() > 0 && report.driverEndTimeUs() > 0
				&& report.driverStartTimeUs() > report.driverEndTimeUs()) return unavailable("inconsistent driver timestamps");
		return new LatencyReport(true, "", (report.gpuRenderEndTimeUs() - start) / 1000.0,
				span(report.simStartTimeUs(), report.simEndTimeUs()),
				span(report.renderSubmitStartTimeUs(), report.renderSubmitEndTimeUs()),
				span(report.gpuRenderStartTimeUs(), report.gpuRenderEndTimeUs()));
	}

	static LatencyReport unavailable(String reason) {
		return new LatencyReport(false, reason, 0, -1, -1, -1);
	}

	/** Streamline ReflexReport timestamps: input, simStart, simEnd, submitStart, submitEnd, presentStart,
	 * presentEnd, driverStart, driverEnd, osQueueStart, osQueueEnd, gpuStart, gpuEnd. */
	static LatencyReport fromStreamline(long[] t) {
		if (t.length < 13) return unavailable("incomplete Streamline report");
		long start = t[0] > 0 ? t[0] : t[1];
		if (start <= 0 || t[12] <= start || t[11] <= 0 || t[12] < t[11]) return unavailable("incomplete Streamline report");
		return new LatencyReport(true, "", (t[12] - start) / 1000.0, span(t[1], t[2]), span(t[3], t[4]), span(t[11], t[12]));
	}

	String format() {
		if (!available) {
			return "Reflex latency: " + reason;
		}
		StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "Reflex render latency: %.1f ms", totalMs));
		append(line, "sim", simMs);
		append(line, "submit", submitMs);
		append(line, "GPU", gpuMs);
		return line.toString();
	}

	private static void append(StringBuilder line, String label, double ms) {
		if (ms >= 0) {
			line.append(String.format(Locale.ROOT, ", %s %.1f", label, ms));
		}
	}

	/** Milliseconds between two driver microsecond timestamps, or -1 when either is missing. */
	private static double span(long start, long end) {
		return start > 0 && end > start ? (end - start) / 1000.0 : -1;
	}
}
