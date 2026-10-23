package com.bambooz.reflexium.debug;

import com.bambooz.reflexium.framegen.FrameGenerationRenderer;
import com.bambooz.reflexium.framegen.GenerationLedger;
import com.bambooz.reflexium.reflex.ReflexController;

import java.util.Locale;

/**
 * Cumulative counters an external benchmark driver copies at its own measured-frame boundaries. Render thread
 * only, no allocation. Every value except the trailing state fields is monotonic, so a pass is the difference
 * of two snapshots. Present counters belong to the frame that started at or after the earlier boundary; the
 * Reflex sleep runs before that frame's render, so sleep counters cover the next frame's pacing.
 */
public final class MeasurementCounters {
	public static final String SCHEMA = "reflexium-measurement-v1";
	private static final GenerationLedger.Shortfall[] SHORTFALLS = GenerationLedger.Shortfall.values();
	public static final String[] NAMES = names();
	/** Index of the first state (non-cumulative) field. */
	public static final int STATE = NAMES.length - 4;

	private MeasurementCounters() {}

	private static String[] names() {
		String[] fixed = {"app_frame_id", "fg_evaluated_frames", "fg_generated_presents", "fg_real_presents",
				"fg_real_frames_closed", "fg_expected_generated", "fg_batches_started", "fg_batches_completed",
				"fg_metering_rejected", "reflex_sleeps_issued", "reflex_sleeps_completed", "reflex_unpaced_frames",
				"reflex_suspended_frames", "reflex_timeouts", "reflex_recoveries", "reflex_failures", "reflex_mode_configurations"};
		String[] state = {"fg_failed", "fg_configured_generated", "reflex_effective_mode", "reflex_applied_mode"};
		String[] all = new String[fixed.length + SHORTFALLS.length + state.length];
		System.arraycopy(fixed, 0, all, 0, fixed.length);
		for (int i = 0; i < SHORTFALLS.length; i++) all[fixed.length + i] = "fg_missing_" + SHORTFALLS[i].name().toLowerCase(Locale.ROOT);
		System.arraycopy(state, 0, all, fixed.length + SHORTFALLS.length, state.length);
		return all;
	}

	public static void read(long[] out) {
		FrameGenerationRenderer fg = FrameGenerationRenderer.get();
		ReflexController reflex = ReflexController.get();
		int i = 0;
		out[i++] = reflex.lastFrameId();
		out[i++] = fg.evaluatedFrames();
		out[i++] = fg.generatedPresents();
		out[i++] = fg.realPresents();
		out[i++] = fg.ledger().closedFrames();
		out[i++] = fg.ledger().expectedTotal();
		out[i++] = fg.batchesStarted();
		out[i++] = fg.batchesCompleted();
		out[i++] = fg.meteringRejected();
		out[i++] = reflex.sleepsIssued();
		out[i++] = reflex.sleepsCompleted();
		out[i++] = reflex.unpacedFrames();
		out[i++] = reflex.suspendedFrames();
		out[i++] = reflex.sleepTimeouts();
		out[i++] = reflex.sleepRecoveries();
		out[i++] = reflex.pacingFailures();
		out[i++] = reflex.modeConfigurations();
		for (GenerationLedger.Shortfall reason : SHORTFALLS) out[i++] = fg.ledger().missing(reason);
		out[i++] = fg.failed() ? 1 : 0;
		out[i++] = fg.configuredGeneratedCount();
		out[i++] = reflex.mode().ordinal();
		out[i] = reflex.appliedModeOrdinal();
	}
}
