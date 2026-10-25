package com.bambooz.reflexium.upscale;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;

/** Camera-relative reprojection and vendor-contract math, shared with the standalone numerical check. */
final class DlssMath {
	/** NVIDIA guide 3.5 and AMD FSR 3.1.4 both subtract one level from log2(render / display). */
	static final float VENDOR_MIP_BASELINE = -1.0f;
	/** NVIDIA section 2.2: at least 16 jitter phases, 32 or more preferred. */
	static final int DLSS_MIN_PHASES = 32;
	/** AMD FSR 3.1.4: 8 phases at native resolution, growing with the squared upscale ratio. */
	static final int FSR_MIN_PHASES = 8;
	/** Relative camera rotation above this angle is a cut, not a turn (radians). */
	static final double CUT_ANGLE = Math.toRadians(60.0);

	private DlssMath() {}

	/**
	 * Additional texture LOD bias for a world rendered below display resolution:
	 * {@code log2(render / display) - 1}. Zero at native resolution and for invalid sizes.
	 */
	static float mipBias(int renderWidth, int displayWidth) {
		if (renderWidth <= 0 || displayWidth <= 0 || renderWidth >= displayWidth) {
			return 0.0f;
		}
		return (float) (Math.log((double) renderWidth / displayWidth) / Math.log(2.0)) + VENDOR_MIP_BASELINE;
	}

	/** Jitter sequence length. DLSS never uses fewer than 32 phases, FSR keeps its own ratio-dependent policy. */
	static int jitterPhases(int renderWidth, int displayWidth, Upscaler upscaler) {
		if (renderWidth <= 0 || displayWidth <= 0) {
			return upscaler == Upscaler.DLSS ? DLSS_MIN_PHASES : FSR_MIN_PHASES;
		}
		double ratio = (double) displayWidth / renderWidth;
		int scaled = (int) Math.ceil(8 * ratio * ratio);
		return Math.max(upscaler == Upscaler.DLSS ? DLSS_MIN_PHASES : FSR_MIN_PHASES, scaled);
	}

	/** Physical perspective parameters extracted from an undistorted reverse-Z projection. */
	record CameraMetadata(float near, float far, float verticalFov) {
		boolean valid() {
			return Float.isFinite(near) && Float.isFinite(far) && Float.isFinite(verticalFov)
					&& near > 0.0f && far > near && verticalFov > 0.0f && verticalFov < (float) Math.PI;
		}
	}

	/**
	 * Near, far and vertical field of view of Minecraft's reverse-Z perspective matrix
	 * ({@code setPerspective(fov, aspect, far, near, true)} argument order). Only a pure perspective matrix
	 * decodes correctly: a matrix already multiplied by view bob, damage tilt or nausea does not.
	 */
	static CameraMetadata cameraMetadata(Matrix4fc projection) {
		float near = projection.m32() / (projection.m22() + 1.0f);
		float far = projection.m32() / projection.m22();
		float fov = 2.0f * (float) Math.atan(1.0f / Math.abs(projection.m11()));
		return new CameraMetadata(near, far, fov);
	}

	/** Shortest-arc rotation between two orientations in radians; q and -q are the same rotation. */
	static double rotationAngle(Quaternionfc previous, Quaternionfc current) {
		double dot = Math.abs((double) previous.x() * current.x() + (double) previous.y() * current.y()
				+ (double) previous.z() * current.z() + (double) previous.w() * current.w());
		// Un-normalized inputs and rounding can push the dot product marginally past one.
		return 2.0 * Math.acos(Math.min(1.0, dot));
	}

	static boolean isRotationCut(Quaternionfc previous, Quaternionfc current) {
		return rotationAngle(previous, current) > CUT_ANGLE;
	}

	/** Camera moves further than this (blocks) between frames: a teleport, not a step. */
	static final double RESET_DISTANCE = 8.0;
	/** A frame later than this after the previous evaluation cannot continue its history. */
	static final long RESET_GAP_NS = 250_000_000L;

	/**
	 * First applicable reason a frame cannot reuse the previous frame's temporal history, or null.
	 * Ordinary mouse turning, strafing and smooth zoom never qualify; only discontinuities do.
	 */
	static ResetReason cutReason(boolean havePrevious, boolean sameLevel, double cameraDistanceSq, long nanosSinceEvaluate,
			boolean sameView, Quaternionfc previousOrientation, Quaternionfc currentOrientation) {
		if (!havePrevious) return ResetReason.FIRST_FRAME;
		if (!sameLevel) return ResetReason.LEVEL;
		if (cameraDistanceSq > RESET_DISTANCE * RESET_DISTANCE) return ResetReason.TELEPORT;
		if (nanosSinceEvaluate > RESET_GAP_NS) return ResetReason.GAP;
		if (!sameView) return ResetReason.CAMERA;
		if (isRotationCut(previousOrientation, currentOrientation)) return ResetReason.ROTATION;
		return null;
	}
	/**
	 * Motion-vector reprojection: maps a jittered current clip position to the unjittered previous clip
	 * position, so the resulting vectors contain no camera jitter (motionVectorsJittered=false).
	 */
	static Matrix4f reprojection(Matrix4fc previous, Matrix4fc currentJittered, float dx, float dy, float dz) {
		return new Matrix4f(previous).translate(dx, dy, dz).mul(new Matrix4f(currentJittered).invert());
	}

	/**
	 * Vendor camera constant clipToPrevClip. Streamline requires constants to contain no temporal jitter
	 * (sl::Constants), so both projections are unjittered. The jitter is supplied separately as jitterOffset.
	 */
	static Matrix4f cameraReprojection(Matrix4fc previous, Matrix4fc current, float dx, float dy, float dz) {
		return new Matrix4f(previous).translate(dx, dy, dz).mul(new Matrix4f(current).invert());
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
