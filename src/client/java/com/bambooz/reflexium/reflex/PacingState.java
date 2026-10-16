package com.bambooz.reflexium.reflex;

/**
 * Driver configuration and completed sleep are separate proofs. One attempt per requested state.
 * <p>
 * VK_TIMEOUT from the sleep wait is not an error: the driver's signal is still pending. Pacing is suspended,
 * no new sleep is issued until the outstanding value is observed, then pacing resumes. Errors, and a streak of
 * timeouts with no completed sleep between them, fail until the mode, swapchain or device changes.
 */
final class PacingState {
	static final int VK_TIMEOUT = 2;
	static final int MAX_TIMEOUT_STREAK = 3;

	ReflexMode applied, attempted;
	int interval = -1, attemptedInterval = -1;
	boolean configured, completed, failed, suspended;
	int lastResult, timeoutStreak;
	/** Lifetime diagnostics, not cleared by {@link #reset()}. */
	long timeouts, recoveries, failures, configurations, configurationFailures;

	void reset() {
		applied = attempted = null;
		interval = attemptedInterval = -1;
		configured = completed = failed = suspended = false;
		lastResult = timeoutStreak = 0;
	}
	boolean shouldApply(ReflexMode mode, int us) { return attempted != mode || attemptedInterval != us; }
	void configuration(ReflexMode mode, int us, int result) {
		attempted = mode; attemptedInterval = us; lastResult = result;
		configured = result == 0; completed = false; failed = result != 0;
		configurations++;
		if (failed) { configurationFailures++; failures++; }
		if (configured) { applied = mode; interval = us; }
	}
	void sleepResult(int result) {
		lastResult = result;
		completed = result == 0;
		if (result == 0) { timeoutStreak = 0; return; }
		boolean wasFailed = failed;
		if (result == VK_TIMEOUT) {
			timeouts++;
			suspended = true;
			failed = ++timeoutStreak >= MAX_TIMEOUT_STREAK;
		} else failed = true;
		if (failed && !wasFailed) failures++;
	}
	/** The outstanding signal was observed; new sleeps are safe again. The streak clears on the next completed sleep. */
	void signalObserved() {
		if (!suspended) return;
		suspended = false;
		recoveries++;
	}
	boolean canSleep() { return configured && !failed && !suspended; }
	boolean ownsCap(ReflexMode mode, int requested) {
		return canSleep() && completed && applied == mode && mode.enabled() && requested > 0 && interval >= requested;
	}
}
