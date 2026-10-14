package com.bambooz.reflexium.dlss;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.IdentityHashMap;

/** Material-only replacements. Vanilla's shared samplers and GUI retain their original LODs. */
final class MipBiasSamplers implements AutoCloseable {
	private final IdentityHashMap<GpuSampler, GpuSampler> samplers = new IdentityHashMap<>();
	private static final ThreadLocal<Float> creatingBias = ThreadLocal.withInitial(() -> 0f);

	public static float creatingBias() { return creatingBias.get(); }

	GpuSampler get(GpuSampler original, float bias) {
		if (bias == 0 || original == null || original.getMaxLod().orElse(1000) <= 0.25) return original;
		return samplers.computeIfAbsent(original, sampler -> {
			creatingBias.set(bias);
			try {
				return RenderSystem.getDevice().createSampler(sampler.getAddressModeU(), sampler.getAddressModeV(),
						sampler.getMinFilter(), sampler.getMagFilter(), sampler.getMaxAnisotropy(), sampler.getMaxLod());
			} finally {
				creatingBias.remove();
			}
		});
	}

	@Override
	public void close() {
		samplers.values().forEach(GpuSampler::close);
		samplers.clear();
	}
}
