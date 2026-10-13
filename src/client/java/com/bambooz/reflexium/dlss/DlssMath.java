package com.bambooz.reflexium.dlss;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Camera-relative reprojection, shared with the standalone numerical check. */
final class DlssMath {
	private DlssMath() {}

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
