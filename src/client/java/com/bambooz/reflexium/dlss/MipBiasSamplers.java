package com.bambooz.reflexium.dlss;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Material-only replacement samplers. Vanilla's shared samplers and GUI retain their original LODs.
 * Replacements are owned here and closed only by {@link #close()}, and a replacement passed back in is
 * returned unchanged, so a corrected sampler never becomes the key of another replacement.
 */
final class MipBiasSamplers implements AutoCloseable {
	private final IdentityHashMap<GpuSampler, GpuSampler> samplers = new IdentityHashMap<>();
	private final Set<GpuSampler> replacements = Collections.newSetFromMap(new IdentityHashMap<>());
	private final BiFunction<GpuSampler, Float, GpuSampler> factory;
	private static final ThreadLocal<Float> creatingBias = new ThreadLocal<>();

	MipBiasSamplers() {
		this(MipBiasSamplers::createOnDevice);
	}

	/** Test seam: creates the replacement for an original sampler at a given bias. */
	MipBiasSamplers(BiFunction<GpuSampler, Float, GpuSampler> factory) {
		this.factory = factory;
	}

	/** Null for ordinary samplers; absolute native-plus-upscaler bias for an owned replacement. */
	public static Float creatingBias() { return creatingBias.get(); }

	private static GpuSampler createOnDevice(GpuSampler sampler, Float bias) {
		creatingBias.set(bias);
		try {
			return RenderSystem.getDevice().createSampler(sampler.getAddressModeU(), sampler.getAddressModeV(),
					sampler.getMinFilter(), sampler.getMagFilter(), sampler.getMaxAnisotropy(), sampler.getMaxLod());
		} finally {
			creatingBias.remove();
		}
	}

	GpuSampler get(GpuSampler original, float bias) {
		if (bias == 0 || original == null || replacements.contains(original) || original.getMaxLod().orElse(1000) <= 0.25) return original;
		GpuSampler existing = samplers.get(original);
		if (existing != null) return existing;
		GpuSampler created = factory.apply(original, appliedBias(original) + bias);
		samplers.put(original, created);
		replacements.add(created);
		return created;
	}

	static float appliedBias(GpuSampler sampler) {
		return sampler instanceof AppliedLodBias applied ? applied.reflexium$appliedLodBias() : 0.0f;
	}

	boolean owns(GpuSampler sampler) {
		return replacements.contains(sampler);
	}

	int size() {
		return samplers.size();
	}

	@Override
	public void close() {
		samplers.values().forEach(GpuSampler::close);
		samplers.clear();
		replacements.clear();
	}
}
