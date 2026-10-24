package com.bambooz.reflexium.framegen;

import com.bambooz.reflexium.vulkan.VulkanLifecycle;
import com.bambooz.reflexium.vulkan.VulkanSession;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.Set;

public final class FrameDeliveryCheck {
	private static final int LEAVE = FrameGenerationRenderer.FEEDBACK_UNREPORTED;

	/** One present through the real renderer hooks; feedback is written into the chained config as a driver would. */
	private static VkSetPresentConfigNV present(FrameGenerationRenderer fg, MemoryStack stack, boolean generated, int feedback, int result) {
		fg.beginPresent(generated);
		var info = fg.chainMetering(VkPresentInfoKHR.calloc(stack).sType$Default());
		VkSetPresentConfigNV config = info.pNext() == 0 ? null : VkSetPresentConfigNV.create(info.pNext());
		if (config != null && feedback != LEAVE) config.presentConfigFeedback(feedback);
		fg.onPresentResult(info, result);
		return config;
	}
	private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

	public static void main(String[] args) {
		check(FrameGeneration.DLSS.withMultiplier(3) == FrameGeneration.DLSS_MULTI, "3x selects MFG");
		check(FrameGeneration.DLSS_MULTI.withMultiplier(2) == FrameGeneration.DLSS, "2x selects standard FG");
		check(FrameGeneration.OFF.withMultiplier(3) == FrameGeneration.OFF && FrameGeneration.FSR.withMultiplier(3) == FrameGeneration.FSR, "multiplier does not enable or replace other providers");
		var refresh = new FrameGenerationRefreshPolicy();
		refresh.cap(223); refresh.configure(0, 6);
		refresh.present(1, true, 100, 9, 1);
		refresh.present(1_000_000_001L, true, 100, 9, 1);
		check(refresh.bypass(), "wait for uncontaminated delivery window");
		refresh.present(2_000_000_001L, true, 100, 9, 1);
		check(refresh.multiplier() == 2, "100 measured FPS selects 2x");
		refresh.present(6_000_000_001L, true, 500, 2, 1);
		check(refresh.multiplier() == 2, "five second minimum dwell");
		refresh.present(12_000_000_001L, true, 100, 2, 1);
		check(refresh.probing() && refresh.bypass() && refresh.probes() == 1, "GPU estimate requests uncapped probe");
		refresh.present(14_000_000_001L, true, 500, 2, 1);
		check(refresh.bypass() && !refresh.probing() && refresh.realFps() == 500, "measured fast scene bypasses");
		refresh.configure(4, 6);
		refresh.present(20_000_000_001L, true, 500, 2, 0);
		refresh.present(22_000_000_001L, true, 500, 2, 0);
		check(refresh.bypass(), "fixed maximum never cuts fast real frames");
		refresh.force(true);
		check(refresh.multiplier() == 4, "force restores old fixed behavior");
		refresh.cap(120);
		check(refresh.multiplier() == 4, "force ignores output budget");
		check(FrameGenerationRefreshPolicy.targetsRefresh(0), "Auto targets the refresh rate");
		check(!FrameGenerationRefreshPolicy.targetsRefresh(2) && !FrameGenerationRefreshPolicy.targetsRefresh(4),
				"fixed multipliers never target the refresh rate");
		check(FrameGenerationRefreshPolicy.choose(100, 416, 6, 4) == 4, "stable within budget");
		check(FrameGenerationRefreshPolicy.choose(105, 416, 6, 4) == 4, "five percent downshift hysteresis");
		check(FrameGenerationRefreshPolicy.choose(111, 416, 6, 4) == 3, "downshift beyond five percent");
		check(FrameGenerationRefreshPolicy.choose(95, 416, 6, 3) == 3, "ten percent upshift headroom");
		check(FrameGenerationRefreshPolicy.choose(90, 416, 6, 3) == 4, "upshift with headroom");
		check(FrameGenerationRefreshPolicy.choose(500, 223, 6, 1) == 1, "fast scene bypasses");
		check(FrameGenerationRefreshPolicy.choose(100, Integer.MAX_VALUE, 6, 1) == 1, "unknown refresh bypasses");
		if (VulkanLifecycle.validImageCount(8, 2, 3) != 3 || VulkanLifecycle.validImageCount(3, 2, 0) != 3
				|| VulkanLifecycle.validImageCount(1, 2, 0) != 2) throw new AssertionError("surface bounds");
		for (int revision = 0; revision <= 3; revision++) {
			var session = new VulkanSession(null, null, null, 0, "", "", revision > 0, revision, false, false, false, Set.of());
			if (session.explicitAttributionSupported() != (revision >= 3)) throw new AssertionError("extension revision");
		}
		var stats = new PresentationStats();
		stats.record(1, 0, 0);
		for (int i = 1; i <= 100; i++) stats.record(1 + i * 10_000_000L, 1, 5);
		if (stats.realRate(1_000_000_001L) != 100 || stats.totalRate(1_000_000_001L) != 600) throw new AssertionError("matching submission window");
		stats.reset();
		if (stats.totalRate(1_000_000_002L) != 0) throw new AssertionError("suppression reset");
		stats.record(2_000_000_001L, 1, 0); stats.record(3_000_000_001L, 99, 0);
		if (stats.totalRate(3_000_000_001L) != 100 || stats.totalRate(5_000_000_001L) != 0) throw new AssertionError("native and stale window");
		var fg = FrameGenerationRenderer.get();
		try (var stack = MemoryStack.stackPush()) {
			// Unchanged sentinel: unreported, neither success nor rejection, and generation continues.
			long unreported = fg.meteringUnreported(), accepted = fg.meteringAccepted(), rejected = fg.meteringRejected();
			fg.beginPresentBatch(6);
			var config = present(fg, stack, true, LEAVE, 0);
			check(config != null && config.numFramesPerBatch() == 6 && config.presentConfigFeedback() == LEAVE, "batch config seeded with sentinel");
			check(!fg.batchRejected() && fg.meteringUnreported() == unreported + 1 && fg.meteringAccepted() == accepted && fg.meteringRejected() == rejected, "unreported feedback");
			check("0x7fffffff".equals(fg.meteringFeedbackRaw()), "raw sentinel");
			for (int i = 1; i < 6; i++) check(present(fg, stack, i < 5, 0, 0) == null, "config chained only on the first present");
			long completed = fg.batchesCompleted();
			fg.endPresentBatch();
			check(fg.batchesCompleted() == completed + 1, "completed batch counted");

			fg.beginPresentBatch(2);
			present(fg, stack, true, 0, 0);
			check(!fg.batchRejected() && fg.meteringAccepted() == accepted + 1, "accepted feedback");
			fg.endPresentBatch();

			// Positive status 0x1, as NVIDIA 617.42 writes for every config: counted, not a rejection.
			long status = fg.meteringStatus();
			fg.beginPresentBatch(2);
			present(fg, stack, true, 1, 0);
			check(!fg.batchRejected() && !fg.meteringResetPending() && fg.meteringStatus() == status + 1 && fg.meteringRejected() == rejected, "positive status feedback");
			fg.endPresentBatch();

			// Explicit negative feedback (-1, unsigned 0xFFFFFFFE): transient rejection, reset on the next present, no latch.
			for (int feedback : new int[]{-1, 0xFFFFFFFE}) {
				long resets = fg.meteringResetConfigs(), generatedBefore = fg.generatedPresents();
				fg.beginPresentBatch(4);
				present(fg, stack, true, feedback, 0);
				check(fg.batchRejected() && fg.meteringResetPending() && !fg.failed() && !fg.meteringProblem().isEmpty(), "rejection is transient " + feedback);
				check(fg.generatedPresents() == generatedBefore + 1, "rejected config still delivered its present");
				check(fg.meteringFeedbackRaw().equals("0x" + Integer.toHexString(feedback)), "unsigned raw feedback");
				var reset = present(fg, stack, false, 0, 0); // real image after abandoning interpolation
				check(reset != null && reset.numFramesPerBatch() == 1 && fg.meteringResetConfigs() == resets + 1 && !fg.meteringResetPending(), "reset config read and cleared");
				fg.endPresentBatch();
			}
			check(fg.meteringRejectionStreak() == 2 && fg.meteringRejected() == rejected + 2, "repeated rejection counted");

			// A rejected reset stays pending and is not consumed as the next batch's configuration.
			fg.beginPresentBatch(3);
			present(fg, stack, true, -2, 0);
			present(fg, stack, false, -2, 0);
			check(fg.meteringResetPending(), "failed reset remains pending");
			fg.endPresentBatch();
			long batches = fg.meteringBatchConfigs();
			fg.beginPresentBatch(3);
			config = present(fg, stack, true, 0, 0);
			check(config.numFramesPerBatch() == 1 && fg.meteringBatchConfigs() == batches && fg.batchRejected() && !fg.meteringResetPending(), "pending reset cuts the batch");
			check(present(fg, stack, false, 0, 0) == null, "no config after reset in the same batch");
			fg.endPresentBatch();

			// A later successful batch recovers without retry().
			completed = fg.batchesCompleted();
			fg.beginPresentBatch(3);
			config = present(fg, stack, true, 0, 0);
			present(fg, stack, true, 0, 0); present(fg, stack, false, 0, 0);
			fg.endPresentBatch();
			check(config.numFramesPerBatch() == 3 && fg.batchesCompleted() == completed + 1 && fg.meteringRejectionStreak() == 0
					&& fg.meteringProblem().isEmpty() && !fg.failed(), "recovery without retry");

			// Non-batch reset feedback is read too, and the chain behind it is preserved.
			fg.beginPresentBatch(2); present(fg, stack, true, -2, 0); fg.endPresentBatch();
			check(fg.meteringResetPending(), "reset pending outside a batch");
			fg.beginPresent(false);
			var original = VkPresentRegionsKHR.calloc(stack).sType$Default();
			var info = fg.chainMetering(VkPresentInfoKHR.calloc(stack).sType$Default().pNext(original.address()));
			var reset = VkSetPresentConfigNV.create(info.pNext());
			check(reset.numFramesPerBatch() == 1 && reset.pNext() == original.address(), "pNext chain preserved");
			reset.presentConfigFeedback(0);
			fg.onPresentResult(info, 0);
			check(!fg.meteringResetPending(), "non-batch reset feedback consumed");

			// VkResult failures at the first or a middle present: abandon, invalidate, never count as delivery.
			for (int failureIndex : new int[]{0, 2}) {
				fg.beginPresentBatch(6);
				for (int i = 0; i <= failureIndex; i++) {
					long delivered = fg.generatedPresents();
					present(fg, stack, true, 0, i == failureIndex ? KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR : 0);
					check(fg.generatedPresents() == delivered + (i == failureIndex ? 0 : 1), "errors are not delivery");
				}
				check(fg.batchRejected() && fg.submittedFps() == 0 && fg.meteringResetPending() && !fg.failed(), "partial batch failure");
				fg.endPresentBatch();
			}
			// SUBOPTIMAL is a delivered submission but stops interpolation on the valid surface.
			fg.beginPresentBatch(2);
			long delivered = fg.generatedPresents();
			present(fg, stack, true, 0, KHRSwapchain.VK_SUBOPTIMAL_KHR);
			check(fg.batchRejected() && fg.generatedPresents() == delivered + 1, "suboptimal delivered and stops interpolation");
			fg.endPresentBatch(); fg.invalidate(); fg.retry(); fg.skipGeneratedFrames();
			if (fg.generatedCount() != 0 || fg.submittedFps() != 0) throw new AssertionError("history/menu/resize warmup");
		}
		ledgerChecks();
		System.out.println("Frame delivery lifecycle checks passed");
	}

