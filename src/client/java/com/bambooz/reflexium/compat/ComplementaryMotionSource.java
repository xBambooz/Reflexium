package com.bambooz.reflexium.compat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Actual pack vertex deformation, evaluated twice with the two frames' parameters, not CPU mesh replay. */
public final class ComplementaryMotionSource {
	private static final Map<String, String> HASHES = Map.of(
			"program/gbuffers_terrain.glsl", "3ff0a3a6086c01ccefe3de42619fd2832c235b684daa02ace8a0cb22c0522c44",
			"program/gbuffers_water.glsl", "1cc42fb08d09b9c95766108a42029b291ddca58879c799706e5c519a6131f4a7",
			"lib/materials/materialMethods/wavingBlocks.glsl", "e0126ae22d0e9621e025e0325e384db7eabd2742f567cb277cdb2ee706c0c284",
			"shaders.properties", "8222397b0f79d37a623872b6672625a411345fe2b6b5dbd6e6cc298c5a5c9ed3");
	private static final Set<String> applied = ConcurrentHashMap.newKeySet();
	private ComplementaryMotionSource() {}
	public static void reset() { applied.clear(); }
	public static boolean ready() { return ComplementaryHdr.ENABLED && applied.containsAll(HASHES.keySet()); }

	public static List<String> adapt(String pack, String path, List<String> lines) {
		if (!ComplementaryHdr.ENABLED || !ComplementaryHdr.reviewedArchive() || !pack.equals("ComplementaryReimagined_r5.9.3") || !ComplementaryTemporalSource.sha256(lines).equals(HASHES.get(path))) return lines;
		String source = String.join("\n", lines);
		if (path.equals("shaders.properties")) {
			// Independent motion/depth/contribution, never alpha-blended with opaque background velocities.
			source += "\nblend.gbuffers_terrain.colortex14=off\nblend.gbuffers_water.colortex15=off\n";
		} else if (path.endsWith("wavingBlocks.glsl")) {
			String previous = source.substring(source.indexOf("vec3 GetRawWave"));
			for (String function : new String[]{"GetRawWave", "GetWave", "DoWave_Foliage", "DoWave_Leaves", "DoWave_Water", "DoWave_Lava", "DoWave"}) {
				previous = previous.replaceAll("\\b" + function + "\\b", "RxPrevious_" + function);
			}
			previous = previous.replaceAll("\\bframeTimeCounter\\b", "rxPreviousWave.x")
					.replaceAll("\\brainFactor\\b", "rxPreviousWave.y")
					.replaceAll("\\binSnowy\\b", "rxPreviousWave.z")
					.replaceAll("\\beyeBrightnessM\\b", "rxPreviousWave.w")
					.replaceAll("\\bcameraPosition\\b", "previousCameraPosition");
			source += "\n#if defined GBUFFERS_TERRAIN || defined GBUFFERS_WATER\n" + previous + "\n#endif\n";
		} else {
			boolean water = path.endsWith("gbuffers_water.glsl");
			source = source.replace("#include \"/lib/common.glsl\"", "#include \"/lib/common.glsl\"\nuniform vec4 rxPreviousWave;\nuniform vec4 rxJitter;\nuniform float rxMotionValid;\n");
			source = source.replace("#ifdef FRAGMENT_SHADER", "#ifdef FRAGMENT_SHADER\nin vec4 rxCurrentClip;\nin vec4 rxPreviousClip;\nflat in float rxValid;");
			source = source.replace("#ifdef VERTEX_SHADER", "#ifdef VERTEX_SHADER\nout vec4 rxCurrentClip;\nout vec4 rxPreviousClip;\nflat out float rxValid;");
			var matcher = Pattern.compile("/\\* DRAWBUFFERS:([0-9]+) \\*/").matcher(source);
			StringBuilder rewritten = new StringBuilder();
			while (matcher.find()) {
				String digits = matcher.group(1);
				String targets = digits.chars().mapToObj(c -> String.valueOf((char)c)).collect(java.util.stream.Collectors.joining(","));
				matcher.appendReplacement(rewritten, "/* RENDERTARGETS: " + targets + "," + (water ? 15 : 14) + " */");
			}
			matcher.appendTail(rewritten); source = rewritten.toString();
			int end = source.indexOf("\n}\n\n#endif");
			if (end < 0) throw new IllegalStateException("Reviewed fragment main boundary missing");
			String contribution = water ? "clamp(color.a, 0.0, 1.0)" : "1.0";
			String slots = water
					? "#if DETAIL_QUALITY >= 3 || (WATER_REFLECT_QUALITY > 0 && WORLD_SPACE_REFLECTIONS > 0)\n#if WORLD_SPACE_REFLECTIONS > 0\ngl_FragData[5] = rxSurface;\n#else\ngl_FragData[3] = rxSurface;\n#endif\n#elif WORLD_SPACE_REFLECTIONS > 0\ngl_FragData[4] = rxSurface;\n#else\ngl_FragData[2] = rxSurface;\n#endif\n"
					: "#if BLOCK_REFLECT_QUALITY >= 2 && RP_MODE != 0\ngl_FragData[3] = rxSurface;\n#else\ngl_FragData[2] = rxSurface;\n#endif\n";
			source = source.substring(0, end) + "\nvec2 rxVelocity = (rxPreviousClip.xy / rxPreviousClip.w - rxCurrentClip.xy / rxCurrentClip.w) * 0.5 * vec2(viewWidth, viewHeight);\n"
					+ "vec4 rxSurface = vec4(rxVelocity, 1.0 - gl_FragCoord.z, rxValid * " + contribution + ");\n" + slots + source.substring(end);
			String wave = water ? "WAVING_WATER_VERTEX" : "WAVING_ANYTHING_TERRAIN";
			source = source.replace("vec4 position = gbufferModelViewInverse * gl_ModelViewMatrix * gl_Vertex;",
					"vec4 position = gbufferModelViewInverse * gl_ModelViewMatrix * gl_Vertex;\nvec4 rxBefore = position;\nrxBefore.xyz += cameraPosition - previousCameraPosition;\nvec3 rxSavedNormal = normal;\n#ifdef " + wave + "\nRxPrevious_DoWave(rxBefore.xyz, mat);\n#endif\nnormal = rxSavedNormal;\nrxPreviousClip = gbufferPreviousProjection * gbufferPreviousModelView * rxBefore;\nrxPreviousClip.xy -= rxJitter.zw * rxPreviousClip.w;\nrxValid = rxMotionValid * float(rxPreviousClip.w > 0.0);\n"
					// A mutable voxel volume has no previous-frame snapshot. Do not invent previous vine occlusion.
					+ "#if COLORED_LIGHTING_INTERNAL > 0\nif (mat == 10884 || mat == 10885) rxValid = 0.0;\n#endif\n");
			source = source.replace("gl_Position = gl_ProjectionMatrix * gbufferModelView * position;",
					"gl_Position = gl_ProjectionMatrix * gbufferModelView * position;\nrxCurrentClip = gl_Position;\nrxCurrentClip.xy -= rxJitter.xy * rxCurrentClip.w;\nrxValid *= float(rxCurrentClip.w > 0.0);\n");
		}
		applied.add(path);
		return List.of(source.split("\n", -1));
	}
}
