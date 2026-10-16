package com.bambooz.reflexium.compat;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Run with the supported Vitrail jar on the classpath; no game or graphics device is needed. */
public final class VitrailCompatCheck {
	public static void main(String[] args) throws Exception {
		System.setProperty("reflexium.pack.hdr", "true");
		temporalSource();
		Class<?> session = Class.forName("dev.vitrail.settings.PackSession");
		Class<?> value = Class.forName("dev.vitrail.pack.option.OptionValue");
		Object taa = value.getMethod("of", String.class).invoke(null, "0");
		Object jitter = value.getMethod("of", String.class).invoke(null, "3");
		Object original = pack(session, "ComplementaryReimagined_r5.9.3.zip", Map.of("TAA_DEFINE", taa, "TAA_JITTER", jitter));
		var owner = VitrailCompat.class.getDeclaredField("temporalOwner");
		owner.setAccessible(true);
		owner.setBoolean(null, true);
		Object replaced = VitrailCompat.withTemporalOwner(original);
		if (replaced == original) throw new AssertionError("Known pack must receive an override");
		Map<?, ?> forced = (Map<?, ?>) session.getMethod("forced").invoke(replaced);
		if (!value.getMethod("asText").invoke(forced.get("TAA_DEFINE")).equals("1")
				|| !value.getMethod("asText").invoke(forced.get("TAA_JITTER")).equals("0")) {
			throw new AssertionError("Pack TAA must be enabled with its own jitter off");
		}
		Map<?, ?> saved = (Map<?, ?>) session.getMethod("forced").invoke(original);
		if (saved.get("TAA_DEFINE") != taa || saved.get("TAA_JITTER") != jitter) {
			throw new AssertionError("Original settings were mutated");
		}
		Object unknown = pack(session, "UnknownPack.zip", Map.of("TAA_DEFINE", taa));
		if (VitrailCompat.withTemporalOwner(unknown) != unknown) throw new AssertionError("Unknown pack was changed");
		Object newer = pack(session, "ComplementaryReimagined_r6.0.zip", Map.of("TAA_DEFINE", taa, "TAA_JITTER", jitter));
		if (VitrailCompat.withTemporalOwner(newer) != newer) throw new AssertionError("Unreviewed pack version was changed");
		owner.setBoolean(null, false);
		if (VitrailCompat.withTemporalOwner(original) != original) throw new AssertionError("Off must preserve saved settings");
		System.out.println("Vitrail temporal ownership checks passed");
	}

