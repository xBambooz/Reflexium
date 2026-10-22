package com.bambooz.reflexium.compat;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.lwjgl.util.shaderc.Shaderc.*;

/** Numerical depth/motion checks and optional ABI checks against the installed, exact-version jars. */
public final class DistantHorizonsCheck {
	public static void main(String[] args) throws Exception {
		System.setProperty("reflexium.disableDhHooks", "true");
		check(!DistantHorizonsCompat.isSupportedVersion(), "Diagnostic switch disables DH integration");
		check(DistantHorizonsCompat.canUpscale(), "Diagnostic switch must allow the comparison with SR enabled");
		DistantHorizonsCompat.configureUpscaling(true);
		DistantHorizonsCompat.captureNative(null);
		DistantHorizonsCompat.captureIris(null);
		check(DistantHorizonsCompat.depthFrame(1920, 1080) == null, "Disabled hooks must not publish DH depth");
		for (String name : new String[] {"dh.BlazeDhTerrainMixin", "dh.IrisDhTerrainMixin", "iris.DhProjectionMixin"}) {
			check(!new CompatMixinPlugin().shouldApplyMixin("unused", "com.bambooz.reflexium.mixin.compat." + name),
					"Diagnostic switch must disable " + name);
		}
		float near = 0.05f, far = 1024, lodFar = 32768, outputFar = lodFar * 2;
		Matrix4f main = perspective(near, far);
		Matrix4f nativeDh = perspective(64, lodFar);
		Matrix4f irisDh = DistantHorizonsCompat.irisDeviceProjection(new Matrix4f().setPerspective(1.2f, 16f / 9f, 64, lodFar));
		Matrix4f encoding = DistantHorizonsCompat.depthTransform(near, far, outputFar);
		check(DistantHorizonsCompat.depthTransform(near, far, far).equals(new Matrix4f()), "No DH keeps depth unchanged");
		for (Matrix4f dh : new Matrix4f[] {nativeDh, irisDh}) {
			Matrix4f toMain = new Matrix4f(main).mul(new Matrix4f(dh).invert());
			for (float distance : new float[] {64, 128, 512, 1024, 4096, 16000, 32000}) {
				Vector4f point = new Vector4f(0, 0, -distance, 1);
				Vector4f mainClip = toMain.transform(ndc(dh.transform(new Vector4f(point))));
				float raw = mainClip.z / mainClip.w;
				float encoded = raw * encoding.m22() + encoding.m32();
				// Independent double-precision reverse-Z formula, including beyond the vanilla far plane.
				double expected = near * ((double) outputFar / distance - 1) / (outputFar - (double) near);
				close(encoded, expected, 2.0e-8, "Re-encoded LOD at " + distance);
				check(encoded > 0, "Finite distant terrain must never become sky");
				if (distance > far) check(raw < 0, "LOD outside main clip range must survive negative main depth");
			}
			float nearerMain = ndc(main.transform(new Vector4f(0, 0, -128, 1))).z;
			float lod = ndc(toMain.transform(ndc(dh.transform(new Vector4f(0, 0, -512, 1))))).z;
			check(nearerMain > lod, "Near main geometry must win the view-distance comparison");
			check(lod > ndc(main.transform(new Vector4f(0, 0, -768, 1))).z, "Near LOD must win over farther main geometry");
			motion(dh);
		}
		for (float depth : new float[] {1, 0.5f, 0.001f}) {
			float encoded = depth * encoding.m22() + encoding.m32();
			close((encoded - encoding.m32()) / encoding.m22(), depth, 1.0e-7, "Main/hand depth round trip");
		}
		shaders();
		abi();
		System.out.println("Distant Horizons checks passed: depth range, occlusion, jitter-free camera motion, shaders and available ABIs");
	}

	private static void motion(Matrix4f projection) {
		float jx = 0.0003f, jy = -0.0002f;
		Matrix4f view = new Matrix4f().rotateY(0.17f).rotateX(-0.08f);
		Matrix4f current = new Matrix4f(projection).mul(view);
		Matrix4f jittered = new Matrix4f().translation(jx, jy, 0).mul(current);
		Matrix4f inverse = new Matrix4f(jittered).invert();
		Vector4f point = new Vector4f(400, 70, -4000, 1);
		Vector4f clip = ndc(jittered.transform(new Vector4f(point)));
		Vector4f still = ndc(new Matrix4f(current).mul(inverse).transform(new Vector4f(clip)));
		close(still.x - (clip.x - jx), 0, 2.0e-6, "Static X motion excludes jitter");
		close(still.y - (clip.y - jy), 0, 2.0e-6, "Static Y motion excludes jitter");
		Matrix4f previous = new Matrix4f(projection).rotateY(0.15f).rotateX(-0.07f);
		Vector4f actual = ndc(new Matrix4f(previous).translate(2, -1, 3).mul(inverse).transform(new Vector4f(clip)));
		Vector4f expected = ndc(previous.transform(new Vector4f(point).add(2, -1, 3, 0)));
		close(actual.x, expected.x, 2.0e-6, "Camera translation and rotation X");
		close(actual.y, expected.y, 2.0e-6, "Camera translation and rotation Y");
		check(Math.abs(actual.x - still.x) > 0.001, "Camera motion check must distinguish stationary reprojection");
	}

