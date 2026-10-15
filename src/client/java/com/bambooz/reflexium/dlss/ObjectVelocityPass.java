package com.bambooz.reflexium.dlss;

import com.bambooz.reflexium.ReflexiumClient;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Second motion vector pass: re-draws the moving entity and block entity quads recorded by
 * {@link ObjectMotion} into the motion target, after {@link MotionVectorPass} wrote camera-only vectors.
 * Each vertex carries its position now and its position last frame; the fragment shader writes the
 * screen-space difference only where the quad is the visible surface (see entity_velocity.fsh).
 * Without a depth attachment, so it cannot disturb the depth DLSS and FSR read from the motion target.
 */
final class ObjectVelocityPass implements AutoCloseable {
	private static final int VERTEX_BYTES = 28;
	/** Quads per index buffer; 16000 quads keeps every index inside an unsigned 16 bit range. */
	private static final int QUADS_PER_DRAW = 16_000;
	private static final int VERTICES_PER_DRAW = QUADS_PER_DRAW * 4;

	private static final VertexFormat VERTEX_FORMAT = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGB32_FLOAT)
			.addAttribute("PreviousPosition", GpuFormat.RGB32_FLOAT)
			.addAttribute("CullFace", GpuFormat.R32_FLOAT)
			.build();

	private static final int UBO_SIZE = new Std140SizeCalculator().putMat4f().putMat4f().putMat4f().putVec4().get();

	private static final RenderPipeline PIPELINE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath(ReflexiumClient.MOD_ID, "pipeline/entity_velocity"))
			.withVertexShader(Identifier.fromNamespaceAndPath(ReflexiumClient.MOD_ID, "core/entity_velocity"))
			.withFragmentShader(Identifier.fromNamespaceAndPath(ReflexiumClient.MOD_ID, "core/entity_velocity"))
			.withVertexBinding(0, VERTEX_FORMAT)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withBindGroupLayout(BindGroupLayout.builder()
					.withUniform("ReflexiumObjectMotion", UniformType.UNIFORM_BUFFER)
					.withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
					.withUniform("HandDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
					.build())
			.withColorTargetState(new ColorTargetState(Optional.empty(), MotionVectorPass.FORMAT, ColorTargetState.WRITE_ALL))
			.withCull(false)
			.build();

	private final GpuBuffer uniforms = RenderSystem.getDevice()
			.createBuffer(() -> "Reflexium object motion UBO", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);
	private final GpuBuffer indices;

	ObjectVelocityPass() {
		ByteBuffer data = MemoryUtil.memAlloc(QUADS_PER_DRAW * 6 * Short.BYTES);
		try {
			for (int quad = 0; quad < QUADS_PER_DRAW; quad++) {
				int base = quad * 4;
				data.putShort((short) base).putShort((short) (base + 1)).putShort((short) (base + 2));
				data.putShort((short) (base + 2)).putShort((short) (base + 3)).putShort((short) base);
			}
			data.flip();
			indices = RenderSystem.getDevice().createBuffer(() -> "Reflexium object motion indices", GpuBuffer.USAGE_INDEX, data);
		} finally {
			MemoryUtil.memFree(data);
		}
	}

	/**
	 * @param currentJittered the jittered view-projection the level pass rasterized with (depth must match)
	 * @param current         the same without jitter, for the current screen position
	 * @param previous        last frame's unjittered view-projection, applied to last frame's camera-relative positions
	 * @return vertices drawn
	 */
	int render(ObjectMotion motion, RenderTarget target, GpuTextureView worldDepth, GpuTextureView handDepth,
			Matrix4f currentJittered, Matrix4f current, Matrix4f previous, float camDx, float camDy, float camDz, boolean reset) {
		int vertices = motion.prepareDraws(camDx, camDy, camDz, reset);
		return vertices == 0 ? 0 : draw(motion, target, worldDepth, handDepth, currentJittered, current, previous, vertices, false);
	}

	/**
	 * Hand and held-item quads. Vertices are already in eye space, so the matrices are the hand projection
	 * (jittered for rasterization, unjittered now and previous). Visibility is tested against the hand-only depth.
	 */
	int renderHand(ObjectMotion motion, RenderTarget target, GpuTextureView worldDepth, GpuTextureView handDepth,
			Matrix4f currentJittered, Matrix4f current, Matrix4f previous, boolean reset) {
		int vertices = motion.prepareHandDraws(reset, !current.equals(previous, 1.0e-6f));
		return vertices == 0 ? 0 : draw(motion, target, worldDepth, handDepth, currentJittered, current, previous, vertices, true);
	}

	private int draw(ObjectMotion motion, RenderTarget target, GpuTextureView worldDepth, GpuTextureView handDepth,
			Matrix4f currentJittered, Matrix4f current, Matrix4f previous, int vertices, boolean hand) {
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		GpuBufferSlice slice;
		GpuBufferSlice uniformSlice;
		try (GpuBufferSlice.MappedView view = encoder.transientMemory().allocateGpuMapped((long) vertices * VERTEX_BYTES, 16L, GpuBuffer.USAGE_VERTEX)) {
			if (hand) motion.writeHandVertices(view.data().asFloatBuffer());
			else motion.writeVertices(view.data().asFloatBuffer());
			slice = view.slice();
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer data = Std140Builder.onStack(stack, UBO_SIZE)
					.putMat4f(currentJittered)
					.putMat4f(current)
					.putMat4f(previous)
					.putVec4(target.width, target.height, hand ? 1.0f : 0.0f, 0.0f)
					.get();
			uniformSlice = MotionVectorPass.uploadUniforms(uniforms, data);
		}
		GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		try (RenderPass pass = encoder.createRenderPass(() -> "Reflexium object motion vectors", target.getColorTextureView(), Optional.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
			pass.setUniform("ReflexiumObjectMotion", uniformSlice);
			pass.setUniform("DepthSampler", worldDepth, nearest);
			pass.setUniform("HandDepthSampler", handDepth, nearest);
			pass.setVertexBuffer(0, slice);
			pass.setIndexBuffer(indices, IndexType.SHORT);
			for (int first = 0; first < vertices; first += VERTICES_PER_DRAW) {
				int count = Math.min(VERTICES_PER_DRAW, vertices - first);
				pass.drawIndexed(count / 4 * 6, 1, 0, first, 0);
			}
		}
		return vertices;
	}

	@Override
	public void close() {
		uniforms.close();
		indices.close();
	}
}
