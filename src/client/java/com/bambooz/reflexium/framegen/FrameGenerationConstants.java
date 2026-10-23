package com.bambooz.reflexium.framegen;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import java.lang.foreign.MemorySegment;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

/** Float-only C ABI in native/frame_constants.h. NGX matrices use row vectors and row-major storage. */
final class FrameGenerationConstants {
	static final int FLOATS = 99;

	static void write(MemorySegment out, Matrix4fc projection, Matrix4fc clipToPrevious, Quaternionfc orientation,
			float x, float y, float z, float jitterX, float jitterY, float near, float far, float fov, float aspect, float frameMs) {
		matrix(out, 0, projection);
		matrix(out, 16, new Matrix4f(projection).invert());
		matrix(out, 32, new Matrix4f());
		matrix(out, 48, clipToPrevious);
		matrix(out, 64, new Matrix4f(clipToPrevious).invert());
		put(out, 80, jitterX); put(out, 81, jitterY);
		vector(out, 82, new Vector3f(x, y, z));
		vector(out, 85, new Vector3f(0, 1, 0).rotate(orientation));
		vector(out, 88, new Vector3f(1, 0, 0).rotate(orientation));
		vector(out, 91, new Vector3f(0, 0, -1).rotate(orientation));
		put(out, 94, near); put(out, 95, far); put(out, 96, fov); put(out, 97, aspect); put(out, 98, frameMs);
	}

	// JOML column-major storage equals row-major storage of its transpose (the NGX row-vector form).
	private static void matrix(MemorySegment out, int offset, Matrix4fc m) {
		float[] values = m.get(new float[16]);
		for (int i = 0; i < 16; i++) put(out, offset + i, values[i]);
	}
	private static void vector(MemorySegment out, int offset, Vector3f v) {
		put(out, offset, v.x); put(out, offset + 1, v.y); put(out, offset + 2, v.z);
	}
	private static void put(MemorySegment out, int offset, float value) { out.set(JAVA_FLOAT, offset * 4L, value); }
}
