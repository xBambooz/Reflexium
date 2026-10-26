package com.bambooz.reflexium.framegen;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;

/** Layout of the native frame generation constants (native/frame_constants.h). Run by Gradle check. */
public final class FrameGenerationConstantsCheck {
	public static void main(String[] args) {
		// Reverse-Z, Vulkan [0,1] depth, matching Minecraft's Projection.
		Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(70), 16f / 9f, 1024, 0.05f, true);
		try (var arena = Arena.ofConfined()) {
			var constants = arena.allocate(FrameGenerationConstants.FLOATS * 4L, 4);
			Matrix4f remap = new Matrix4f().translation(0.2f, -0.4f, 0.3f).rotateY(0.7f);
			FrameGenerationConstants.write(constants, projection, remap, new Quaternionf(),
					10, 20, 30, 0.25f, -0.125f, 0.05f, 1024, 1.2f, 16f / 9f, 16.6f);
			// Read as C row-major matrices and multiply a row vector. It must produce JOML's result.
			for (int base : new int[] {0, 48}) {
				Vector4f input = new Vector4f(0.2f, 0.3f, -2, 1);
				Vector4f expected = (base == 0 ? projection : remap).transform(new Vector4f(input));
				for (int col = 0; col < 4; col++) {
					float value = 0;
					for (int row = 0; row < 4; row++) value += input.get(row) * constants.get(ValueLayout.JAVA_FLOAT, (base + row * 4L + col) * 4);
					near(value, expected.get(col));
				}
			}
			near(constants.get(ValueLayout.JAVA_FLOAT, 80 * 4L), 0.25f);
			near(constants.get(ValueLayout.JAVA_FLOAT, 82 * 4L), 10);
			near(constants.get(ValueLayout.JAVA_FLOAT, 93 * 4L), -1);
			near(constants.get(ValueLayout.JAVA_FLOAT, 98 * 4L), 16.6f);
		}
		System.out.println("Frame generation constant layout checks passed");
	}

	private static void near(float actual, float expected) {
		if (!Float.isFinite(actual) || Math.abs(actual - expected) > 0.00002f) {
			throw new AssertionError(actual + " != " + expected);
		}
	}
}
