package com.bambooz.reflexium.motion;

import com.bambooz.reflexium.compat.ShaderPacks;
import com.bambooz.reflexium.debug.DebugSettings;
import com.bambooz.reflexium.upscale.DeferredEffects;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;

import java.util.Optional;
import java.util.OptionalDouble;

/** Replays vanilla's prepared cloud faces to identify their actual depth, including OIT clouds. */
public final class VanillaCloudMotion implements AutoCloseable {
	private TextureTarget target;
	private ProjectionMatrixBuffer projection;
	private double previousTime = Double.NaN;
	private float displacement;
	public GpuTextureView capture(GameRenderer renderer, int width, int height, Matrix4f jitteredProjection, boolean reset) {
		var state = renderer.gameRenderState();
		if (!DebugSettings.cloudMotion || ShaderPacks.isDrawingPack() || state.optionsRenderState.cloudStatus == CloudStatus.OFF
				|| (state.levelRenderState.cloudColor >>> 24) == 0) {
			previousTime = Double.NaN;
			displacement = 0;
			return null;
		}
		double time = state.levelRenderState.gameTime + (double) state.levelRenderState.worldPartialTicks;
		displacement = reset || Double.isNaN(previousTime) || Math.abs(time - previousTime) > 20 ? 0 : (float) ((time - previousTime) * 0.030000001);
		previousTime = time;
		if (target == null || target.width != width || target.height != height) {
			if (target != null) target.destroyBuffers();
			target = new TextureTarget("Reflexium cloud depth", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
		}
		if (projection == null) projection = new ProjectionMatrixBuffer("Reflexium cloud motion");
		var savedProjection = RenderSystem.getProjectionMatrixBuffer();
		var savedType = RenderSystem.getProjectionType();
		var savedFog = RenderSystem.getShaderFog();
		var stack = RenderSystem.getModelViewStack();
		stack.pushMatrix();
		try {
			stack.identity().mul(state.levelRenderState.cameraRenderState.viewRotationMatrix);
			RenderSystem.setProjectionMatrix(projection.getBuffer(jitteredProjection), ProjectionType.PERSPECTIVE);
			RenderSystem.setShaderFog(DeferredEffects.worldFog());
			try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
					() -> "Reflexium cloud depth", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.of(0))) {
				RenderSystem.bindDefaultUniforms(pass);
				Minecraft.getInstance().levelRenderer.cloudRenderer().render(state.optionsRenderState.cloudStatus, pass);
			}
		} finally {
			stack.popMatrix();
			RenderSystem.setProjectionMatrix(savedProjection, savedType);
			RenderSystem.setShaderFog(savedFog);
		}
		return target.getDepthTextureView();
	}
	public float displacement() { return displacement; }
	public void close() {
		if (target != null) target.destroyBuffers();
		if (projection != null) projection.close();
		target = null; projection = null; previousTime = Double.NaN;
	}
}