	private static void shaders() throws Exception {
		long compiler = shaderc_compiler_initialize(), options = shaderc_compile_options_initialize();
		try {
			shaderc_compile_options_set_auto_bind_uniforms(options, true);
			shaderc_compile_options_set_auto_map_locations(options, true);
			for (String name : new String[] {"dlss_motion", "dlss_depth_resolve"}) {
				String source = Files.readString(Path.of("src/client/resources/assets/reflexium/shaders/core/" + name + ".fsh"));
				long result = shaderc_compile_into_spv(compiler, source, shaderc_fragment_shader, name, "main", options);
				try {
					check(shaderc_result_get_compilation_status(result) == shaderc_compilation_status_success,
							name + ": " + shaderc_result_get_error_message(result));
				} finally { shaderc_result_release(result); }
			}
		} finally {
			shaderc_compile_options_release(options);
			shaderc_compiler_release(compiler);
		}
	}

	private static void abi() throws Exception {
		String appData = System.getenv("APPDATA");
		if (appData == null) return;
		Path mods = Path.of(appData, "ModrinthApp/profiles/Fabric 26.3/mods");
		for (String name : new String[] {"DistantHorizons-3.3.5-dev-26.3-fabric-neoforge.jar", "iris-fabric-1.12.0-snapshot+mc26.3-local.jar"}) {
			Path path = mods.resolve(name);
			if (!Files.isRegularFile(path)) { System.out.println("DH ABI check skipped: " + path); continue; }
			try (ZipFile zip = new ZipFile(path.toFile())) {
				String render = "(Lcom/seibel/distanthorizons/api/methods/events/sharedParameterObjects/DhApiRenderParam;ZLcom/seibel/distanthorizons/api/objects/util/IDhApiReadOnlyList;)V";
				if (name.startsWith("Distant")) {
					require(zip, "com/seibel/distanthorizons/common/render/blaze/BlazeDhTerrainRenderer", "render" + render);
					require(zip, "com/seibel/distanthorizons/core/config/types/ConfigEntry", "setApiValue(Ljava/lang/Object;Ljava/lang/String;)V", "getApiUser()Ljava/lang/String;", "getApiValue()Ljava/lang/Object;");
					require(zip, "com/seibel/distanthorizons/core/util/math/DhMat4f", "createJomlMatrix(Lcom/seibel/distanthorizons/api/objects/math/DhApiMat4f;)Lorg/joml/Matrix4f;");
					require(zip, "com/seibel/distanthorizons/api/interfaces/render/IDhApiRenderProxy", "getDhDepthTextureBlazeWrapper()Lcom/seibel/distanthorizons/api/objects/DhApiResult;");
				} else {
					require(zip, "net/irisshaders/iris/compat/dh/DHCompat", "getProjection(FF)Lorg/joml/Matrix4f;");
					require(zip, "net/irisshaders/iris/compat/dh/DHCompatInternal", "renderTerrain" + render,
							"getDepthTexture(Z)Lcom/mojang/renderpearl/api/textures/GpuTextureView;", "getProjection()Lorg/joml/Matrix4f;", "getModelView()Lorg/joml/Matrix4f;", "getFarPlane()F");
				}
			}
		}
	}

	private static void require(ZipFile zip, String type, String... methods) throws Exception {
		ClassNode node = new ClassNode();
		try (var in = zip.getInputStream(zip.getEntry(type + ".class"))) { new ClassReader(in).accept(node, ClassReader.SKIP_CODE); }
		for (String method : methods) check(node.methods.stream().anyMatch(m -> (m.name + m.desc).equals(method)), type + " lacks " + method);
	}

	private static Matrix4f perspective(float near, float far) { return new Matrix4f().setPerspective(1.2f, 16f / 9f, far, near, true); }
	private static Vector4f ndc(Vector4f clip) { return clip.div(clip.w); }
	private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
	private static void close(double actual, double expected, double tolerance, String message) {
		check(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance, message + ": " + actual + " != " + expected);
	}
}
