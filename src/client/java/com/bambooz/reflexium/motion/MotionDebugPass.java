package com.bambooz.reflexium.motion;

import com.bambooz.reflexium.ReflexiumClient;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.minecraft.resources.Identifier;

import java.util.Optional;
import java.util.OptionalDouble;

/** Debug only: fullscreen overlay of the motion vector texture (hue = direction, brightness = magnitude). */
public final class MotionDebugPass {
	private static final RenderPipeline PIPELINE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath(ReflexiumClient.MOD_ID, "pipeline/dlss_motion_debug"))
			.withVertexShader("core/screenquad")
			.withFragmentShader(Identifier.fromNamespaceAndPath(ReflexiumClient.MOD_ID, "core/dlss_motion_debug"))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withBindGroupLayout(BindGroupLayout.builder()
					.withUniform("MotionSampler", UniformType.COMBINED_IMAGE_SAMPLER)
					.build())
			.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
			.build();

	/** Draws {@code motion}'s color texture over {@code main}'s color texture (any resolution). */
	public void render(RenderTarget motion, RenderTarget main) {
		GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
				.createRenderPass(() -> "Reflexium motion vector view", main.getColorTextureView(), Optional.empty(),
						main.getDepthTextureView(), OptionalDouble.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
			pass.setUniform("MotionSampler", motion.getColorTextureView(), linear);
			pass.draw(3, 1, 0, 0);
		}
	}
}
