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
		runtimeDownloadCheck();
		// Signature-polymorphic calls can compile a conditional MemorySegment as Object.
		// Check the actual bytecode ABI, since invokeExact rejects that only during native dispatch.
		for (Class<?> bridge : new Class<?>[] {FsrBridge.class, NgxBridge.class, FsrSwapchain.class}) {
			var bytecode = new org.objectweb.asm.tree.ClassNode();
			try (var in = bridge.getResourceAsStream(bridge.getSimpleName() + ".class")) {
				new org.objectweb.asm.ClassReader(in).accept(bytecode, 0);
			}
			int dispatches = 0;
			for (var method : bytecode.methods) for (var instruction : method.instructions) {
				if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
						&& call.owner.equals("java/lang/invoke/MethodHandle") && call.name.equals("invokeExact")) {
					if (call.desc.contains("Ljava/lang/Object;")) throw new AssertionError("Untyped native ABI in " + bridge + ": " + call.desc);
					dispatches++;
				}
			}
			if (dispatches == 0) throw new AssertionError("No native ABI calls checked in " + bridge);
		}
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
		try (var arena = java.lang.foreign.Arena.ofConfined()) {
			var constants = arena.allocate(FrameGenerationConstants.FLOATS * 4L, 4);
			Matrix4f remap = new Matrix4f().translation(0.2f, -0.4f, 0.3f).rotateY(0.7f);
			FrameGenerationConstants.write(constants, projection, remap, new org.joml.Quaternionf(),
					10, 20, 30, 0.25f, -0.125f, 0.05f, 1024, 1.2f, 16f / 9f, 16.6f);
			// Read as C row-major matrices and multiply a row vector. It must produce JOML's result.
			for (int base : new int[] {0, 48}) {
				Vector4f input = new Vector4f(0.2f, 0.3f, -2, 1);
				Vector4f expected = (base == 0 ? projection : remap).transform(new Vector4f(input));
				for (int col = 0; col < 4; col++) {
					float value = 0;
					for (int row = 0; row < 4; row++) value += input.get(row) * constants.get(java.lang.foreign.ValueLayout.JAVA_FLOAT, (base + row * 4L + col) * 4);
					near(value, expected.get(col));
				}
			}
			near(constants.get(java.lang.foreign.ValueLayout.JAVA_FLOAT, 80 * 4L), 0.25f);
			near(constants.get(java.lang.foreign.ValueLayout.JAVA_FLOAT, 82 * 4L), 10);
			near(constants.get(java.lang.foreign.ValueLayout.JAVA_FLOAT, 93 * 4L), -1);
			near(constants.get(java.lang.foreign.ValueLayout.JAVA_FLOAT, 98 * 4L), 16.6f);
		}
		for (int frame = 1; frame <= 72; frame++) {
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
		near(DlssMath.jitterPhases(1920, 1920, Upscaler.FSR), 8);
		near(DlssMath.jitterPhases(1920, 1920, Upscaler.DLSS), 32);
		near(DlssMath.jitterPhases(1280, 1920, Upscaler.FSR), 18);
		near(DlssMath.jitterPhases(960, 1920, Upscaler.FSR), 32);
		near(DlssMath.jitterPhases(640, 1920, Upscaler.DLSS), 72);
		near(DlssMath.mipBias(960, 1920), -2);
		near(DlssMath.mipBias(1280, 1920), -1.5849625f);
		near(DlssMath.mipBias(1920, 1920), 0);
		System.out.println("DLSS reprojection checks passed: translation, rotation, reverse depth and jitter.");
	}

	private static Vector4f ndc(Vector4f v) { return v.div(v.w); }
	private static void runtimeDownloadCheck() throws Exception {
		byte[] payload = "Reflexium runtime fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload));
		var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/runtime", exchange -> {
			exchange.sendResponseHeaders(200, payload.length);
			try (var out = exchange.getResponseBody()) { out.write(payload); }
		});
		Path folder = Files.createTempDirectory("reflexium-runtime-check");
		server.start();
		try {
			var source = java.net.URI.create("http://localhost:" + server.getAddress().getPort() + "/runtime");
			for (String name : new String[] {DlssRuntimeInstaller.FILE_NAME, DlssRuntimeInstaller.FG_FILE_NAME}) {
				Path installed = DlssRuntimeInstaller.install(folder, source, hash, name);
				if (!java.util.Arrays.equals(Files.readAllBytes(installed), payload)) throw new AssertionError("Corrupted runtime " + name);
			}
			Files.writeString(folder.resolve(DlssRuntimeInstaller.FILE_NAME), "user runtime");
			DlssRuntimeInstaller.startInstall(folder, source, hash).join();
			if (!Files.readString(folder.resolve(DlssRuntimeInstaller.FILE_NAME)).equals("user runtime")) throw new AssertionError("Replaced user runtime");
			try {
				DlssRuntimeInstaller.install(folder, source, "0".repeat(64), "bad.dll");
				throw new AssertionError("Accepted wrong runtime hash");
			} catch (java.io.IOException expected) {
				if (Files.exists(folder.resolve("bad.dll")) || Files.exists(folder.resolve("bad.dll.part"))) throw new AssertionError("Left failed runtime download");
			}
		} finally {
			server.stop(0);
			try (var files = Files.list(folder)) { for (Path file : files.toList()) Files.delete(file); }
			Files.delete(folder);
		}
	}
	private static void near(float actual, float expected) {
		if (!Float.isFinite(actual) || Math.abs(actual - expected) > 0.00002f) {
			throw new AssertionError(actual + " != " + expected);
		}
	}
}
