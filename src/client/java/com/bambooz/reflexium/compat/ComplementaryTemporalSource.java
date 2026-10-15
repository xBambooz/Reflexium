package com.bambooz.reflexium.compat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Exact-source adapter for the reviewed pack's final full-scene temporal resolve. */
public final class ComplementaryTemporalSource {
	private static final String SHA256 = "3bcb286841eb17dbc217eca390431fcd6aaed547528bdfee0d0e8162e5e49444";
	private static final String RESOLVE = "        DoTAA(color, temp, z1);";
	private static volatile boolean applied;

	private ComplementaryTemporalSource() {}

	public static boolean applied() { return applied; }
	public static void reset() { applied = false; }

	public static List<String> adapt(String pack, String path, List<String> lines, boolean enabled) {
		if (!enabled || !pack.equals("ComplementaryReimagined_r5.9.3") || !path.equals("program/composite6.glsl")) return lines;
		try {
			String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
			if (!SHA256.equals(hash) || java.util.Collections.frequency(lines, RESOLVE) != 1) return lines;
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
		List<String> result = new ArrayList<>(lines);
		// Keep TAA-dependent effect sampling and the target schedule. SR owns the full-scene resolve.
		result.set(result.indexOf(RESOLVE), "        temp = color; // Reflexium: external temporal reconstruction");
		applied = true;
		return List.copyOf(result);
	}
}
