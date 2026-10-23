package com.bambooz.reflexium.upscale;

/** One native/DLSS comparison per configuration, using asynchronously collected world timestamps. */
public final class UpscalerPerformanceGuard {
	private int phase;
	private long started, last, intervals, elapsed, tag;
	private double nativeMs, dlssMs;
	public void reset() { phase = 0; started = last = intervals = elapsed = 0; nativeMs = dlssMs = 0; tag++; }
	public boolean probing() { return phase < 3; }
	public boolean nativePhase() { return phase < 2; }
	public boolean nativeWins() { return phase == 3 && !faster(nativeMs, dlssMs); }
	public static boolean faster(double nativeMs, double dlssMs) { return nativeMs > 0 && dlssMs > 0 && dlssMs <= nativeMs * 0.95; }
	public long sampleTag(long now) { return phase > 0 && phase < 3 && now - started >= 1_000_000_000L ? tag : 0; }
	public double nativeMs() { return nativeMs; }
	public double dlssMs() { return dlssMs; }
	public void update(long now, boolean gameplay, long gpuSamples, double gpuMs) {
		if (phase == 3) return;
		if (!gameplay) { started = last = intervals = elapsed = 0; tag++; return; }
		if (started == 0) { if (phase == 0) phase = 1; started = now; last = now; return; }
		if (now - started >= 1_000_000_000L && last != 0) { elapsed += now - last; intervals++; }
		last = now;
		if (now - started < 3_000_000_000L) return;
		double ms = gpuSamples >= 8 && gpuMs > 0 ? gpuMs : intervals == 0 ? 0 : elapsed / (intervals * 1_000_000.0);
		if (!(ms > 0) || !Double.isFinite(ms)) return;
		if (phase == 1) nativeMs = ms; else dlssMs = ms;
		phase++; started = last = intervals = elapsed = 0; tag++;
	}
}