	/** Expected minus delivered generated presents always equals the sum of per-reason shortfalls. */
	private static void ledgerChecks() {
		var ledger = new GenerationLedger();
		ledger.begin(5, GenerationLedger.Shortfall.NOT_EVALUATED);
		ledger.reason(GenerationLedger.Shortfall.UNPRESENTED);
		for (int i = 0; i < 5; i++) ledger.generatedPresent(true);
		ledger.closeFrame();
		ledger.closeIfOpen();
		check(ledger.expectedTotal() == 5 && ledger.closedFrames() == 1 && total(ledger) == 0, "complete 6x frame");
		ledger.begin(5, GenerationLedger.Shortfall.NOT_EVALUATED);
		ledger.reason(GenerationLedger.Shortfall.UNPRESENTED);
		ledger.generatedPresent(true); ledger.generatedPresent(true);
		ledger.reason(GenerationLedger.Shortfall.BATCH_ABANDONED);
		ledger.closeFrame();
		check(ledger.missing(GenerationLedger.Shortfall.BATCH_ABANDONED) == 3, "abandoned batch keeps its reason");
		ledger.begin(1, GenerationLedger.Shortfall.NOT_EVALUATED);
		ledger.reason(GenerationLedger.Shortfall.HISTORY_RESET);
		ledger.closeFrame();
		ledger.begin(1, GenerationLedger.Shortfall.NOT_EVALUATED);
		ledger.reason(GenerationLedger.Shortfall.GUI_BLUR);
		ledger.closeFrame();
		ledger.begin(1, GenerationLedger.Shortfall.UNAVAILABLE);
		ledger.closeFrame();
		ledger.begin(1, GenerationLedger.Shortfall.NOT_EVALUATED);
		ledger.reason(GenerationLedger.Shortfall.UNPRESENTED);
		ledger.generatedPresent(false);
		ledger.closeFrame();
		// Lost surface before the real present: the batch end closes the frame exactly once.
		ledger.begin(2, GenerationLedger.Shortfall.NOT_EVALUATED);
		ledger.generatedPresent(true);
		ledger.reason(GenerationLedger.Shortfall.SURFACE_LOST);
		ledger.closeIfOpen();
		ledger.closeIfOpen();
		ledger.begin(0, GenerationLedger.Shortfall.NOT_EVALUATED);
		ledger.closeFrame();
		check(ledger.closedFrames() == 8 && ledger.expectedTotal() == 5 + 5 + 1 + 1 + 1 + 1 + 2, "frame closures and expectations");
		check(total(ledger) == ledger.expectedTotal() - (5 + 2 + 1), "every shortfall has one reason");
		check(ledger.missing(GenerationLedger.Shortfall.HISTORY_RESET) == 1 && ledger.missing(GenerationLedger.Shortfall.GUI_BLUR) == 1
				&& ledger.missing(GenerationLedger.Shortfall.UNAVAILABLE) == 1 && ledger.missing(GenerationLedger.Shortfall.PRESENT_FAILED) == 1
				&& ledger.missing(GenerationLedger.Shortfall.SURFACE_LOST) == 1, "per-reason attribution");
	}
	private static long total(GenerationLedger ledger) {
		long sum = 0;
		for (var reason : GenerationLedger.Shortfall.values()) sum += ledger.missing(reason);
		return sum;
	}
}
