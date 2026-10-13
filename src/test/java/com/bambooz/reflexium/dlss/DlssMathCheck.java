package com.bambooz.reflexium.dlss;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.nio.file.Files;
import java.nio.file.Path;

/** Run by Gradle check, no Minecraft runtime or graphics device needed. */
public final class DlssMathCheck {
	public static void main(String[] args) throws Exception {
		try (var paths = Files.walk(Path.of("src"))) {
			for (Path path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
				try (var reader = new JsonReader(Files.newBufferedReader(path))) {
					reader.setStrictness(Strictness.STRICT);
					JsonParser.parseReader(reader);
					if (reader.peek() != JsonToken.END_DOCUMENT) throw new AssertionError("Trailing JSON: " + path);
				}
			}
		}
		// Reverse-Z, Vulkan [0,1] depth, matching Minecraft's Projection.
		Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(70), 16f / 9f, 1024, 0.05f, true);
		for (int frame = 1; frame <= 64; frame++) {
			float jx = 2 * (DlssMath.halton(frame, 2) - 0.5f) / 1280;
			float jy = 2 * (DlssMath.halton(frame, 3) - 0.5f) / 720;
			for (boolean moving : new boolean[] {false, true}) {
				float dx = moving ? 0.3f : 0;
				float dy = moving ? -0.1f : 0;
				float dz = moving ? 0.4f : 0;
				Matrix4f current = new Matrix4f(projection).rotateY(moving ? 0.15f : 0);
				Matrix4f jittered = new Matrix4f().translation(jx, jy, 0).mul(current);
				Matrix4f remap = DlssMath.reprojection(projection, jittered, dx, dy, dz);
				for (float distance : new float[] {1, 10, 500}) {
					Vector4f world = new Vector4f(0.2f, 0.1f, -distance, 1);
					Vector4f local = new Vector4f(world).sub(dx, dy, dz, 0);
					Vector4f pixel = ndc(jittered.transform(new Vector4f(local)));
					Vector4f previous = ndc(remap.transform(new Vector4f(pixel)));
					Vector4f expected = ndc(projection.transform(new Vector4f(world)));
					near(previous.x, expected.x);
					near(previous.y, expected.y);
					if (!moving) {
						// A stationary camera produces zero velocity even as the jitter changes.
						near(previous.x - (pixel.x - jx), 0);
						near(previous.y - (pixel.y - jy), 0);
					}
				}
			}
		}
		near(DlssMath.halton(1, 2), 0.5f);
		near(DlssMath.halton(2, 2), 0.25f);
		System.out.println("DLSS reprojection checks passed: translation, rotation, reverse depth and jitter.");
	}

	private static Vector4f ndc(Vector4f v) { return v.div(v.w); }
	private static void near(float actual, float expected) {
		if (!Float.isFinite(actual) || Math.abs(actual - expected) > 0.00002f) {
			throw new AssertionError(actual + " != " + expected);
		}
	}
}
