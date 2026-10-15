package com.bambooz.reflexium.vulkan;

import com.bambooz.reflexium.ReflexiumClient;

/** Opt-in bounded API-boundary trace. Submission order is not display feedback. */
public final class FrameTrace {
	private static long endId;
	public static void arm(int frames) { endId = com.bambooz.reflexium.reflex.ReflexController.get().lastFrameId() + Math.clamp(frames, 0, 32); }
	public static boolean enabled(long id) { return id > 0 && id <= endId; }
	public static void event(long id, String event) {
		if (enabled(id)) ReflexiumClient.LOGGER.info("[FG API] frame={} {}", id, event);
	}
}
