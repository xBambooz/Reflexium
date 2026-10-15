package com.bambooz.reflexium.dlss;

/**
 * Implemented by the Vulkan sampler class through a mixin: the mipLodBias value the sampler was actually
 * created with, which distinguishes a bound corrected sampler from a merely requested bias.
 */
public interface AppliedLodBias {
	float reflexium$appliedLodBias();
}
