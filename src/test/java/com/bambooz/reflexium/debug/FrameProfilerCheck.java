package com.bambooz.reflexium.debug;

public final class FrameProfilerCheck {
	public static void main(String[] args) {
		FrameProfiler.command("off");
		if (FrameProfiler.start(FrameProfiler.EVALUATE, null) != 0) throw new AssertionError("disabled profiler");
		FrameProfiler.command("reset");
		long start = FrameProfiler.start(FrameProfiler.EVALUATE, null);
		FrameProfiler.end(FrameProfiler.EVALUATE, start, null);
		var span = FrameProfiler.command("status").getAsJsonObject("spans").getAsJsonObject("evaluate");
		if (span.get("cpu_samples").getAsLong() != 1 || span.get("cpu_us").getAsDouble() < 0
				|| span.get("gpu_samples").getAsLong() != 0) throw new AssertionError(span);
		span = FrameProfiler.command("reset").getAsJsonObject("spans").getAsJsonObject("evaluate");
		if (span.get("cpu_samples").getAsLong() != 0) throw new AssertionError("reset counters");
		FrameProfiler.command("off");
		System.out.println("Frame profiler CPU/disabled/reset checks passed");
	}
}
