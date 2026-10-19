package com.bambooz.reflexium.compat;

import com.bambooz.reflexium.dlss.UpscalingRenderer;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.joml.Vector4f;
import java.util.List;
import java.util.function.Supplier;

/** Reviewed BSL cutout vegetation: evaluate the actual wave and contact bend at both frame times. */
public final class BslFoliageMotion {
	private static boolean sourceReady;
	private static boolean waveReady;
	private static Object timer;
	private static java.lang.reflect.Method clock;
	private static float previousTime;
	private static final org.joml.Vector3f previousEye = new org.joml.Vector3f();
	private static boolean haveEye;
	private static java.lang.reflect.Method eyePosition, cameraPosition;
	private BslFoliageMotion() { }
	public static void reset() { sourceReady = waveReady = haveEye = false; }
	public static boolean ready() { return sourceReady && waveReady && IrisCompat.isDrawingPack() && "BSL_v10.1.8".equals(IrisCompat.currentPackName()); }

	static String adapt(String path, String source) {
		if (path.equals("lib/vertex/waving.glsl")) {
			waveReady = "f4163b9a6b715dd62cc3df92ffc17137eb89ce8a9b78d857869cc0b1f74d5a50".equals(
					ComplementaryTemporalSource.sha256(List.of(source.replace("\r\n", "\n").split("\n", -1))));
			if (waveReady) return "#ifndef RX_BEND_OFFSET\nvec3 rxBendOffset = vec3(0.0);\n#endif\n" + source.replace(
					"position += relativeEyePosition + vec3(0.0, 0.62, 0.0);",
					"position += relativeEyePosition + rxBendOffset + vec3(0.0, 0.62, 0.0);");
		}
		if (!path.equals("program/gbuffers_terrain.glsl") || !"c36e8a1d5c58c214ba1f21e5a9d468bfba92a7e53ad78382696265f2dc84aeea".equals(
				ComplementaryTemporalSource.sha256(List.of(source.replace("\r\n", "\n").split("\n", -1))))) return source;
		String s = source.replace("\r\n", "\n");
		// Buffer 15 is unused by this reviewed source. No composite flips or blends its vectors.
		s = s.replace("#ifdef FSH", "#ifdef FSH\nvarying vec4 rxCurrentClip, rxPreviousClip;\nvarying float rxFoliage;\n/*\nconst int colortex15Format = RGBA32F;\nconst bool colortex15Clear = true;\nconst vec4 colortex15ClearColor = vec4(0.0, 0.0, 0.0, 0.0);\n*/\n");
		var matcher = java.util.regex.Pattern.compile("/\\* DRAWBUFFERS:([0-9]+) \\*/").matcher(s);
		StringBuilder out = new StringBuilder();
		while (matcher.find()) {
			String digits = matcher.group(1);
			String targets = digits.chars().mapToObj(c -> String.valueOf((char)c)).collect(java.util.stream.Collectors.joining(","));
			matcher.appendReplacement(out, "/* RENDERTARGETS: " + targets + ",15 */");
		}
		matcher.appendTail(out); s = out.toString();
		String output = "\nvec2 rxVelocity = (rxPreviousClip.xy / rxPreviousClip.w - rxCurrentClip.xy / rxCurrentClip.w) * 0.5;\n"
				+ "vec4 rxSurface = vec4(rxVelocity, 1.0 - gl_FragCoord.z, rxFoliage);\n"
				+ "#ifdef MCBL_SS\n#if defined ADVANCED_MATERIALS && defined REFLECTION_SPECULAR\ngl_FragData[5] = rxSurface;\n#else\ngl_FragData[2] = rxSurface;\n#endif\n"
				+ "#else\n#if defined ADVANCED_MATERIALS && defined REFLECTION_SPECULAR\ngl_FragData[4] = rxSurface;\n#else\ngl_FragData[1] = rxSurface;\n#endif\n#endif\n";
		int end = s.indexOf("\n}\n\n#endif");
		if (end < 0) throw new IllegalStateException("Reviewed BSL fragment boundary missing");
		s = s.substring(0, end) + output + s.substring(end);
		s = s.replace("#ifdef VSH", "#ifdef VSH\n#define RX_BEND_OFFSET\nvec3 rxBendOffset = vec3(0.0);\nvarying vec4 rxCurrentClip, rxPreviousClip;\nvarying float rxFoliage;\nuniform mat4 rxCurrentProjection, rxPreviousProjection;\nuniform vec4 rxMotionState;\nuniform vec3 rxPreviousEye;\n");
		s = s.replace("position.xyz = WavingBlocks(position.xyz, blockID, istopv);",
				"vec3 rxBase = position.xyz;\nfloat rxSavedTime = time;\ntime = rxMotionState.w * ANIMATION_SPEED;\n"
				+ "rxBendOffset = rxMotionState.xyz + rxPreviousEye - relativeEyePosition;\n"
				+ "vec3 rxBefore = WavingBlocks(rxBase, blockID, istopv) + rxMotionState.xyz;\ntime = rxSavedTime;\nrxBendOffset = vec3(0.0);\n"
				+ "position.xyz = WavingBlocks(position.xyz, blockID, istopv);\n"
				+ "#ifdef WORLD_CURVATURE\nrxBefore.y -= WorldCurvature(rxBefore.xz);\n#endif\n"
				+ "rxPreviousClip = rxPreviousProjection * vec4(rxBefore, 1.0);\n"
				+ "rxFoliage = float((blockID >= 100 && blockID <= 108) || blockID == 157);\n"
				+ "#ifdef WORLD_TIME_ANIMATION\nrxFoliage = 0.0;\n#endif\n");
		s = s.replace("gl_Position = gl_ProjectionMatrix * gbufferModelView * position;",
				"gl_Position = gl_ProjectionMatrix * gbufferModelView * position;\nrxCurrentClip = rxCurrentProjection * position;\n"
				+ "rxFoliage *= float(rxPreviousClip.w > 0.0 && rxCurrentClip.w > 0.0);\n");
		sourceReady = true;
		return s;
	}

