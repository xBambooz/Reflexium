package com.bambooz.reflexium.debug;

/**
 * Runtime overrides for the upscaling debug harness (see {@link DebugServer}). Every default reproduces
 * the shipped behavior exactly, so nothing here changes rendering unless a debug command sets it.
 */
public final class DebugSettings {
	private static volatile float jitterSignX = Float.NaN;
	private static volatile float jitterSignY = Float.NaN;
	private static volatile boolean jitterEnabled = true;
	private static volatile boolean motionVectorView;

	private static volatile long frames;
	private static volatile float frameTimeMs;
	private static long lastFrameNs;

	private DebugSettings() {
	}

	/** Sign applied to the X jitter handed to the upscaler; {@code fallback} is the built-in value. */
	public static float jitterSignX(float fallback) {
		float value = jitterSignX;
		return Float.isNaN(value) ? fallback : value;
	}

	public static float jitterSignY(float fallback) {
		float value = jitterSignY;
		return Float.isNaN(value) ? fallback : value;
	}

	/** NaN restores the built-in sign. */
	public static void setJitterSignX(float value) {
		jitterSignX = value;
	}

	public static void setJitterSignY(float value) {
		jitterSignY = value;
	}

	public static boolean jitterEnabled() {
		return jitterEnabled;
	}

	public static void setJitterEnabled(boolean enabled) {
		jitterEnabled = enabled;
	}

	/** Draw the motion vector texture over the upscaled world. */
	public static boolean motionVectorView() {
		return motionVectorView;
	}

	public static void setMotionVectorView(boolean enabled) {
		motionVectorView = enabled;
	}

	/** Called once per rendered frame from the render thread. */
	public static void onFrame() {
		long now = System.nanoTime();
		if (lastFrameNs != 0) {
			float ms = (now - lastFrameNs) / 1_000_000f;
			frameTimeMs = frameTimeMs == 0 ? ms : frameTimeMs * 0.95f + ms * 0.05f;
		}
		lastFrameNs = now;
		frames++;
	}

	public static long frames() {
		return frames;
	}

	/** Smoothed frame time in milliseconds (exponential average over roughly 20 frames). */
	public static float frameTimeMs() {
		return frameTimeMs;
	}
}
