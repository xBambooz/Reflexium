package com.bambooz.reflexium.dlss;

import com.bambooz.reflexium.ReflexiumClient;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.Optional;

/** Debug capture only: packs motion or depth into an RGBA8 target the screenshot readback can decode. */
final class CaptureEncodePass {
	static final int MOTION = 0;
	static final int DEPTH = 1;
	private static final int UBO_SIZE = new Std140SizeCalculator().putVec4().get();

	private static final RenderPipeline PIPELINE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath(ReflexiumClient.MOD_ID, "pipeline/dlss_capture_encode"))
			.withVertexShader("core/screenquad")
			.withFragmentShader(Identifier.fromNamespaceAndPath(ReflexiumClient.MOD_ID, "core/dlss_capture_encode"))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withBindGroupLayout(BindGroupLayout.builder()
					.withUniform("ReflexiumCaptureEncode", UniformType.UNIFORM_BUFFER)
					.withUniform("MotionSampler", UniformType.COMBINED_IMAGE_SAMPLER)
					.withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
					.build())
			.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
			.build();

	/** Encodes {@code source}'s RG16F color (mode MOTION) or depth (mode DEPTH) into {@code destination}'s RGBA8 color. */
	void render(RenderTarget source, RenderTarget destination, int mode) {
		GpuBufferSlice slice;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer data = Std140Builder.onStack(stack, UBO_SIZE).putVec4(mode, 0.0f, 0.0f, 0.0f).get();
			// Each queued mode draw needs its own immutable data until the GPU consumes it.
			int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
			try (GpuBufferSlice.MappedView view = RenderSystem.getDevice().createCommandEncoder().transientMemory()
					.allocateGpuMapped(data.remaining(), alignment, GpuBuffer.USAGE_UNIFORM)) {
				view.data().put(data);
				slice = view.slice();
			}
		}
		GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
				.createRenderPass(() -> "Reflexium capture encode", destination.getColorTextureView(), Optional.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
			pass.setUniform("ReflexiumCaptureEncode", slice);
			pass.setUniform("MotionSampler", source.getColorTextureView(), nearest);
			pass.setUniform("DepthSampler", source.getDepthTextureView(), nearest);
			pass.draw(3, 1, 0, 0);
		}
	}
}
