package com.bambooz.reflexium.vulkan;

import com.bambooz.reflexium.ReflexiumClient;

/**
 * Opt-in bounded API-boundary trace. Submission order is not display feedback. Every line carries the CPU
 * monotonic clock (System.nanoTime, "t=") only; GPU timestamps are a separate clock domain and are not correlated
 * here. While armed, work that reaches a traced hook without an open application frame is logged as frame=0 so
 * missing attribution is visible instead of silently dropped.
 */
public final class FrameTrace {
	private static long endId;
	public static void arm(int frames) { endId = com.bambooz.reflexium.reflex.ReflexController.get().lastFrameId() + Math.clamp(frames, 0, 32); }
	public static boolean enabled(long id) { return id > 0 && id <= endId; }
	/** Inside the armed window, including gaps where no application frame is open. */
	public static boolean armed() { return endId > 0 && com.bambooz.reflexium.reflex.ReflexController.get().lastFrameId() <= endId; }
	/** Guard string building with this; untraced frames must not allocate. */
	public static boolean on(long id) { return enabled(id) || id == 0 && armed(); }
	public static void event(long id, String event) {
		if (on(id)) ReflexiumClient.LOGGER.info("[FG API] frame={} t={} {}", id, System.nanoTime(), event);
	}
}
