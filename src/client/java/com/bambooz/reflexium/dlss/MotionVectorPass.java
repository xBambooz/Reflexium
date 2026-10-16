package com.bambooz.reflexium.dlss;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.compat.DistantHorizonsCompat;
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
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;

/** Fullscreen pass writing DLSS motion vectors (RG16F, render pixels) from depth. See dlss_motion.fsh. */
final class MotionVectorPass implements AutoCloseable {
	static final GpuFormat FORMAT = GpuFormat.RG16_FLOAT;

	private static final int UBO_SIZE = new Std140SizeCalculator().putMat4f().putMat4f().putMat4f().putVec4().putVec4().get();
	private final Matrix4f previousDh = new Matrix4f();
	private boolean havePreviousDh;

	private static final RenderPipeline PIPELINE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath(ReflexiumClient.MOD_ID, "pipeline/dlss_motion"))
			.withVertexShader("core/screenquad")
			.withFragmentShader(Identifier.fromNamespaceAndPath(ReflexiumClient.MOD_ID, "core/dlss_motion"))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withBindGroupLayout(BindGroupLayout.builder()
					.withUniform("ReflexiumMotion", UniformType.UNIFORM_BUFFER)
					.withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
					.withUniform("HandDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
					.withUniform("DhDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
					.build())
			.withColorTargetState(new ColorTargetState(Optional.empty(), FORMAT, ColorTargetState.WRITE_ALL))
			.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
			.build();

	private final GpuBuffer uniforms = RenderSystem.getDevice()
			.createBuffer(() -> "Reflexium DLSS motion UBO", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);

	void render(RenderTarget motion, GpuTextureView worldDepth, GpuTextureView handDepth,
			Matrix4f currentToPrevious, float jitterNdcX, float jitterNdcY,
			DistantHorizonsCompat.DepthFrame dh, Matrix4f currentJittered, Matrix4f depthTransform,
			float camDx, float camDy, float camDz, boolean reset) {
		Matrix4f dhToMain = new Matrix4f(), dhToPrevious = new Matrix4f();
		if (dh != null) {
			Matrix4f inverse = new Matrix4f(dh.viewProjection()).invert();
			Matrix4f unjittered = new Matrix4f().translation(-jitterNdcX, -jitterNdcY, 0).mul(dh.viewProjection());
			dhToMain.set(currentJittered).mul(inverse);
			if (reset || !havePreviousDh) dhToPrevious.set(unjittered).mul(inverse);
			else dhToPrevious.set(previousDh).translate(camDx, camDy, camDz).mul(inverse);
			previousDh.set(unjittered);
		}
		havePreviousDh = dh != null;
		GpuBufferSlice uniformSlice;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer data = Std140Builder.onStack(stack, UBO_SIZE)
					.putMat4f(currentToPrevious)
					.putMat4f(dhToMain)
					.putMat4f(dhToPrevious)
					.putVec4(jitterNdcX, jitterNdcY, motion.width, motion.height)
					.putVec4(depthTransform.m22(), depthTransform.m32(), dh == null ? 0 : 1, 0)
					.get();
			uniformSlice = uploadUniforms(uniforms, data);
		}
		GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
				.createRenderPass(() -> "Reflexium DLSS motion vectors", motion.getColorTextureView(), Optional.empty(),
						motion.getDepthTextureView(), OptionalDouble.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
			pass.setUniform("ReflexiumMotion", uniformSlice);
			pass.setUniform("DepthSampler", worldDepth, nearest);
			pass.setUniform("HandDepthSampler", handDepth, nearest);
			pass.setUniform("DhDepthSampler", dh == null ? worldDepth : dh.depth(), nearest);
			pass.draw(3, 1, 0, 0);
		}
	}

	static GpuBufferSlice uploadUniforms(GpuBuffer fallback, ByteBuffer data) {
		var encoder = RenderSystem.getDevice().createCommandEncoder();
		if (!UpscalingRenderer.get().usesTransientUniforms()) {
			encoder.writeToBuffer(fallback.slice(), data);
			return fallback.slice();
		}
		int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
		try (GpuBufferSlice.MappedView view = encoder.transientMemory().allocateGpuMapped(data.remaining(), alignment, GpuBuffer.USAGE_UNIFORM)) {
			view.data().put(data);
			return view.slice();
		}
	}

	@Override
	public void close() {
		uniforms.close();
	}
}
