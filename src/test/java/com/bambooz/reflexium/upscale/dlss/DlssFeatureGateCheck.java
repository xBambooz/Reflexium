package com.bambooz.reflexium.upscale.dlss;

/**
 * Hardware gating with mocked slIsFeatureSupported(adapter) and slDLSSGGetState results.
 * A pre-RTX 40 adapter reports kFeatureDLSS_G unsupported; an RTX 40 adapter reports
 * numFramesToGenerateMax == 1, which is the same state a driver without MFG support returns.
 */
public final class DlssFeatureGateCheck {
	public static void main(String[] args) {
		DlssFeatureGate.Decision unsupported = DlssFeatureGate.evaluate(
				new DlssFeatureGate.Support(true, true, false, 0));
		check(!unsupported.frameGenerationAllowed(), "unsupported DLSS-G must hide frame generation");
		check(!unsupported.multiFrameGenerationAllowed(), "unsupported DLSS-G must hide MFG");
		check(unsupported.upscalingAllowed(), "DLSS SR still works when DLSS-G is unsupported");
		check(DlssFeatureGate.KEY_FG_RTX40.equals(unsupported.reasonKey()), "pre-RTX 40 reason key");
		check(unsupported.reason().contains("RTX 40"), "pre-RTX 40 reason text: " + unsupported.reason());

		// RTX 40: DLSS-G 2x only. numFramesToGenerateMax == 1.
		DlssFeatureGate.Decision rtx40 = DlssFeatureGate.evaluate(
				new DlssFeatureGate.Support(true, true, true, 1));
		check(rtx40.frameGenerationAllowed(), "RTX 40 allows 2x frame generation");
		check(!rtx40.multiFrameGenerationAllowed(), "RTX 40 must hide MFG");
		check(DlssFeatureGate.KEY_MFG_RTX50.equals(rtx40.reasonKey()), "pre-RTX 50 reason key");
		check(rtx40.reason().contains("RTX 50"), "pre-RTX 50 reason text: " + rtx40.reason());

		// RTX 50: up to six presents (five generated frames).
		DlssFeatureGate.Decision rtx50 = DlssFeatureGate.evaluate(
				new DlssFeatureGate.Support(true, true, true, 5));
		check(rtx50.frameGenerationAllowed(), "RTX 50 allows frame generation");
		check(rtx50.multiFrameGenerationAllowed(), "RTX 50 allows MFG");
		check(!rtx50.limited(), "RTX 50 has no gate reason");

		// A non-NVIDIA adapter gets neither DLSS nor DLSS-G.
		DlssFeatureGate.Decision other = DlssFeatureGate.evaluate(
				new DlssFeatureGate.Support(false, false, false, 0));
		check(!other.upscalingAllowed() && !other.frameGenerationAllowed(), "non-NVIDIA disables DLSS features");
		check(DlssFeatureGate.KEY_DLSS_RTX.equals(other.reasonKey()), "non-NVIDIA reason key");

		// A supported adapter with a missing Streamline runtime keeps SR but hides FG.
		DlssFeatureGate.Decision noRuntime = DlssFeatureGate.evaluate(
				new DlssFeatureGate.Support(true, false, false, 0));
		check(noRuntime.upscalingAllowed() && !noRuntime.frameGenerationAllowed(), "missing runtime keeps SR only");
		check(DlssFeatureGate.KEY_STREAMLINE.equals(noRuntime.reasonKey()), "missing runtime reason key");

		System.out.println("DLSS feature gate checks passed: pre-RTX 40, RTX 40, RTX 50, non-NVIDIA and missing runtime.");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
