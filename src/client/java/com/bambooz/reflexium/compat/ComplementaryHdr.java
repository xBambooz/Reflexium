package com.bambooz.reflexium.compat;

import com.bambooz.reflexium.upscale.UpscalingRenderer;
import com.bambooz.reflexium.vulkan.VulkanSession;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkFormatProperties;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reviewed composite5 boundary. Pack world targets stay at input size; post targets are independent. */
public final class ComplementaryHdr {
	// Keep experimental graph changes opt-in until matched temporal sequences pass on the installed renderer.
	public static final boolean ENABLED = Boolean.getBoolean("reflexium.pack.hdr");
	private static final String HASH = "f6f348a0f1218fd2799deee1299f687186c4c39da59c5354672260dfc8900026";
	private static volatile boolean sourceVerified;
	private static volatile boolean formatVerified;
	private static volatile boolean archiveVerified;
	private static volatile long epoch;
	public static long epoch() { return epoch; }
	public static boolean reviewedArchive() { return archiveVerified; }
	public static void verifyArchive(Path path) {
		archiveVerified = false;
		try {
			archiveVerified = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))
					.equals("fed6c879e732cf7fc7bc6570ebb1def43235aa7e96f62859fbdbdbfa0b484b38");
		} catch (IOException | NoSuchAlgorithmException ignored) { /* Unknown pack keeps its ordinary route. */ }
	}
	private static final Map<GpuTextureView, TextureTarget> post = new IdentityHashMap<>();
	private static final Map<GpuTextureView, GpuTextureView> written = new IdentityHashMap<>();
	private static final Map<GpuTextureView, GpuTextureView> pending = new IdentityHashMap<>();
	private static Object originalWorld, displayWorld;
	private ComplementaryHdr() {}
	public static boolean storageSupported() {
		var session = VulkanSession.current();
		if (session == null) return false;
		try (var stack = MemoryStack.stackPush()) {
			var properties = VkFormatProperties.calloc(stack);
			VK10.vkGetPhysicalDeviceFormatProperties(session.physicalDevice(), VK10.VK_FORMAT_R16G16B16A16_SFLOAT, properties);
			return (properties.optimalTilingFeatures() & VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) != 0;
		}
	}

	public static void resetSource() { epoch++; sourceVerified = formatVerified = archiveVerified = false; ComplementaryMotionSource.reset(); PackMotionState.resetEvidence(); }
	public static boolean ready() { return ENABLED && sourceVerified && formatVerified && ComplementaryTemporalSource.appliedEdits().containsAll(
			Set.of(ComplementaryTemporalSource.RESOLVE, ComplementaryTemporalSource.FXAA, ComplementaryTemporalSource.SHARPEN)); }

	public static List<String> adapt(String pack, String path, List<String> lines) {
		if (!archiveVerified) return lines;
		if (ENABLED && pack.equals("ComplementaryReimagined_r5.9.3") && path.equals("lib/pipelineSettings.glsl")
				&& "34fae16ac0e31077bd83b41d2d157f83ca32aac0423dc9d2fff6d4c69a428e5d".equals(ComplementaryTemporalSource.sha256(lines))) {
			formatVerified = true;
			return List.of((String.join("\n", lines).replace("const int colortex0Format = R11F_G11F_B10F;", "const int colortex0Format = RGBA16F;")
					+ "\n/*\nconst int colortex14Format = RGBA16F;\nconst int colortex15Format = RGBA16F;\n*/\n").split("\n", -1));
		}
		if (!ENABLED || !pack.equals("ComplementaryReimagined_r5.9.3") || !path.equals("program/composite5.glsl")
				|| !HASH.equals(ComplementaryTemporalSource.sha256(lines))) return lines;
		// Bloom's packed atlas is still the input-sized image. Display viewport uniforms must not rescale it.
		String text = String.join("\n", lines)
				.replace("vec2(viewWidth, viewHeight) / vec2(1920.0, 1080.0)", "vec2(textureSize(colortex3, 0)) / vec2(1920.0, 1080.0)")
				.replace("texelFetch(colortex6, texelCoord, 0)", "texelFetch(colortex6, ivec2(texCoord * vec2(textureSize(colortex6, 0))), 0)")
				.replace("texelFetch(dhDepthTex, texelCoord, 0)", "texelFetch(dhDepthTex, ivec2(texCoord * vec2(textureSize(dhDepthTex, 0))), 0)")
				.replace("texelFetch(vxDepthTexTrans, texelCoord, 0)", "texelFetch(vxDepthTexTrans, ivec2(texCoord * vec2(textureSize(vxDepthTexTrans, 0))), 0)");
		sourceVerified = true;
		return List.of(text.split("\n", -1));
	}

	public static boolean downstream(String path) {
		String program = path.substring(path.lastIndexOf('/') + 1);
		return Set.of("composite5", "composite6", "composite7", "final").contains(program);
	}
	/**
	 * Viewport and viewWidth/viewHeight follow the same per-frame state as attachments and samplers. composite5
	 * sizes before its HEAD boundary is guaranteed to have run, so it uses the boundary's precondition instead.
	 */
	public static boolean displaySized(String path) {
		UpscalingRenderer renderer = UpscalingRenderer.get();
		return downstream(path) && (renderer.packHdrThisFrame() || first(path) && renderer.packHdrBoundaryDue());
	}
	public static boolean first(String path) { return path.equals("composite5") || path.endsWith("/composite5"); }
	public static GpuTextureView input(Object pass, Object targets) {
		try {
			var field = pass.getClass().getDeclaredField("samplerBindings"); field.setAccessible(true);
			for (Object binding : (List<?>) field.get(pass)) {
				var sampler = binding.getClass().getDeclaredMethod("sampler"); sampler.setAccessible(true);
				if (!sampler.invoke(binding).equals("colortex0")) continue;
				var side = binding.getClass().getDeclaredMethod("side"); side.setAccessible(true);
				var view = targets.getClass().getDeclaredMethod("view", int.class, side.getReturnType()); view.setAccessible(true);
				return (GpuTextureView) view.invoke(targets, 0, side.invoke(binding));
			}
			throw new IllegalStateException("composite5 has no colortex0 binding");
		} catch (ReflectiveOperationException e) { throw new IllegalStateException("Vitrail HDR input ABI changed", e); }
	}
	public static GpuTextureView geometrySurface(Object targets, int index) {
		try {
			Class<?> side = Class.forName("dev.vitrail.pack.target.TargetSchedule$Side");
			var view = targets.getClass().getDeclaredMethod("view", int.class, side); view.setAccessible(true);
			return (GpuTextureView) view.invoke(targets, index, side.getField("MAIN").get(null));
		} catch (ReflectiveOperationException e) { throw new IllegalStateException("Vitrail surface motion ABI changed", e); }
	}
	public static void beginFrame() { written.clear(); pending.clear(); }
	public static void finishPass() { written.putAll(pending); pending.clear(); }
	public static void release() {
		for (TextureTarget target : post.values()) target.destroyBuffers();
		post.clear(); written.clear(); pending.clear(); originalWorld = displayWorld = null;
	}

	public static GpuTextureView attachment(String path, GpuTextureView original) {
		UpscalingRenderer renderer = UpscalingRenderer.get();
		if (!renderer.packHdrThisFrame() || !downstream(path)) return original;
		TextureTarget target = post.computeIfAbsent(original, view -> new TextureTarget("Reflexium pack display post",
				renderer.outputWidth(), renderer.outputHeight(), view.texture().getFormat(), null));
		pending.put(original, target.getColorTextureView());
		return target.getColorTextureView();
	}

	public static GpuTextureView sampler(String path, String name, GpuTextureView original) {
		UpscalingRenderer renderer = UpscalingRenderer.get();
		if (!renderer.packHdrThisFrame() || !downstream(path)) return original;
		if (first(path) && name.equals("colortex0")) return renderer.packHdrColor();
		return written.getOrDefault(original, original);
	}

	/** Only the four display passes get viewport values. Geometry and all upstream histories keep input values. */
	public static Object world(String path, Object world) {
		if (!displaySized(path)) return world;
		if (world != originalWorld) {
			originalWorld = world;
			try {
				Class<?> api = Class.forName("dev.vitrail.uniform.WorldState");
				displayWorld = Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, (proxy, method, args) -> {
					if (method.getName().equals("viewWidth")) return (float) UpscalingRenderer.get().outputWidth();
					if (method.getName().equals("viewHeight")) return (float) UpscalingRenderer.get().outputHeight();
					return method.invoke(world, args);
				});
			} catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
		}
		return displayWorld;
	}
}
