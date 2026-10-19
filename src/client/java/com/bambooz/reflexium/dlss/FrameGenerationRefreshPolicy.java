package com.bambooz.reflexium.dlss;

/** Static NGX generation with a rendered-only fallback when real frames already fill the display. */
public final class FrameGenerationRefreshPolicy {
	private int cap = Integer.MAX_VALUE;
	private boolean bypass = true;
	private long start;
	private int frames;
	private double realFps;

	public void reset() { bypass = true; start = 0; frames = 0; realFps = 0; }
	public void cap(int value) { if (cap != value) { cap = value; reset(); } }
	public boolean bypass() { return cap != Integer.MAX_VALUE && bypass; }
	public double realFps() { return realFps; }
	public int cap() { return cap; }
	public void present(long now, boolean gameplay) {
		if (!gameplay) { start = 0; frames = 0; return; }
		if (start == 0) { start = now; return; }
		frames++;
		long elapsed = now - start;
		if (elapsed < 1_000_000_000L) return;
		realFps = frames * 1_000_000_000.0 / elapsed;
		// Hysteresis prevents switching on ordinary cap jitter. Never infer uncapped base FPS by multiplying FG FPS.
		if (realFps >= cap * 0.95) bypass = true;
		else if (realFps < cap * 0.80) bypass = false;
		start = now; frames = 0;
	}
}
