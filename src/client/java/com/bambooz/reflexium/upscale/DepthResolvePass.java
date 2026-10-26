package com.bambooz.reflexium.upscale;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.motion.MotionVectorPass;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
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
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.OptionalDouble;

/** Display-resolution scene depth for post effects that run after the upscaler. See dlss_depth_resolve.fsh. */
public final class DepthResolvePass {
	private static final int UBO_SIZE = new Std140SizeCalculator().putVec4().putVec4().putVec4().get();

	private static final RenderPipeline PIPELINE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath(ReflexiumClient.MOD_ID, "pipeline/dlss_depth_resolve"))
			.withVertexShader("core/screenquad")
			.withFragmentShader(Identifier.fromNamespaceAndPath(ReflexiumClient.MOD_ID, "core/dlss_depth_resolve"))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withCull(false)
			.withBindGroupLayout(BindGroupLayout.builder()
					.withUniform("ReflexiumDepthResolve", UniformType.UNIFORM_BUFFER)
					.withUniform("SourceDepth", UniformType.COMBINED_IMAGE_SAMPLER)
					.build())
			.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
			.build();

	private final GpuBuffer uniforms = RenderSystem.getDevice()
			.createBuffer(() -> "Reflexium depth resolve UBO", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);

	public void render(GpuTextureView sourceDepth, GpuTextureView destinationDepth, int destinationWidth, int destinationHeight,
			float depthScale, float depthOffset, float jitterNdcX, float jitterNdcY) {
		render(sourceDepth, destinationDepth, destinationWidth, destinationHeight, depthScale, depthOffset,
				jitterNdcX, jitterNdcY, false);
	}

	/** {@code flipY} mirrors the source vertically, for the presented-backbuffer orientation of frame generation. */
	public void render(GpuTextureView sourceDepth, GpuTextureView destinationDepth, int destinationWidth, int destinationHeight,
			float depthScale, float depthOffset, float jitterNdcX, float jitterNdcY, boolean flipY) {
		GpuBufferSlice slice;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int sourceWidth = sourceDepth.texture().getWidth(0);
			int sourceHeight = sourceDepth.texture().getHeight(0);
			ByteBuffer data = Std140Builder.onStack(stack, UBO_SIZE)
					.putVec4(sourceWidth, sourceHeight, destinationWidth, destinationHeight)
					.putVec4(depthScale, depthOffset, jitterNdcX * 0.5f, jitterNdcY * 0.5f)
					.putVec4(flipY ? 1.0f : 0.0f, 0.0f, 0.0f, 0.0f).get();
			slice = MotionVectorPass.uploadUniforms(uniforms, data);
		}
		var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		var descriptor = RenderPassDescriptor.builder(() -> "Reflexium display depth for post effects")
				.withDepthAttachment(destinationDepth, OptionalDouble.empty())
				.withRenderArea(new RenderPass.RenderArea(0, 0, destinationWidth, destinationHeight)).build();
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
			pass.setUniform("ReflexiumDepthResolve", slice);
			pass.setUniform("SourceDepth", sourceDepth, nearest);
			pass.draw(3, 1, 0, 0);
		}
	}

	public void close() {
		uniforms.close();
	}
}
