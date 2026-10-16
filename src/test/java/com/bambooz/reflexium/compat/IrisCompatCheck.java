package com.bambooz.reflexium.compat;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * No game needed. Checks the exact-hash source edits against the reviewed pack as Iris reads it, the fail-safe
 * paths for modified or unknown sources, and that every Iris member Reflexium hooks exists in the supported jar.
 */
public final class IrisCompatCheck {
	private static final String PACK = "ComplementaryReimagined_r5.9.3";

	public static void main(String[] args) throws Exception {
		abi(Path.of(System.getProperty("reflexium.irisJar", "run/mods/iris-fabric-experimental-build-26.3.jar")));
		sources(Path.of("run/shaderpacks/" + PACK + ".zip"));
		proxySwapchain();
		System.out.println("Iris compatibility checks passed");
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

	private static void abi(Path jar) throws Exception {
		if (!Files.isRegularFile(jar)) throw new AssertionError("Supported Iris jar missing: " + jar);
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			String version = new String(zip.getInputStream(zip.getEntry("fabric.mod.json")).readAllBytes());
			if (!version.contains("\"" + IrisCompat.SUPPORTED + "\"")) throw new AssertionError("Jar is not " + IrisCompat.SUPPORTED);
			require(zip, "net/irisshaders/iris/shaderpack/include/IncludeGraph", "readFile(Ljava/nio/file/Path;)Ljava/lang/String;");
			require(zip, "net/irisshaders/iris/pathways/HandRenderer", "setupGlState", "renderSolid", "renderTranslucent");
			require(zip, "net/irisshaders/iris/Iris", "loadShaderpack()V", "reload()V", "isPackInUseQuick()Z", "getIrisConfig");
			require(zip, "net/irisshaders/iris/pipeline/IrisRenderingPipeline", "normalNativeSampler", "specularNativeSampler");
			ClassNode target = node(zip, "net/irisshaders/iris/mixin/MixinRenderTarget");
			for (String field : List.of("iris$depthBufferVersion", "iris$colorBufferVersion"))
				if (target.fields.stream().noneMatch(f -> f.name.equals(field) && f.desc.equals("I"))) throw new AssertionError("Iris field missing: " + field);
			ClassNode targets = node(zip, "net/irisshaders/iris/targets/RenderTargets");
			// The attachment-version fix relies on resizeIfNeeded reading the version before the depth texture.
			if (targets.methods.stream().noneMatch(m -> m.name.equals("resizeIfNeeded") && m.desc.startsWith("(ILcom/mojang/renderpearl/api/textures/GpuTexture;II")))
				throw new AssertionError("RenderTargets.resizeIfNeeded contract changed");
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
			String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
			for (String call : List.of("vkCreateSwapchainKHR", "vkGetSwapchainImagesKHR", "vkAcquireNextImageKHR", "vkQueuePresentKHR", "vkDestroySwapchainKHR"))
				if (!text.contains(call)) throw new AssertionError("FSR proxy does not route " + call);
		}
	}
}
