package com.bambooz.reflexium.framegen;

/** Rates of successful submission operations in one shared window, never physical scanout. */
public final class PresentationStats {
	private long start, last, real, generated;
	private int realRate, totalRate;
	public void reset() { start = last = real = generated = 0; realRate = totalRate = 0; }
	public void record(long now, long realCount, long generatedCount) {
		if (start == 0) start = now;
		last = now; real += realCount; generated += generatedCount;
		long elapsed = now - start;
		if (elapsed >= 1_000_000_000L) {
			realRate = (int) Math.round(real * 1_000_000_000.0 / elapsed);
			totalRate = (int) Math.round((real + generated) * 1_000_000_000.0 / elapsed);
			start = now; real = generated = 0;
		}
	}
	public int realRate(long now) { return now - last > 1_500_000_000L ? 0 : realRate; }
	public int totalRate(long now) { return now - last > 1_500_000_000L ? 0 : totalRate; }
}
