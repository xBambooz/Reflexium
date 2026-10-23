package com.bambooz.reflexium.dlss;

import com.bambooz.reflexium.ReflexiumClient;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/** Overwrite only the visible, independently animated foliage surface. */
final class FoliageMotionPass {
	private static final RenderPipeline PIPELINE = pipeline(false), DEPTH_DEBUG = pipeline(true);
	private static RenderPipeline pipeline(boolean debug) {
		var builder = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath(ReflexiumClient.MOD_ID, "pipeline/foliage_motion" + (debug ? "_depth_debug" : "")))
			.withVertexShader("core/screenquad")
			.withFragmentShader(Identifier.fromNamespaceAndPath(ReflexiumClient.MOD_ID, "core/foliage_motion"))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withBindGroupLayout(BindGroupLayout.builder()
					.withUniform("Surface", UniformType.COMBINED_IMAGE_SAMPLER)
					.withUniform("SceneDepth", UniformType.COMBINED_IMAGE_SAMPLER)
					.withUniform("HandDepth", UniformType.COMBINED_IMAGE_SAMPLER).build())
			.withColorTargetState(new ColorTargetState(Optional.empty(), debug ? GpuFormat.RGBA8_UNORM : MotionVectorPass.FORMAT, ColorTargetState.WRITE_ALL));
		if (debug) builder.withShaderDefine("RX_DEPTH_DEBUG");
		return builder.build();
	}
	void render(RenderTarget motion, GpuTextureView surface, GpuTextureView scene, GpuTextureView hand) {
		render(motion, surface, scene, hand, false);
	}
	void render(RenderTarget motion, GpuTextureView surface, GpuTextureView scene, GpuTextureView hand, boolean debug) {
		var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Reflexium BSL foliage motion", motion.getColorTextureView(), Optional.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(debug ? DEPTH_DEBUG : PIPELINE));
			pass.setUniform("Surface", surface, nearest);
			pass.setUniform("SceneDepth", scene, nearest);
			pass.setUniform("HandDepth", hand, nearest);
			pass.draw(3, 1, 0, 0);
		}
	}
}
