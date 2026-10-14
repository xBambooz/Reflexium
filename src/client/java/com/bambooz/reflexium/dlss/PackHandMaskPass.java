package com.bambooz.reflexium.dlss;

import com.bambooz.reflexium.ReflexiumClient;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.resources.Identifier;
import java.util.OptionalDouble;

/** Records only pixels whose actual depth changed during a shader pack's hand draw. */
final class PackHandMaskPass {
	private static final RenderPipeline PIPELINE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath(ReflexiumClient.MOD_ID, "pipeline/pack_hand_mask"))
			.withVertexShader("core/screenquad")
			.withFragmentShader(Identifier.fromNamespaceAndPath(ReflexiumClient.MOD_ID, "core/pack_hand_mask"))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withCull(false)
			.withBindGroupLayout(BindGroupLayout.builder()
					.withUniform("BeforeSampler", UniformType.COMBINED_IMAGE_SAMPLER)
					.withUniform("AfterSampler", UniformType.COMBINED_IMAGE_SAMPLER)
					.build())
			.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
			.build();

	/** Caller clears the mask once per frame, then accumulates both hand halves without another clear. */
	void render(RenderTarget mask, GpuTextureView before, GpuTextureView after) {
		var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		var descriptor = RenderPassDescriptor.builder(() -> "Reflexium shader pack hand coverage")
				.withDepthAttachment(mask.getDepthTextureView(), OptionalDouble.empty())
				.withRenderArea(new RenderPass.RenderArea(0, 0, mask.width, mask.height)).build();
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
			pass.setUniform("BeforeSampler", before, nearest);
			pass.setUniform("AfterSampler", after, nearest);
			pass.draw(3, 1, 0, 0);
		}
	}
}
