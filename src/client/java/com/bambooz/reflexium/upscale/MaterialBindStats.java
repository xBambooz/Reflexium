package com.bambooz.reflexium.upscale;

import com.google.gson.JsonObject;

import java.util.Arrays;

/** Material bind telemetry per {@link MaterialBinding} path. Counters are reset every frame and published when the frame ends. */
final class MaterialBindStats {
	private final int[] frameBinds = new int[MaterialBinding.PATHS];
	private final int[] frameBiased = new int[MaterialBinding.PATHS];
	private final float[] frameBias = new float[MaterialBinding.PATHS];
	private final int[] lastBinds = new int[MaterialBinding.PATHS];
	private final int[] lastBiased = new int[MaterialBinding.PATHS];
	private final float[] lastBias = new float[MaterialBinding.PATHS];

	MaterialBindStats() {
		Arrays.fill(frameBias, Float.NaN);
		Arrays.fill(lastBias, Float.NaN);
	}

	/** One bind on {@code path} with the LOD bias it actually carried (NaN when unknown). */
	void record(int path, float appliedBias, boolean withRequestedBias) {
		frameBinds[path]++;
		frameBias[path] = appliedBias;
		if (withRequestedBias) frameBiased[path]++;
	}

	void publish() {
		for (int i = 0; i < MaterialBinding.PATHS; i++) {
			lastBinds[i] = frameBinds[i];
			lastBiased[i] = frameBiased[i];
			lastBias[i] = frameBias[i];
			frameBinds[i] = 0;
			frameBiased[i] = 0;
			frameBias[i] = Float.NaN;
		}
	}

	/** {@code currentFrame} reads the counters still being collected instead of the last published frame. */
	JsonObject toJson(boolean currentFrame) {
		JsonObject binds = new JsonObject();
		for (int i = 0; i < MaterialBinding.PATHS; i++) {
			JsonObject bind = new JsonObject();
			bind.addProperty("binds", currentFrame ? frameBinds[i] : lastBinds[i]);
			bind.addProperty("with_requested_bias", currentFrame ? frameBiased[i] : lastBiased[i]);
			float applied = currentFrame ? frameBias[i] : lastBias[i];
			bind.addProperty("applied_bias", Float.isNaN(applied) ? null : applied);
			binds.add(MaterialBinding.PATH_NAMES[i], bind);
		}
		return binds;
	}
}
