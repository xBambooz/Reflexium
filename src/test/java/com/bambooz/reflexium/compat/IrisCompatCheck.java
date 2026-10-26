package com.bambooz.reflexium.compat;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * No game needed. Checks the exact-hash source edits against the reviewed pack as Iris reads it, the fail-safe
 * paths for modified or unknown sources, and that every Iris member Reflexium hooks exists in the supported jar.
 */
public final class IrisCompatCheck {
	private static final String PACK = "ComplementaryReimagined_r5.9.3";

	public static void main(String[] args) throws Exception {
		abi(Path.of(System.getProperty("reflexium.irisJar", "run/mods/iris-fabric-experimental-build-26.3.jar")));
		sources(Path.of("run/shaderpacks/" + PACK + ".zip"));
		bsl(Path.of("run/shaderpacks/BSL_v10.1.8.zip"));
		proxySwapchain();
		depthOwnership();
		System.out.println("Iris compatibility checks passed");
	}

	private static void depthOwnership() {
		var oldMain = texture();
		var newMain = texture();
		var dh = texture();
		var mainFb = new DepthFramebuffer(oldMain);
		var dhFb = new DepthFramebuffer(dh);
		IrisCompat.replaceMainDepth(mainFb, newMain, oldMain);
		IrisCompat.replaceMainDepth(dhFb, newMain, oldMain);
		if (mainFb.texture != newMain || dhFb.texture != dh) throw new AssertionError("Depth ownership lost on main-target change");
	}

	private static GpuTexture texture() {
		return (GpuTexture) Proxy.newProxyInstance(
				IrisCompatCheck.class.getClassLoader(), new Class<?>[] {GpuTexture.class}, (p, m, a) -> null);
	}

	public static final class DepthFramebuffer {
		private GpuTexture texture;
		DepthFramebuffer(GpuTexture texture) { this.texture = texture; }
		public void addDepthAttachment(GpuTexture texture) { this.texture = texture; }
		public GpuTextureView getDepthView() {
			return (GpuTextureView) Proxy.newProxyInstance(
					IrisCompatCheck.class.getClassLoader(), new Class<?>[] {GpuTextureView.class}, (p, m, a) -> texture);
		}
	}

	private static void sources(Path zip) throws Exception {
		if (!Files.isRegularFile(zip)) { System.out.println("Iris source checks skipped: " + zip + " missing"); return; }
		var owner = IrisCompat.class.getDeclaredField("temporalOwner");
		owner.setAccessible(true);
		owner.setBoolean(null, true);
		try (FileSystem fs = FileSystems.newFileSystem(URI.create("jar:" + zip.toAbsolutePath().toUri()), Map.of())) {
			Path jitter = fs.getPath("/shaders/lib/antialiasing/jitter.glsl");
			String original = Files.readString(jitter);
			String edited = IrisCompat.adapt(PACK, jitter, original);
			if (edited.equals(original) || !edited.contains("#if 0 // Reflexium") || edited.contains("#if TAA_JITTER > 0")) throw new AssertionError("Pack jitter not removed");
			if (!IrisCompat.packJitterRemoved()) throw new AssertionError("Jitter evidence not published");
			for (String program : List.of("program/composite6.glsl", "program/composite7.glsl", "program/final.glsl")) {
				Path file = fs.getPath("/shaders/" + program);
				String source = Files.readString(file);
				if (IrisCompat.adapt(PACK, file, source).equals(source)) throw new AssertionError("Temporal edit missing for " + program);
			}
			if (!ComplementaryTemporalSource.appliedEdits().containsAll(Set.of(ComplementaryTemporalSource.RESOLVE, ComplementaryTemporalSource.FXAA, ComplementaryTemporalSource.SHARPEN)))
				throw new AssertionError("Applied edit evidence incomplete");
			if (!IrisCompat.temporalVerified()) throw new AssertionError("Verified pack not reported as verified");
			// Fail safe: a modified file, an unknown pack or temporal ownership off must leave sources untouched.
			String modified = original + "\n// user edit\n";
			if (!IrisCompat.adapt(PACK, jitter, modified).equals(modified)) throw new AssertionError("Modified source was edited");
			if (!IrisCompat.adapt("SomeOtherPack", jitter, original).equals(original)) throw new AssertionError("Unknown pack was edited");
			IrisCompat.onPackLoading();
			if (IrisCompat.packJitterRemoved() || IrisCompat.temporalVerified()) throw new AssertionError("Pack load must clear previous evidence");
			owner.setBoolean(null, false);
			if (!IrisCompat.adapt(PACK, jitter, original).equals(original)) throw new AssertionError("Off must keep the pack's own jitter");
		}
	}

