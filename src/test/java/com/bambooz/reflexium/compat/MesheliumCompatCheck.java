package com.bambooz.reflexium.compat;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** ABI check against the actual 1.6.2 jar, without loading optional mod classes. */
public final class MesheliumCompatCheck {
	public static void main(String[] args) throws Exception {
		if (MesheliumCompat.debugDelegation || MesheliumCompat.delegateTerrain())
			throw new AssertionError("Normal pack sessions must use Meshelium's own eligibility checks");
		Path jar = Path.of(System.getProperty("reflexium.mesheliumJar", "run/mods/meshelium-1.6.2+mc26.3.jar"));
		if (!Files.isRegularFile(jar)) {
			if (System.getProperty("reflexium.mesheliumJar") != null) throw new AssertionError("Missing Meshelium jar: " + jar);
			System.out.println("Meshelium ABI check skipped: optional jar absent");
			return;
		}
		try (var zip = new ZipFile(jar.toFile())) {
			var base = read(zip, "sodium/MesheliumChunkRendererBase");
			if (base.methods.stream().noneMatch(m -> m.name.equals("wantsDraw") && m.desc.endsWith(")Z")))
				throw new AssertionError("Missing pre-suspension stand-down boundary");
			var draw = base.methods.stream().filter(m -> m.name.equals("draw")).findFirst().orElseThrow();
			if (!draw.desc.contains("Lcom/mojang/renderpearl/api/textures/GpuSampler;)Z"))
				throw new AssertionError("Atlas sampler ABI changed");
			var renderer = read(zip, "sodium/MesheliumChunkRenderer");
			var render = renderer.methods.stream().filter(m -> m.name.equals("render")).findFirst().orElseThrow();
			boolean guarded = false, delegates = false;
			for (var instruction : render.instructions) {
				if (!(instruction instanceof MethodInsnNode call)) continue;
				if (call.name.equals("wantsDraw")) guarded = true;
				if (call.name.equals("suspend") && !guarded) throw new AssertionError("Suspends before guard");
				if (call.owner.endsWith("/ChunkRenderer") && call.name.equals("render")) delegates = true;
			}
			if (!guarded || !delegates) throw new AssertionError("Missing safe Sodium fallback");
		}
		System.out.println("Meshelium 1.6.2 sampler and pre-suspension delegation ABI checks passed");
	}

	private static ClassNode read(ZipFile zip, String name) throws Exception {
		var node = new ClassNode();
		try (var input = zip.getInputStream(zip.getEntry("com/deds/meshelium/" + name + ".class"))) {
			new ClassReader(input).accept(node, 0);
		}
		return node;
	}
}
