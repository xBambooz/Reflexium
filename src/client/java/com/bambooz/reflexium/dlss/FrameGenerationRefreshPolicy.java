package com.bambooz.reflexium.dlss;

/** Selects an output budget from measured uncapped gameplay presents. Render thread only. */
public final class FrameGenerationRefreshPolicy {
	private int cap = Integer.MAX_VALUE, requested, maximum = 2, selected = 1;
	private long nextSelection, bypassSince, nextProbe;
	private double realFps;
	private boolean force, probing;
	private long probes;
	public void reset() { selected = 1; nextSelection = bypassSince = nextProbe = 0; realFps = 0; probing = false; }
	public void force(boolean value) { if (force != value) { force = value; reset(); } }
	public void cap(int value) { if (cap != value) { cap = value; reset(); } }
	public void configure(int value, int max) {
		if (requested != value || maximum != Math.max(2, max)) { requested = value; maximum = Math.max(2, max); reset(); }
	}
	public int multiplier() { return force && requested != 0 ? Math.min(requested, maximum) : selected; }
	public boolean bypass() { return multiplier() < 2; }
	public boolean probing() { return probing; }
	public long probes() { return probes; }
	public double realFps() { return realFps; }
	public int cap() { return cap; }
	public void present(long now, boolean gameplay, double measuredRate, double gpuCostMs, double fgCostMs) {
		if (force && requested != 0) return;
		if (!gameplay) { bypassSince = 0; return; }
		int upper = requested == 0 ? maximum : Math.min(requested, maximum);
		if (bypass()) {
			if (bypassSince == 0) bypassSince = now;
			// The delivery window may straddle the transition. Wait two windows before trusting it.
			if (now - bypassSince < 2_000_000_000L || !(measuredRate > 0) || !Double.isFinite(measuredRate)) return;
			realFps = measuredRate;
			if (now < nextSelection) return;
			selected = choose(realFps, cap, upper, probing ? 1 : selected);
			probing = false;
			nextSelection = now + 5_000_000_000L;
			if (selected > 1) {
				nextProbe = Math.max(nextProbe, now + 10_000_000_000L);
				bypassSince = 0;
			}
		} else {
			// GPU-only cost is an upper bound on throughput, never a replacement for the last bypass rate.
			double cost = gpuCostMs - fgCostMs;
			if (now < nextSelection || now < nextProbe || !(cost > 0) || !Double.isFinite(cost)) return;
			double upperFps = 1000.0 / cost;
			if (choose(upperFps, cap, upper, selected) != selected) {
				selected = 1; probing = true; probes++; bypassSince = now;
				nextProbe = now + 10_000_000_000L;
			}
		}
	}
	static int choose(double fps, int target, int maximum, int previous) {
		if (!(fps > 0) || !Double.isFinite(fps) || target == Integer.MAX_VALUE) return 1;
		int candidate = Math.max(1, Math.min(maximum, (int) Math.floor(target / fps)));
		if (candidate > previous) return Math.max(previous, Math.max(1, Math.min(maximum, (int) Math.floor(target * 0.9 / fps))));
		if (candidate < previous && fps * previous <= target * 1.05 && target / (double) previous >= fps * 0.9) return previous;
		return candidate;
	}
}