	/** Register through Iris's public holder interface, without a compile dependency on optional Iris. */
	public static void install(Object holder) {
		try {
			Class<?> api = Class.forName("net.irisshaders.iris.gl.uniform.UniformHolder");
			Class<?> frequency = Class.forName("net.irisshaders.iris.gl.uniform.UniformUpdateFrequency");
			Object perFrame = frequency.getField("PER_FRAME").get(null);
			var matrix = api.getMethod("uniformMatrix", frequency, String.class, Supplier.class);
			matrix.invoke(holder, perFrame, "rxCurrentProjection", (Supplier<?>) () -> UpscalingRenderer.get().foliageProjection(false));
			matrix.invoke(holder, perFrame, "rxPreviousProjection", (Supplier<?>) () -> UpscalingRenderer.get().foliageProjection(true));
			api.getMethod("uniform4f", frequency, String.class, Supplier.class).invoke(holder, perFrame, "rxMotionState",
					(Supplier<Vector4f>) () -> UpscalingRenderer.get().foliageMotionState(previousTime));
			api.getMethod("uniform3f", frequency, String.class, Supplier.class).invoke(holder, perFrame, "rxPreviousEye",
					(Supplier<?>) () -> haveEye ? previousEye : relativeEye());
		} catch (ReflectiveOperationException e) { throw new IllegalStateException("Iris foliage uniform ABI changed", e); }
	}

	public static void endFrame() {
		if (!ready()) return;
		previousTime = currentTime();
		previousEye.set(relativeEye());
		haveEye = true;
	}
	private static org.joml.Vector3f relativeEye() {
		try {
			if (eyePosition == null) {
				eyePosition = Class.forName("net.irisshaders.iris.uniforms.IrisExclusiveUniforms").getDeclaredMethod("getEyePosition");
				eyePosition.setAccessible(true);
				cameraPosition = Class.forName("net.irisshaders.iris.uniforms.CameraUniforms").getMethod("getUnshiftedCameraPosition");
			}
			return new org.joml.Vector3f(new org.joml.Vector3d((org.joml.Vector3dc) cameraPosition.invoke(null)).sub((org.joml.Vector3dc) eyePosition.invoke(null)));
		} catch (ReflectiveOperationException e) { throw new IllegalStateException("Iris contact bend eye position unavailable", e); }
	}
	public static float previousTime() { return previousTime; }
	public static float currentTime() {
		try {
			if (clock == null) {
				timer = Class.forName("net.irisshaders.iris.uniforms.SystemTimeUniforms").getField("TIMER").get(null);
				clock = timer.getClass().getMethod("getFrameTimeCounter");
			}
			return (float) clock.invoke(timer);
		} catch (ReflectiveOperationException e) { throw new IllegalStateException("Iris foliage frame time unavailable", e); }
	}

	public static GpuTextureView surface() {
		if (!ready()) return null;
		try {
			Object manager = Class.forName("net.irisshaders.iris.Iris").getMethod("getPipelineManager").invoke(null);
			Object pipeline = manager.getClass().getMethod("getPipelineNullable").invoke(manager);
			if (pipeline == null) return null; // pack reloading or shaders just disabled
			var field = pipeline.getClass().getDeclaredField("renderTargets"); field.setAccessible(true);
			Object targets = field.get(pipeline);
			Object target = targets.getClass().getMethod("get", int.class).invoke(targets, 15);
			return target == null ? null : (GpuTextureView) target.getClass().getMethod("getTextureView", boolean.class, boolean.class).invoke(target, false, false);
		} catch (ReflectiveOperationException | ClassCastException e) { throw new IllegalStateException("Iris foliage target unavailable", e); }
	}
}
