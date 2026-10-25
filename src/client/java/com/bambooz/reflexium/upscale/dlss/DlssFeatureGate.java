package com.bambooz.reflexium.upscale.dlss;

/**
 * Which NVIDIA features this adapter may use. The inputs are the results of
 * {@code slIsFeatureSupported(kFeatureDLSS_G, adapter)} and {@code slDLSSGGetState()}:
 * an unsupported DLSS-G feature is what a pre-RTX 40 GPU reports, and
 * {@code numFramesToGenerateMax == 1} is what an RTX 40 GPU reports for MFG.
 * Pure so the pre-RTX 40 and pre-RTX 50 paths can be checked without that hardware.
 */
public final class DlssFeatureGate {
	public record Support(boolean nvidiaGpu, boolean streamlineReady, boolean dlssgFeatureSupported, int maxGeneratedFrames) {
	}

	public record Decision(boolean upscalingAllowed, boolean frameGenerationAllowed,
			boolean multiFrameGenerationAllowed, String reason, String reasonKey) {
		public boolean limited() {
			return !reason.isEmpty();
		}
	}

	public static final String KEY_DLSS_RTX = "reflexium.gate.dlss_requires_rtx";
	public static final String KEY_FG_RTX40 = "reflexium.gate.fg_requires_rtx40";
	public static final String KEY_MFG_RTX50 = "reflexium.gate.mfg_requires_rtx50";
	public static final String KEY_STREAMLINE = "reflexium.gate.fg_needs_streamline";

	private DlssFeatureGate() {
	}

	public static Decision evaluate(Support support) {
		boolean upscaling = support.nvidiaGpu();
		boolean frameGeneration = support.nvidiaGpu() && support.streamlineReady() && support.dlssgFeatureSupported();
		boolean multiFrameGeneration = frameGeneration && support.maxGeneratedFrames() >= 2;
		if (!support.nvidiaGpu()) {
			return new Decision(false, false, false,
					"DLSS and Frame Generation require an NVIDIA RTX GPU", KEY_DLSS_RTX);
		}
		if (!support.streamlineReady()) {
			return new Decision(true, false, false,
					"Frame Generation requires the Streamline runtime", KEY_STREAMLINE);
		}
		if (!support.dlssgFeatureSupported()) {
			return new Decision(true, false, false,
					"Frame Generation requires an RTX 40 series or newer GPU with hardware GPU scheduling", KEY_FG_RTX40);
		}
		if (!multiFrameGeneration) {
			return new Decision(true, true, false,
					"Multi Frame Generation requires an RTX 50 series GPU", KEY_MFG_RTX50);
		}
		return new Decision(true, true, true, "", "");
	}
}
