package com.bambooz.reflexium.upscale.dlss;

import java.util.Set;

/** Result of {@link DlssSupport#probe}. Immutable; replaced as a whole. */
public record DlssStatus(boolean prerequisitesMet, String reason, boolean extensionsEnabled, Set<String> featureDlls) {

	static DlssStatus pending() {
		return new DlssStatus(false, "needs the Vulkan renderer", false, Set.of());
	}

	static DlssStatus unavailable(String reason) {
		return new DlssStatus(false, reason, false, Set.of());
	}

	static DlssStatus ready(boolean extensionsEnabled, Set<String> featureDlls) {
		return new DlssStatus(extensionsEnabled, "", extensionsEnabled, Set.copyOf(featureDlls));
	}

	public String summary() {
		if (!reason.isEmpty()) {
			return reason;
		}
		if (!extensionsEnabled) {
			return "device lacks NVX extensions";
		}
		String dlls = featureDlls.isEmpty() ? "no feature DLLs in reflexium/" : String.join(", ", featureDlls);
		return "prerequisites met (" + dlls + ")";
	}
}
