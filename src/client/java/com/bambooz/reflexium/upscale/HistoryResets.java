package com.bambooz.reflexium.upscale;

import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.Locale;

/** Whether the next evaluation discards temporal history, and why earlier evaluations did. */
final class HistoryResets {
	private boolean pending = true;
	private ResetReason pendingReason = ResetReason.REBUILD;
	private ResetReason last = ResetReason.REBUILD;
	private final long[] byReason = new long[ResetReason.values().length];
	private long frames;

	/** The first reason wins until an evaluation consumes the reset. */
	void request(ResetReason reason) {
		if (!pending) {
			pending = true;
			pendingReason = reason;
		}
	}

	boolean pending() { return pending; }
	ResetReason pendingReason() { return pendingReason; }
	long frames() { return frames; }

	/** An evaluation is about to apply the pending reset. */
	void count() {
		frames++;
		byReason[pendingReason.ordinal()]++;
		last = pendingReason;
	}

	/** An evaluation ran; history continues from it. */
	void consume() {
		pending = false;
	}

	void clearCounters() {
		frames = 0;
		Arrays.fill(byReason, 0L);
	}

	/** {@code currentFrame} also counts a reset the frame being captured is about to apply. */
	void write(JsonObject o, boolean currentFrame) {
		boolean resetting = currentFrame && pending;
		o.addProperty("history_reset_frames", frames + (resetting ? 1 : 0));
		o.addProperty("last_reset_reason", (resetting ? pendingReason : last).label);
		JsonObject reasons = new JsonObject();
		for (ResetReason reason : ResetReason.values()) {
			reasons.addProperty(reason.name().toLowerCase(Locale.ROOT), byReason[reason.ordinal()] + (resetting && pendingReason == reason ? 1 : 0));
		}
		o.add("resets_by_reason", reasons);
	}
}
