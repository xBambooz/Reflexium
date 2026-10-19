package com.bambooz.reflexium.dlss;

/** Output-budget selection from post-sleep driver render cost, without interrupting generation. */
public final class FrameGenerationRefreshPolicy {
	private int cap = Integer.MAX_VALUE, requested, maximum = 2, selected = 1;
	private long nextSelection;
	private double realFps;

	public void reset() { selected = 1; nextSelection = 0; realFps = 0; }
	public void cap(int value) { if (cap != value) { cap = value; reset(); } }
	public void configure(int value, int max) {
		if (requested != value || maximum != Math.max(2, max)) { requested = value; maximum = Math.max(2, max); reset(); }
	}
	public int multiplier() { return requested == 0 ? selected : Math.min(requested, maximum); }
	public boolean bypass() { return multiplier() < 2; }
	public boolean probing() { return false; }
	public double realFps() { return realFps; }
	public int cap() { return cap; }
	public void present(long now, boolean gameplay, double renderCostMs) {
		if (requested != 0) return;
		if (!gameplay || !(renderCostMs > 0) || !Double.isFinite(renderCostMs)) return;
		// Driver sim-start follows Reflex sleep. This includes GPU/FG work and queue delay, so it is
		// a conservative throughput estimate, not a measurement of FG-off performance.
		double sample = 1000.0 / renderCostMs;
		realFps = realFps == 0 ? sample : realFps * 0.9 + sample * 0.1;
		if (now < nextSelection) return;
		selected = choose(realFps, cap, maximum, selected);
		nextSelection = now + 5_000_000_000L;
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