	private static void bsl(Path zip) throws Exception {
		if (!Files.isRegularFile(zip)) { System.out.println("BSL source checks skipped: " + zip + " missing"); return; }
		var owner = IrisCompat.class.getDeclaredField("temporalOwner");
		owner.setAccessible(true);
		owner.setBoolean(null, true);
		IrisCompat.onPackLoading();
		try (FileSystem fs = FileSystems.newFileSystem(URI.create("jar:" + zip.toAbsolutePath().toUri()), Map.of())) {
			if (zip.getFileName().toString().equals("BSL_v10.1.8.zip")) {
				Path terrain = fs.getPath("/shaders/program/gbuffers_terrain.glsl");
				String source = Files.readString(terrain);
				String changed = BslFoliageMotion.adapt("program/gbuffers_terrain.glsl", source);
				if (changed.equals(source) || changed.contains("DRAWBUFFERS:") || !changed.contains("rxPreviousProjection * vec4(rxBefore, 1.0)")
						|| !changed.contains("blockID >= 100 && blockID <= 108") || !changed.contains("time = rxSavedTime")
						|| !changed.contains("rxPreviousEye - relativeEyePosition") || !changed.contains("rxBendOffset = vec3(0.0)")
						|| !changed.contains("#define RX_BEND_OFFSET"))
					throw new AssertionError("BSL deformation producer not installed");
				if (!changed.contains("vec4(rxVelocity, rxCurrentClip.z / rxCurrentClip.w, rxFoliage)"))
					throw new AssertionError("Foliage depth must preserve the raster projection's reverse-Z precision");
				String wave = Files.readString(fs.getPath("/shaders/lib/vertex/waving.glsl"));
				String previousBend = BslFoliageMotion.adapt("lib/vertex/waving.glsl", wave);
				if (!previousBend.contains("relativeEyePosition + rxBendOffset") || !previousBend.contains("#ifndef RX_BEND_OFFSET"))
					throw new AssertionError("BSL previous contact bend missing");
				if (!BslFoliageMotion.adapt("lib/vertex/waving.glsl", wave + "\n// edited").equals(wave + "\n// edited"))
					throw new AssertionError("Modified BSL wave must remain untouched");
				String modified = source + "\n// edited\n";
				if (!BslFoliageMotion.adapt("program/gbuffers_terrain.glsl", modified).equals(modified))
					throw new AssertionError("Modified BSL terrain must remain untouched");
			}
			Path settings = fs.getPath("/shaders/lib/settings.glsl");
			String original = Files.readString(settings);
			String edited = IrisCompat.adapt("BSL_v10.1.8", settings, original);
			if (!edited.contains("#if 1 // Reflexium") || edited.contains("#ifdef RETRO_FILTER")) throw new AssertionError("BSL anti-aliasing not removed");
			// Must follow the "#define SHARPEN_ENABLED" decision, or final.glsl blurs with mult 0.0625 * -1.
			if (edited.indexOf("#undef SHARPEN_ENABLED") < edited.indexOf("#define SHARPEN_ENABLED")) throw new AssertionError("BSL pre-upscale sharpen still enabled");
			// Option parsing must still see the original defines so saved pack options stay valid.
			if (!edited.contains("  #define TAA") || !edited.contains("  #define FXAA")) throw new AssertionError("BSL option defines changed");
			if (!IrisCompat.temporalVerified()) throw new AssertionError("BSL not reported as verified");
			for (String effect : List.of("lib/atmospherics/clouds.glsl", "lib/atmospherics/lightShafts.glsl",
					"lib/lighting/ambientOcclusion.glsl", "lib/reflections/raytrace.glsl", "program/deferred1.glsl")) {
				Path file = fs.getPath("/shaders/" + effect);
				String source = Files.readString(file);
				String sampling = IrisCompat.adapt("BSL_v10.1.8", file, source);
				if (sampling.equals(source) || !sampling.contains("#if 1 // Reflexium: temporal effect sampling"))
					throw new AssertionError("BSL temporal effect sampling frozen: " + effect);
				String changed = source + "\n// user edit\n";
				if (!IrisCompat.adapt("BSL_v10.1.8", file, changed).equals(changed))
					throw new AssertionError("Modified BSL effect edited: " + effect);
			}
			String modified = original + "\n// user edit\n";
			if (!IrisCompat.adapt("BSL_v10.1.8", settings, modified).equals(modified)) throw new AssertionError("Modified BSL source was edited");
			owner.setBoolean(null, false);
			if (!IrisCompat.adapt("BSL_v10.1.8", settings, original).equals(original)) throw new AssertionError("Off must keep BSL's own TAA");
		}
		IrisCompat.onPackLoading();
	}

