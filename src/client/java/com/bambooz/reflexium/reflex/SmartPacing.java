package com.bambooz.reflexium.reflex;

/** Half-second frame-time mean with 320/280 FPS hysteresis, independent of generated frames. */
final class SmartPacing {
	private long start;
	private int frames;
	boolean paused;

	void reset() {
		start = 0;
		frames = 0;
		paused = false;
	}

	void update(long now, boolean allowed) {
		if (!allowed) {
			reset();
			return;
		}
		if (start == 0) {
			start = now;
			return;
		}
		frames++;
		long elapsed = now - start;
		if (elapsed < 500_000_000L) return;
		double meanNs = (double) elapsed / frames;
		if (meanNs < 1_000_000_000.0 / 320) paused = true;
		else if (meanNs > 1_000_000_000.0 / 280) paused = false;
		start = now;
		frames = 0;
	}
}
