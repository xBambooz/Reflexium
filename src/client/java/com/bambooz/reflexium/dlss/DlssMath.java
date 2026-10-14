package com.bambooz.reflexium.dlss;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Camera-relative reprojection, shared with the standalone numerical check. */
final class DlssMath {
	private DlssMath() {}

	static float mipBias(int renderWidth, int displayWidth) {
		return renderWidth < displayWidth ? (float) (Math.log((double) renderWidth / displayWidth) / Math.log(2) - 1) : 0;
	}

	static int jitterPhases(int renderWidth, int displayWidth) {
		double ratio = (double) displayWidth / renderWidth;
		return Math.max(8, (int) Math.ceil(8 * ratio * ratio));
	}

	static Matrix4f reprojection(Matrix4fc previous, Matrix4fc currentJittered, float dx, float dy, float dz) {
		return new Matrix4f(previous).translate(dx, dy, dz).mul(new Matrix4f(currentJittered).invert());
	}

	static float halton(int index, int base) {
		float result = 0.0f;
		float fraction = 1.0f;
		while (index > 0) {
			fraction /= base;
			result += fraction * (index % base);
			index /= base;
		}
		return result;
	}
}
