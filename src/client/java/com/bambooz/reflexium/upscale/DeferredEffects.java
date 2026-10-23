package com.bambooz.reflexium.upscale;

import com.bambooz.reflexium.compat.ShaderPacks;
import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.debug.DebugSettings;
import com.bambooz.reflexium.framegen.FrameGenerationRenderer;
import com.bambooz.reflexium.mixin.GameRendererAccessor;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import org.joml.Matrix4f;

import java.util.Optional;
import java.util.OptionalDouble;

/** Display-resolution vanilla effects after SR. Packs and OIT retain their own composition order. */
public final class DeferredEffects {
	private static ParticlesRenderState particles;
	private static CameraRenderState camera;
	private static WeatherEffectRenderer weather;
	private static WeatherRenderState weatherState;
	public static boolean applied;
	public static int generatedCopies;
	private static GpuBufferSlice fog;
	private static final Matrix4f projection = new Matrix4f();
	private static ProjectionMatrixBuffer projectionBuffer;
	private static final SubmitNodeStorage storage = new SubmitNodeStorage();
	public static boolean enabled() {
		// A post chain must transform particles with the world. Until it can replay per output, retain vanilla order.
		return DebugSettings.deferParticles && ReflexiumConfig.get().postUpscaleParticles() && available()
				&& (!FrameGenerationRenderer.get().wantsMotion() || ((GameRendererAccessor)
				Minecraft.getInstance().gameRenderer).reflexium$appliedPostEffects().isEmpty());
	}
	// OIT requires a display-size transparency composition contract; preserve vanilla ordering there.
	public static boolean available() { return UpscalingRenderer.get().isUpscalingThisFrame() && !ShaderPacks.isDrawingPack()
			&& !Minecraft.getInstance().gameRenderer.useImprovedTransparency(); }
	public static void deferWeather(WeatherEffectRenderer renderer, WeatherRenderState state) {
		weather = renderer;
		weatherState = state;
		camera = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
	}
	public static void projection(Matrix4f matrix) { projection.set(matrix); }
	public static void fog(GpuBufferSlice value) { fog = value; }
	public static GpuBufferSlice worldFog() { return fog; }
	public static void defer(ParticlesRenderState state, CameraRenderState view) { particles = state; camera = view; }
	public static boolean pending() { return particles != null || weather != null; }
	public static void clear() { particles = null; weather = null; camera = null; fog = null; }
	static void close() { clear(); if (projectionBuffer != null) projectionBuffer.close(); projectionBuffer = null; }
	public static void draw(FeatureRenderDispatcher dispatcher) {
		if (!pending()) return;
		if (!UpscalingRenderer.get().displayDepthResolved()) { clear(); return; }
		var savedProjection = RenderSystem.getProjectionMatrixBuffer();
		var savedType = RenderSystem.getProjectionType();
		var savedFog = RenderSystem.getShaderFog();
		try {
			if (projectionBuffer == null) projectionBuffer = new ProjectionMatrixBuffer("Reflexium display particles");
			RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.PERSPECTIVE);
			RenderSystem.setShaderFog(fog);
			var stack = RenderSystem.getModelViewStack();
			stack.pushMatrix();
			try {
				stack.identity().mul(camera.viewRotationMatrix);
				if (particles != null) particles.submit(storage, camera);
				try (var frame = dispatcher.prepareFrame(storage)) {
					var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
					var fg = FrameGenerationRenderer.get();
					var encoder = RenderSystem.getDevice().createCommandEncoder();
					for (int i = 0; i < fg.generatedCount(); i++) {
						encoder.copyTextureToTexture(target.getDepthTexture(), fg.generatedDepthTexture(i),
								0, 0, 0, 0, 0, target.width, target.height);
					}
					for (int i = -1; i < fg.generatedCount(); i++) {
						var color = i < 0 ? target.getColorTextureView() : fg.generatedView(i);
						var depth = i < 0 ? target.getDepthTextureView() : fg.generatedDepthView(i);
						try (var pass = encoder.createRenderPass(
								() -> "Reflexium display particles", color, Optional.empty(),
								depth, OptionalDouble.empty())) {
							RenderSystem.bindDefaultUniforms(pass);
							FeatureRenderDispatcher.renderAllFeatures(pass, frame);
							if (weather != null) weather.render(weatherState, pass);
							applied = true;
							if (i >= 0) generatedCopies++;
						}
					}
				}
			} finally { stack.popMatrix(); }
		} finally {
			RenderSystem.setProjectionMatrix(savedProjection, savedType);
			RenderSystem.setShaderFog(savedFog);
			clear();
		}
	}
}