	private static void abi(Path jar) throws Exception {
		if (!Files.isRegularFile(jar)) throw new AssertionError("Supported Iris jar missing: " + jar);
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			String version = new String(zip.getInputStream(zip.getEntry("fabric.mod.json")).readAllBytes());
			if (!version.contains("\"" + IrisCompat.SUPPORTED + "\"")) throw new AssertionError("Jar is not " + IrisCompat.SUPPORTED);
			require(zip, "net/irisshaders/iris/shaderpack/include/IncludeGraph", "readFile(Ljava/nio/file/Path;)Ljava/lang/String;");
			require(zip, "net/irisshaders/iris/pathways/HandRenderer", "setupGlState", "renderSolid", "renderTranslucent");
			ClassNode hand = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry("net/irisshaders/iris/pathways/HandRenderer.class")).readAllBytes()).accept(hand, 0);
			for (String name : List.of("renderSolid", "renderTranslucent")) {
				var method = hand.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
				boolean earlyReturn = false, backup = false, restore = false;
				for (var instruction : method.instructions) {
					if (instruction.getOpcode() == Opcodes.RETURN && !backup) earlyReturn = true;
					if (instruction instanceof MethodInsnNode call
							&& call.owner.equals("com/mojang/blaze3d/systems/RenderSystem")) {
						if (call.name.equals("backupProjectionMatrix")) { if (!earlyReturn) throw new AssertionError("Hand capture precedes early return"); backup = true; }
						if (call.name.equals("restoreProjectionMatrix")) { if (!backup) throw new AssertionError("Unpaired hand capture"); restore = true; }
					}
				}
				if (!backup || !restore) throw new AssertionError("Hand capture boundaries missing: " + name);
			}
			require(zip, "net/irisshaders/iris/Iris", "loadShaderpack()V", "reload()V", "isPackInUseQuick()Z", "getIrisConfig");
			require(zip, "net/irisshaders/iris/config/IrisConfig", "setShadersEnabled(Z)V", "save()V");
			require(zip, "net/irisshaders/iris/pipeline/IrisRenderingPipeline", "normalNativeSampler", "specularNativeSampler");
			require(zip, "net/irisshaders/iris/pipeline/IrisRenderingPipeline", "renderShadows", "beginLevelRendering", "beginTranslucents", "finalizeLevelRendering");
			require(zip, "net/irisshaders/iris/pipeline/CompositeRenderer", "renderAll()V");
			require(zip, "net/irisshaders/iris/pipeline/FinalPassRenderer", "renderFinalPass()V");
			require(zip, "net/irisshaders/iris/uniforms/CommonUniforms", "generalCommonUniforms");
			require(zip, "net/irisshaders/iris/gl/uniform/UniformHolder", "uniformMatrix", "uniform4f", "uniform3f");
			require(zip, "net/irisshaders/iris/uniforms/IrisExclusiveUniforms", "getEyePosition()Lorg/joml/Vector3d;");
			require(zip, "net/irisshaders/iris/uniforms/CameraUniforms", "getUnshiftedCameraPosition()Lorg/joml/Vector3d;");
			require(zip, "net/irisshaders/iris/targets/RenderTargets", "getDepthTextureView(I)Lcom/mojang/renderpearl/api/textures/GpuTextureView;");
			require(zip, "net/irisshaders/iris/targets/RenderTargets", "getOrCreate(I)Lnet/irisshaders/iris/targets/RenderTarget;");
			require(zip, "net/irisshaders/iris/uniforms/SystemTimeUniforms$Timer", "getFrameTimeCounter()F");
			require(zip, "net/irisshaders/iris/targets/RenderTarget", "getTextureView(ZZ)Lcom/mojang/renderpearl/api/textures/GpuTextureView;");
			ClassNode target = node(zip, "net/irisshaders/iris/mixin/MixinRenderTarget");
			for (String field : List.of("iris$depthBufferVersion", "iris$colorBufferVersion"))
				if (target.fields.stream().noneMatch(f -> f.name.equals(field) && f.desc.equals("I"))) throw new AssertionError("Iris field missing: " + field);
			ClassNode targets = node(zip, "net/irisshaders/iris/targets/RenderTargets");
			require(zip, "net/irisshaders/iris/targets/RenderFramebuffer", "getDepthView()Lcom/mojang/renderpearl/api/textures/GpuTextureView;",
					"addDepthAttachment(Lcom/mojang/renderpearl/api/textures/GpuTexture;)V");
			if (targets.fields.stream().noneMatch(f -> f.name.equals("currentDepthTexture"))) throw new AssertionError("Iris main-depth field missing");
			// The attachment-version fix relies on resizeIfNeeded reading the version before the depth texture.
			if (targets.methods.stream().noneMatch(m -> m.name.equals("resizeIfNeeded") && m.desc.startsWith("(ILcom/mojang/renderpearl/api/textures/GpuTexture;II")))
				throw new AssertionError("RenderTargets.resizeIfNeeded contract changed");
			ClassNode resizeCode = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry("net/irisshaders/iris/targets/RenderTargets.class")).readAllBytes()).accept(resizeCode, 0);
			var resize = resizeCode.methods.stream().filter(m -> m.name.equals("resizeIfNeeded")).findFirst().orElseThrow();
			int attachments = 0;
			for (var instruction : resize.instructions)
				if (instruction instanceof MethodInsnNode call && call.name.equals("addDepthAttachment")) attachments++;
			// The first two calls resize Iris's private depth copies. Only the third updates owned framebuffers.
			if (attachments != 3) throw new AssertionError("Iris depth-attachment redirect ordinal changed");
		}
	}

	private static void require(ZipFile zip, String type, String... members) throws Exception {
		ClassNode node = node(zip, type);
		for (String member : members) {
			boolean found = node.methods.stream().anyMatch(m -> (m.name + m.desc).startsWith(member) || m.name.equals(member));
			if (!found) throw new AssertionError(type + " lacks " + member);
		}
	}

	private static ClassNode node(ZipFile zip, String type) throws Exception {
		var entry = zip.getEntry(type + ".class");
		if (entry == null) throw new AssertionError("Iris class missing: " + type);
		ClassNode node = new ClassNode();
		new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_CODE);
		return node;
	}

	/** The FSR proxy handle is not a driver swapchain: all five operations must be routed. */
	private static void proxySwapchain() throws Exception {
		try (var in = IrisCompatCheck.class.getClassLoader().getResourceAsStream("com/bambooz/reflexium/mixin/VulkanGpuSurfaceMixin.class")) {
			if (in == null) throw new AssertionError("VulkanGpuSurfaceMixin not on the classpath");
			// Injection targets live in the constant pool as descriptor strings.
			String text = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
			for (String call : List.of("vkCreateSwapchainKHR", "vkGetSwapchainImagesKHR", "vkAcquireNextImageKHR", "vkQueuePresentKHR", "vkDestroySwapchainKHR"))
				if (!text.contains(call)) throw new AssertionError("FSR proxy does not route " + call);
		}
	}
}