	private static void temporalSource() throws Exception {
		Path path = Path.of("run/shaderpacks/ComplementaryReimagined_r5.9.3.zip");
		if (!java.nio.file.Files.isRegularFile(path)) return;
		ComplementaryHdr.verifyArchive(path);
		Class<?> type = Class.forName("dev.vitrail.pack.source.ShaderPackSource");
		try (var opened = (AutoCloseable) type.getMethod("open", Path.class).invoke(null, path)) {
			for (String motionPath : List.of("/program/gbuffers_terrain.glsl", "/program/gbuffers_water.glsl", "/lib/materials/materialMethods/wavingBlocks.glsl", "/shaders.properties")) {
				Path motionFile = (Path) ((java.util.Optional<?>) type.getMethod("resolveInsideShaders", String.class).invoke(opened, motionPath)).orElseThrow();
				@SuppressWarnings("unchecked") List<String> originalMotion = (List<String>) type.getMethod("readLines", Path.class).invoke(opened, motionFile);
				String motionName = (String) type.getMethod("packName").invoke(opened), motionRel = (String) type.getMethod("rel", Path.class).invoke(opened, motionFile);
				List<String> transformed = ComplementaryMotionSource.adapt(motionName, motionRel, originalMotion);
				if (transformed == originalMotion) throw new AssertionError("Motion source guard mismatch " + motionRel + " hash=" + ComplementaryTemporalSource.sha256(originalMotion));
				String text = String.join("\n", transformed);
				if (motionPath.contains("gbuffers") && (!text.contains("RENDERTARGETS:") || !text.contains("rxPreviousClip = gbufferPreviousProjection") || !text.contains("gl_FragData[2] = rxSurface;"))) throw new AssertionError("Pack motion output contract");
				if (motionPath.contains("wavingBlocks") && (!text.contains("RxPrevious_DoWave") || !text.contains("rxPreviousWave.x") || !text.contains("previousCameraPosition"))) throw new AssertionError("Previous deformation did not retain the pack function");
				var modifiedMotion = new java.util.ArrayList<>(originalMotion); modifiedMotion.add("// modified");
				if (ComplementaryMotionSource.adapt(motionName, motionRel, modifiedMotion) != modifiedMotion) throw new AssertionError("Motion source guard failed");
			}
			Path hdrFile = (Path) ((java.util.Optional<?>) type.getMethod("resolveInsideShaders", String.class).invoke(opened, "/program/composite5.glsl")).orElseThrow();
			@SuppressWarnings("unchecked") List<String> hdrLines = (List<String>) type.getMethod("readLines", Path.class).invoke(opened, hdrFile);
			String hdrName = (String) type.getMethod("packName").invoke(opened), hdrRel = (String) type.getMethod("rel", Path.class).invoke(opened, hdrFile);
			List<String> hdr = ComplementaryHdr.adapt(hdrName, hdrRel, hdrLines);
			if (hdr == hdrLines || !String.join("\n", hdr).contains("vec2(textureSize(colortex3, 0))") || !String.join("\n", hdr).contains("DoCompTonemap(color);")) throw new AssertionError("HDR post adapter: " + hdrName + "/" + hdrRel + " reader=" + ComplementaryTemporalSource.sha256(hdrLines));
			var changedHdr = new java.util.ArrayList<>(hdrLines); changedHdr.add("// changed");
			if (ComplementaryHdr.adapt(hdrName, hdrRel, changedHdr) != changedHdr || ComplementaryHdr.adapt("Other", hdrRel, hdrLines) != hdrLines) throw new AssertionError("HDR source guard failed");
			if (!ComplementaryHdr.downstream("world0/composite5") || !ComplementaryHdr.downstream("final") || ComplementaryHdr.downstream("composite4")) throw new AssertionError("HDR stage boundary");
			Class<?> packPass = Class.forName("dev.vitrail.render.PackPass");
			packPass.getDeclaredField("samplerBindings");
			Class.forName("dev.vitrail.render.ColorTargets").getDeclaredMethod("view", int.class, Class.forName("dev.vitrail.pack.target.TargetSchedule$Side"));
			// Pre-reconstruction FXAA and sharpening: removed by default, kept for the diagnostic A/B, guarded by hash.
			for (String[] filter : new String[][]{{"/program/composite7.glsl", "FXAA311(color);"}, {"/program/final.glsl", "SharpenImage(color, texCoordM);"}}) {
				Path filterFile = (Path) ((java.util.Optional<?>) type.getMethod("resolveInsideShaders", String.class).invoke(opened, filter[0])).orElseThrow();
				@SuppressWarnings("unchecked")
				List<String> lines = (List<String>) type.getMethod("readLines", Path.class).invoke(opened, filterFile);
				String packName = (String) type.getMethod("packName").invoke(opened), rel = (String) type.getMethod("rel", Path.class).invoke(opened, filterFile);
				List<String> removed = ComplementaryTemporalSource.adapt(packName, rel, lines, true, false);
				if (removed == lines || removed.stream().anyMatch(l -> l.strip().equals(filter[1])) || removed.size() != lines.size())
					throw new AssertionError("Reviewed pre-reconstruction filter not removed from " + rel + " (reader hash " + ComplementaryTemporalSource.sha256(lines) + ")");
				if (ComplementaryTemporalSource.adapt(packName, rel, lines, true, true) != lines) throw new AssertionError("Diagnostic A/B did not keep " + rel);
				var modified = new java.util.ArrayList<>(lines); modified.add("// modified pack");
				if (ComplementaryTemporalSource.adapt(packName, rel, modified, true, false) != modified) throw new AssertionError("Hash guard failed for " + rel);
				if (!ComplementaryTemporalSource.appliedEdits().contains(rel)) throw new AssertionError("Applied edit not reported for " + rel);
			}
			Path file = (Path) ((java.util.Optional<?>) type.getMethod("resolveInsideShaders", String.class)
					.invoke(opened, "/program/composite6.glsl")).orElseThrow();
			@SuppressWarnings("unchecked")
			List<String> original = (List<String>) type.getMethod("readLines", Path.class).invoke(opened, file);
			String pack = (String) type.getMethod("packName").invoke(opened);
			String source = (String) type.getMethod("rel", Path.class).invoke(opened, file);
			if (ComplementaryTemporalSource.adapt(pack, source, original, false) != original) throw new AssertionError("Disabled adapter changed source");
			List<String> changed = ComplementaryTemporalSource.adapt(pack, source, original, true);
			if (changed == original || changed.stream().anyMatch(s -> s.contains("DoTAA(color, temp, z1)"))) throw new AssertionError("Reviewed resolve was not bypassed");
			if (original.stream().noneMatch(s -> s.contains("DoTAA(color, temp, z1)"))) throw new AssertionError("Original source was mutated");
			var unknown = new java.util.ArrayList<>(original);
			unknown.add("// modified pack");
			if (ComplementaryTemporalSource.adapt(pack, source, unknown, true) != unknown) throw new AssertionError("Source hash guard failed");
			if (ComplementaryTemporalSource.adapt("ComplementaryReimagined_r6.0", source, original, true) != original) throw new AssertionError("Pack version guard failed");
			if (ComplementaryTemporalSource.adapt(pack, "program/composite5.glsl", original, true) != original) throw new AssertionError("Program guard failed");
		} finally {
			ComplementaryTemporalSource.reset();
		}
	}

	private static Object pack(Class<?> type, String name, Map<String, Object> forced) throws Exception {
		RecordComponent[] fields = type.getRecordComponents();
		Class<?>[] types = new Class<?>[fields.length];
		Object[] values = new Object[fields.length];
		for (int i = 0; i < fields.length; i++) {
			types[i] = fields[i].getType();
			values[i] = switch (fields[i].getName()) {
				case "gameDirectory", "packPath" -> Path.of(".");
				case "packFileName" -> name;
				case "declared" -> Set.of("TAA_DEFINE", "TAA_JITTER");
				case "forced" -> forced;
				default -> null;
			};
		}
		return type.getConstructor(types).newInstance(values);
	}
}
