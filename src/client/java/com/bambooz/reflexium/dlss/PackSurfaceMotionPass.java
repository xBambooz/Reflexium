package com.bambooz.reflexium.dlss;

import com.bambooz.reflexium.ReflexiumClient;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.resources.Identifier;
import java.util.Optional;

/** Merge independently rasterized pack surfaces, preserving existing object/hand velocities behind blended layers. */
final class PackSurfaceMotionPass {
	private static RenderPipeline pipeline(boolean composition) {
		var builder = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath(ReflexiumClient.MOD_ID,
					composition ? "pipeline/pack_surface_motion" : "pipeline/pack_surface_motion_dlss"))
			.withVertexShader("core/screenquad")
			.withFragmentShader(Identifier.fromNamespaceAndPath(ReflexiumClient.MOD_ID, "core/pack_surface_motion"))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withBindGroupLayout(BindGroupLayout.builder()
					.withUniform("BaseMotion", UniformType.COMBINED_IMAGE_SAMPLER)
					.withUniform("BaseDepth", UniformType.COMBINED_IMAGE_SAMPLER)
					.withUniform("HandDepth", UniformType.COMBINED_IMAGE_SAMPLER)
					.withUniform("OpaqueSurface", UniformType.COMBINED_IMAGE_SAMPLER)
					.withUniform("TransparentSurface", UniformType.COMBINED_IMAGE_SAMPLER).build())
			.withColorTargetState(0, new ColorTargetState(Optional.empty(), MotionVectorPass.FORMAT, ColorTargetState.WRITE_ALL));
		if (composition) builder.withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, ColorTargetState.WRITE_ALL));
		return builder.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true)).build();
	}
	private static final RenderPipeline MOTION_PIPELINE = pipeline(false);
	private static final RenderPipeline COMPOSITION_PIPELINE = pipeline(true);

	void render(RenderTarget base, RenderTarget motion, RenderTarget mask, GpuTextureView opaque, GpuTextureView transparent, GpuTextureView hand) {
		var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		var builder = RenderPassDescriptor.builder(() -> "Reflexium pack surface motion")
				.withColorAttachment(motion.getColorTextureView());
		if (mask != null) builder.withColorAttachment(mask.getColorTextureView());
		var descriptor = builder.withDepthAttachment(motion.getDepthTextureView()).build();
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(mask == null ? MOTION_PIPELINE : COMPOSITION_PIPELINE));
			pass.setUniform("BaseMotion", base.getColorTextureView(), nearest);
			pass.setUniform("BaseDepth", base.getDepthTextureView(), nearest);
			pass.setUniform("HandDepth", hand, nearest);
			pass.setUniform("OpaqueSurface", opaque, nearest);
			pass.setUniform("TransparentSurface", transparent, nearest);
			pass.draw(3, 1, 0, 0);
		}
	}
}
