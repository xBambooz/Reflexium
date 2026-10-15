package com.bambooz.reflexium.reflex;

/** Driver configuration and completed sleep are separate proofs. One attempt per requested state. */
final class PacingState {
	ReflexMode applied, attempted;
	int interval = -1, attemptedInterval = -1;
	boolean configured, completed, failed;
	int lastResult;

	void reset() {
		applied = attempted = null;
		interval = attemptedInterval = -1;
		configured = completed = failed = false;
		lastResult = 0;
	}
	boolean shouldApply(ReflexMode mode, int us) { return attempted != mode || attemptedInterval != us; }
	void configuration(ReflexMode mode, int us, int result) {
		attempted = mode; attemptedInterval = us; lastResult = result;
		configured = result == 0; completed = false; failed = result != 0;
		if (configured) { applied = mode; interval = us; }
	}
	void sleepResult(int result) { lastResult = result; completed = result == 0; failed = result != 0; }
	boolean canSleep() { return configured && !failed; }
	boolean ownsCap(ReflexMode mode, int requested) {
		return canSleep() && completed && applied == mode && mode.enabled() && requested > 0 && interval >= requested;
	}
}
