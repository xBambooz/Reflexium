package com.bambooz.reflexium.dlss;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Draws the block selection outline at display resolution with unjittered projection
 * and upscaled depth, preventing DLSS and FSR from blurring thin 1-pixel edges.
 */
public final class SharpBlockOutlineRenderer {
	private static final SharpBlockOutlineRenderer INSTANCE = new SharpBlockOutlineRenderer();

	private BlockOutlineRenderState capturedOutline;
	private CameraRenderState capturedCamera;

	public static SharpBlockOutlineRenderer get() {
		return INSTANCE;
	}

	private SharpBlockOutlineRenderer() {
	}

	public void capture(BlockOutlineRenderState outline, CameraRenderState camera) {
		this.capturedOutline = outline;
		this.capturedCamera = camera;
	}

	public void clear() {
		this.capturedOutline = null;
		this.capturedCamera = null;
	}

	public void render(RenderTarget displayTarget, RenderTarget motionTarget, Matrix4fc unjitteredProjection) {
		if (displayTarget == null || motionTarget == null) {
			clear();
			return;
		}

		upscaleDepth(displayTarget, motionTarget);

		BlockOutlineRenderState outline = this.capturedOutline;
		CameraRenderState camera = this.capturedCamera;
		clear();

		if (outline == null || camera == null || outline.shape() == null || outline.shape().isEmpty()) {
			return;
		}

		BlockPos pos = outline.pos();
		Vec3 cameraPos = camera.pos;
		VoxelShape shape = outline.shape();

		Matrix4f modelView = new Matrix4f(camera.viewRotationMatrix)
				.translate((float) (pos.getX() - cameraPos.x), (float) (pos.getY() - cameraPos.y), (float) (pos.getZ() - cameraPos.z));

		int outlineColor = outline.highContrast() ? -11010079 : ARGB.black(102);
		float lineWidth = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState.appropriateLineWidth;

		drawShape(displayTarget, unjitteredProjection, modelView, shape, outlineColor, lineWidth);

		if (outline.highContrast()) {
			drawShape(displayTarget, unjitteredProjection, modelView, shape, -16777216, 7.0f);
		}
	}

	private static void upscaleDepth(RenderTarget displayTarget, RenderTarget motionTarget) {
		if (displayTarget.getDepthTextureView() == null || motionTarget.getDepthTextureView() == null) {
			return;
		}
		RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Upscale depth")
				.withDepthAttachment(displayTarget.getDepthTextureView())
				.build();
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.INTEGRATE_DEPTH));
			pass.setUniform("InSampler", motionTarget.getDepthTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(3, 1, 0, 0);
		}
	}

	private static void drawShape(RenderTarget target, Matrix4fc projection, Matrix4fc modelView, VoxelShape shape, int color, float lineWidth) {
		ByteBufferBuilder byteBufferBuilder = new ByteBufferBuilder(2048);
		BufferBuilder bufferBuilder = new BufferBuilder(byteBufferBuilder, PrimitiveTopology.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH);
		Vector3f normal = new Vector3f();
		PoseStack.Pose identityPose = new PoseStack().last();

		shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
			normal.set((float) (x2 - x1), (float) (y2 - y1), (float) (z2 - z1)).normalize();
			bufferBuilder.addVertex(identityPose, (float) x1, (float) y1, (float) z1)
					.setColor(color)
					.setNormal(identityPose, normal)
					.setLineWidth(lineWidth);
			bufferBuilder.addVertex(identityPose, (float) x2, (float) y2, (float) z2)
					.setColor(color)
					.setNormal(identityPose, normal)
					.setLineWidth(lineWidth);
		});

		try (MeshData meshData = bufferBuilder.build()) {
			if (meshData == null) {
				byteBufferBuilder.close();
				return;
			}
			int vertexCount = meshData.drawState().vertexCount();
			if (vertexCount <= 0) {
				byteBufferBuilder.close();
				return;
			}
			try (GpuBuffer vertexBuffer = RenderSystem.getDevice()
					.createBuffer(() -> "Sharp block outline vertices", GpuBuffer.USAGE_VERTEX, meshData.vertexBuffer())) {
				GpuBufferSlice projectionSlice = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(projection));
				GpuBufferSlice transformSlice = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(modelView));

				try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
						.createRenderPass(() -> "Sharp block outline", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
					pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.LINES_TRANSLUCENT));
					RenderSystem.bindDefaultUniforms(pass);
					pass.setUniform("Projection", projectionSlice);
					pass.setUniform("DynamicTransforms", transformSlice);
					pass.setVertexBuffer(0, vertexBuffer.slice());
					pass.draw(vertexCount, 1, 0, 0);
				}
			} finally {
				byteBufferBuilder.close();
			}
		}
	}
}
