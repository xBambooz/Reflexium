package com.bambooz.reflexium.compat;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Run with the supported Vitrail jar on the classpath; no game or graphics device is needed. */
public final class VitrailCompatCheck {
	public static void main(String[] args) throws Exception {
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
		Class<?> type = Class.forName("dev.vitrail.pack.source.ShaderPackSource");
		try (var opened = (AutoCloseable) type.getMethod("open", Path.class).invoke(null, path)) {
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
