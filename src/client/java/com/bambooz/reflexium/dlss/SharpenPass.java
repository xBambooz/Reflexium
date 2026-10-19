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
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.Optional;

/** Display-resolution RCAS after DLSS, matching FSR's built-in sharpening. See dlss_sharpen.fsh. */
final class SharpenPass {
	private static final int UBO_SIZE = new Std140SizeCalculator().putVec4().get();

	private static final RenderPipeline PIPELINE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath(ReflexiumClient.MOD_ID, "pipeline/dlss_sharpen"))
			.withVertexShader("core/screenquad")
			.withFragmentShader(Identifier.fromNamespaceAndPath(ReflexiumClient.MOD_ID, "core/dlss_sharpen"))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withCull(false)
			.withBindGroupLayout(BindGroupLayout.builder()
					.withUniform("ReflexiumSharpen", UniformType.UNIFORM_BUFFER)
					.withUniform("Source", UniformType.COMBINED_IMAGE_SAMPLER)
					.build())
			.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
			.build();

	private final GpuBuffer uniforms = RenderSystem.getDevice()
			.createBuffer(() -> "Reflexium sharpen UBO", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);

	/** FSR's mapping: slider 0..1 to 2..0 stops of RCAS attenuation, so equal settings sharpen equally. */
	static float rcasSharpness(float sharpness) {
		return sharpness <= 0 ? 0 : (float) Math.exp(-Math.log(2) * 2 * (1 - Math.min(sharpness, 1)));
	}

	void render(GpuTextureView source, RenderTarget destination, float sharpness) {
		GpuBufferSlice slice;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer data = Std140Builder.onStack(stack, UBO_SIZE).putVec4(rcasSharpness(sharpness), 0, 0, 0).get();
			slice = MotionVectorPass.uploadUniforms(uniforms, data);
		}
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
				.createRenderPass(() -> "Reflexium DLSS sharpen", destination.getColorTextureView(), Optional.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
			pass.setUniform("ReflexiumSharpen", slice);
			pass.setUniform("Source", source, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(3, 1, 0, 0);
		}
	}

	void close() {
		uniforms.close();
	}
}
