package com.bambooz.reflexium.upscale;

import com.google.gson.JsonObject;
import org.joml.Matrix4f;

/** Halton(2,3) sub-pixel projection offsets in render pixels. DLSS uses at least 32 phases, FSR its own ratio rule. */
final class JitterSequence {
	private int renderWidth;
	private int renderHeight;
	private int index;
	private int phases;
	private float x;
	private float y;
	private float previousNdcX;
	private float previousNdcY;

	/** Render size the NDC offsets are relative to. */
	void resize(int renderWidth, int renderHeight) {
		this.renderWidth = renderWidth;
		this.renderHeight = renderHeight;
	}

	/** A rebuilt feature starts the sequence again. */
	void restart() {
		index = 0;
	}

	void advance(Upscaler upscaler, int displayWidth, boolean enabled) {
		phases = DlssMath.jitterPhases(renderWidth, displayWidth, upscaler);
		index = index % phases + 1;
		if (upscaler == Upscaler.OFF || !enabled) {
			x = 0.0f;
			y = 0.0f;
			return;
		}
		x = DlssMath.halton(index, 2) - 0.5f;
		y = DlssMath.halton(index, 3) - 0.5f;
	}

	/** The jittered copy of {@code projection}. */
	Matrix4f apply(Matrix4f projection) {
		return new Matrix4f().translation(ndcX(), ndcY(), 0.0f).mul(projection);
	}

	/** End of a scene: this frame's offset becomes the previous one packs read. */
	void promote() {
		previousNdcX = ndcX();
		previousNdcY = ndcY();
	}

	float x() { return x; }
	float y() { return y; }
	int index() { return index; }
	int phases() { return phases; }
	float ndcX() { return 2.0f * x / renderWidth; }
	float ndcY() { return 2.0f * y / renderHeight; }
	float previousNdcX() { return previousNdcX; }
	float previousNdcY() { return previousNdcY; }

	JsonObject toJson(float signX, float signY, float sentX, float sentY) {
		JsonObject jitter = new JsonObject();
		jitter.addProperty("index", index);
		jitter.addProperty("phases", phases);
		jitter.addProperty("x_px", x);
		jitter.addProperty("y_px", y);
		jitter.addProperty("sign_x", signX);
		jitter.addProperty("sign_y", signY);
		jitter.addProperty("sent_x_px", sentX);
		jitter.addProperty("sent_y_px", sentY);
		jitter.addProperty("ndc_x", ndcX());
		jitter.addProperty("ndc_y", ndcY());
		return jitter;
	}
}
