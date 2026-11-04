package com.bambooz.reflexium.compat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exact-source adapter for the reviewed pack, so the external upscaler is the only temporal resolve and the only
 * antialiasing/sharpening applied to the jittered render-resolution image. DLSS and FSR expect aliased, jittered
 * input with as few effects as possible applied before them; the pack's FXAA and its TAA-compensating sharpen ran
 * on every jittered sample before reconstruction. Each edit needs its exact program path and Vitrail reader hash;
 * any other source keeps the pack's own filters.
 */
public final class ComplementaryTemporalSource {
	private static final String PACK = "ComplementaryReimagined_r5.9.3";
	private static final String EUPHORIA = PACK + " + EuphoriaPatches_1.10.5";
	static boolean supports(String pack) { return PACK.equals(pack) || EUPHORIA.equals(pack); }
	public static final String RESOLVE = "program/composite6.glsl", FXAA = "program/composite7.glsl", SHARPEN = "program/final.glsl";
	private record Edit(String path, String sha256, String euphoriaSha256, String line, String replacement, boolean preFilter) { }
	private static final List<Edit> EDITS = List.of(
			// Keep TAA-dependent effect sampling and the target schedule. SR owns the full-scene resolve.
			new Edit(RESOLVE, "3bcb286841eb17dbc217eca390431fcd6aaed547528bdfee0d0e8162e5e49444",
					"d14e6985efa147b588e19a87d82d8aa02d27e4b6af92c4a43c5a22affde8ebd6",
					"DoTAA(color, temp, z1);", "temp = color; // Reflexium: external temporal reconstruction", false),
			new Edit(FXAA, "edcba9d87976622827a373a16a077cd77533b37ae6082ecba0c69174e30b95ff",
					"bc789de027f0c3c76c09a8eebe84d0a272aa55eb00a4d35083555ee5275f3ad0",
					"FXAA311(color);", "// Reflexium: external reconstruction is the antialiasing", true),
			new Edit(SHARPEN, "6e33e63d4c841f1e1468a985a9c05eed30a7b42e915957677c00753fad60b3c3",
					"f33a40aedbca1341dd07c67e730d2e2290fac96beba94d7524171c51e398f969",
					"SharpenImage(color, texCoordM);", "// Reflexium: no sharpening of jittered input before reconstruction", true));
	private static final Set<String> applied = ConcurrentHashMap.newKeySet();

	private ComplementaryTemporalSource() {}

	/** The full-scene resolve bypass reached the flattened source. */
	public static boolean applied() { return applied.contains(RESOLVE); }
	public static Set<String> appliedEdits() { return Set.copyOf(applied); }
	public static void reset() { applied.clear(); ComplementaryHdr.resetSource(); }

	/** {@code preFilters} keeps the pack's FXAA and sharpening for a matched diagnostic comparison. */
	public static List<String> adapt(String pack, String path, List<String> lines, boolean enabled, boolean preFilters) {
		if (!enabled || !supports(pack)) return lines;
		Edit edit = EDITS.stream().filter(e -> e.path.equals(path)).findFirst().orElse(null);
		if (edit == null || edit.preFilter && preFilters || !(EUPHORIA.equals(pack) ? edit.euphoriaSha256 : edit.sha256).equals(sha256(lines))) return lines;
		int index = -1;
		for (int i = 0; i < lines.size(); i++) {
			if (!lines.get(i).strip().equals(edit.line)) continue;
			if (index >= 0) return lines;
			index = i;
		}
		if (index < 0) return lines;
		List<String> result = new ArrayList<>(lines);
		String original = lines.get(index);
		String indent = original.substring(0, original.indexOf(edit.line));
		result.set(index, indent + edit.replacement + (original.endsWith("\r") ? "\r" : ""));
		applied.add(path);
		return List.copyOf(result);
	}
	public static List<String> adapt(String pack, String path, List<String> lines, boolean enabled) { return adapt(pack, path, lines, enabled, false); }

	static String sha256(List<String> lines) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}
}
