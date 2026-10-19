package com.bambooz.reflexium.dlss;

/** Output-budget selection from rendered-only samples, never from cap-limited FG throughput. */
public final class FrameGenerationRefreshPolicy {
	private int cap = Integer.MAX_VALUE, requested, maximum = 2, selected = 1;
	private long start, nextProbe;
	private int frames;
	private double realFps;
	private boolean probing = true;

	public void reset() { selected = 1; start = 0; nextProbe = 0; frames = 0; realFps = 0; probing = true; }
	public void cap(int value) { if (cap != value) { cap = value; reset(); } }
	public void configure(int value, int max) {
		if (requested != value || maximum != Math.max(2, max)) { requested = value; maximum = Math.max(2, max); reset(); }
	}
	public int multiplier() { return requested == 0 ? (probing ? 1 : selected) : Math.min(requested, maximum); }
	public boolean bypass() { return multiplier() < 2; }
	public boolean probing() { return requested == 0 && probing; }
	public double realFps() { return realFps; }
	public int cap() { return cap; }
	public void present(long now, boolean gameplay) {
		if (requested != 0) return;
		if (!gameplay) { start = 0; frames = 0; nextProbe = 0; probing = true; return; }
		// ponytail: periodic one-second rendered-only probes cost FG continuity. Replace with calibrated
		// uncapped render-cost telemetry if reliable limiter/FG-independent measurements become available.
		if (!probing) {
			if (now < nextProbe) return;
			probing = true; start = 0; frames = 0;
		}
		if (start == 0) { start = now; return; }
		frames++;
		long elapsed = now - start;
		if (elapsed < 1_000_000_000L) return;
		realFps = frames * 1_000_000_000.0 / elapsed;
		selected = choose(realFps, cap, maximum, selected);
		probing = false; nextProbe = now + 5_000_000_000L;
		start = 0; frames = 0;
	}
	static int choose(double fps, int target, int maximum, int previous) {
		if (!(fps > 0) || target == Integer.MAX_VALUE) return 1;
		int candidate = Math.max(1, Math.min(maximum, (int) Math.floor(target / fps)));
		// Downshift when the budget is exceeded; upshift only with 5% spare budget.
		if (candidate > previous && fps * candidate > target * 0.95)
			return Math.max(previous, Math.max(1, (int) Math.floor(target * 0.95 / fps)));
		return candidate;
	}
}
