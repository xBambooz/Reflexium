package com.bambooz.reflexium.dlss;

/**
 * Per-real-frame accounting for NVIDIA generated presents. Every generated present a frame was configured for is
 * either delivered or counted under exactly one reason, so expected minus delivered always equals the sum of the
 * reasons. Render thread only; all totals are lifetime values for callers that compare snapshots.
 */
public final class GenerationLedger {
	/**
	 * Why a real frame delivered fewer generated presents than configured. Only HISTORY_RESET is a legitimate cut;
	 * UNPRESENTED means images were ready but never presented, for example when the real image skipped the batch.
	 */
	public enum Shortfall { NOT_EVALUATED, UNAVAILABLE, REFLEX_NOT_SLEEPING, HISTORY_RESET, GUI_BLUR, BATCH_ABANDONED, SURFACE_LOST, PRESENT_FAILED, UNPRESENTED }

	private Shortfall reason = Shortfall.NOT_EVALUATED;
	private int expected, delivered;
	private long expectedTotal, closed;
	private final long[] missing = new long[Shortfall.values().length];

	/** Frame start: how many generated presents this frame should deliver, and the reason if none get ready. */
	void begin(int configured, Shortfall initial) { expected = configured; delivered = 0; reason = initial; }
	void reason(Shortfall value) { reason = value; }
	void generatedPresent(boolean success) { if (success) delivered++; else reason = Shortfall.PRESENT_FAILED; }
	/** The real present attempt closes the frame. */
	void closeFrame() {
		closed++;
		expectedTotal += expected;
		int shortBy = expected - Math.min(delivered, expected);
		if (shortBy > 0) missing[reason.ordinal()] += shortBy;
		expected = delivered = 0;
	}
	/** A batch that ended before its real present (lost surface) still closes the frame it started. */
	void closeIfOpen() { if (expected > 0) closeFrame(); }

	public String reasonText() { return switch (reason) {
 case REFLEX_NOT_SLEEPING -> "Reflex not sleeping";
 case HISTORY_RESET -> "history reset";
 case GUI_BLUR -> "GUI blur";
 case SURFACE_LOST -> "surface lost";
 case PRESENT_FAILED -> "present failed";
 case BATCH_ABANDONED -> "present batch abandoned";
 case UNAVAILABLE -> "generation unavailable";
 case UNPRESENTED -> "generated frames not presented";
 default -> "world frame not evaluated";
 }; }
 public long expectedTotal() { return expectedTotal; }
	public long closedFrames() { return closed; }
	public long missing(Shortfall value) { return missing[value.ordinal()]; }
}
