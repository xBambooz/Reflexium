package com.bambooz.reflexium.compat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Source evidence for the fitted Euphoria sign material and color path, independent of folder names. */
public final class WorldTextPackSource {
	private static final Map<String, String> HASHES = Map.of(
			"program/gbuffers_block.glsl", "9ce7a1b4fd6d3edd0f7b886db6c6afb2ee6825cbe0dbf13b3e38ab9a571e19bb",
			"lib/materials/specificMaterials/others/signText.glsl", "87b1aedf79d955e5e0e80b79a06521e42d7b38b8605e4d8d6fe03bf678e85be3",
			"lib/lighting/mainLighting.glsl", "77e52942fbbb62fa16fb2c8e93c1b4dfa85cfb3049a613efd813154b1f30c1d8",
			"program/composite5.glsl", "4a70a5f5e0dfca7ed9aecb5b6d8600cc8979a75eb1b50b3ba0a3cf6695313894",
			"lib/colors/tonemaps.glsl", "2f4f3c0aade67030eca8508ead1a14df30e28a6f506a1c24a802b3eca91b8381");
	private static final Set<String> verified = ConcurrentHashMap.newKeySet();
	private WorldTextPackSource() { }
	public static void reset() { verified.clear(); }
	public static boolean verified() { return verified.size() == HASHES.size(); }
	static void observe(String path, List<String> lines) {
		String expected = HASHES.get(path);
		if (expected == null) return;
		if (expected.equals(ComplementaryTemporalSource.sha256(lines))) verified.add(path);
		else verified.remove(path);
	}
}
