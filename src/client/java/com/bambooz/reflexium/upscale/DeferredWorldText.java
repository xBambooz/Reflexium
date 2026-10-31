package com.bambooz.reflexium.upscale;

import com.bambooz.reflexium.compat.IrisCompat;
import com.bambooz.reflexium.compat.ShaderPacks;
import com.bambooz.reflexium.compat.VitrailCompat;
import com.bambooz.reflexium.framegen.FrameGenerationRenderer;
import com.bambooz.reflexium.debug.DebugSettings;
import com.bambooz.reflexium.mixin.GameRendererAccessor;
import com.bambooz.reflexium.mixin.LevelRendererAccessor;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.OptionalDouble;

/** Scoped world glyphs resolved once against scene depth, then shared by the real frame and FG UI. */
public final class DeferredWorldText {
	private record Phase(int order, int index) { }
	private record Text(Phase phase, TextFeatureRenderer.Submit node, boolean sign) { }
	private static final IdentityHashMap<FeatureRenderPhase<?>, Phase> phases = new IdentityHashMap<>();
	private static final IdentityHashMap<SubmitNodeCollection, Boolean> collections = new IdentityHashMap<>();
	private static final ArrayList<Text> texts = new ArrayList<>();
	private static SubmitNodeStorage storage = new SubmitNodeStorage();
	private static final Matrix4f projection = new Matrix4f();
	private static final Vector4f CLEAR = new Vector4f(0, 0, 0, 0);
	private static ProjectionMatrixBuffer projectionBuffer;
	private static TextureTarget layer, signLayer;
	private static SubmitNodeStorage mainStorage;
	private static boolean signScope;
	private static int scope;
	private static boolean enabled, prepared, depthChecked, depthAvailable;
	public static int captured, fallback;
	public static String limitation = "";
	private static final RenderPipeline COMPOSITE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("reflexium", "pipeline/world_text_composite"))
			.withVertexShader("core/screenquad")
			.withFragmentShader(Identifier.fromNamespaceAndPath("reflexium", "core/world_text_composite"))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withCull(false)
			.withBindGroupLayout(BindGroupLayout.builder().withUniform("TextLayer", UniformType.COMBINED_IMAGE_SAMPLER).build())
			.withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
			.build();

	public static void clear() {
		finish();
		prepared = false;
		depthChecked = false; depthAvailable = false;
	}

	public static void finish() {
		if (!texts.isEmpty()) storage = new SubmitNodeStorage();
		texts.clear(); phases.clear(); collections.clear();
		scope = 0; signScope = false; enabled = false; mainStorage = null;
	}

	public static void begin(GameRenderer renderer) {
		captured = 0; fallback = 0; limitation = "";
		var upscaling = UpscalingRenderer.get();
		if (!DebugSettings.deferWorldText || !upscaling.isUpscalingRequestedThisFrame()) return;
		var fg = FrameGenerationRenderer.get();
		if (fg.streamlineFrameGenerationActive() && !fg.capturesUiAlpha()) {
			limitation = "UI recomposition disabled; retaining vanilla world text"; return;
		}
		if (renderer.useImprovedTransparency()) { limitation = "OIT retains vanilla world text"; return; }
		if (!((GameRendererAccessor) renderer).reflexium$appliedPostEffects().isEmpty()) {
			limitation = "post effects retain vanilla world text"; return;
		}
		if (!ShaderPacks.canUpscale() || VitrailCompat.isDrawingPack()) {
			limitation = "unsupported pack retains vanilla world text"; return;
		}
		int width = upscaling.outputWidth(), height = upscaling.outputHeight();
		if (layer == null || layer.width != width || layer.height != height) {
			upscaling.waitForGpu();
			close();
			layer = new TextureTarget("Reflexium display name tags", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
			signLayer = new TextureTarget("Reflexium display sign text", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
		}
		mainStorage = ((LevelRendererAccessor) Minecraft.getInstance().levelRenderer).reflexium$submitNodeStorage();
		enabled = true;
	}

	public static void projection(Matrix4f matrix) { projection.set(matrix); }
	public static void enter(boolean sign) { if (scope++ == 0) signScope = sign; }
	public static void exit() { scope--; }
	public static boolean pending() { return !texts.isEmpty(); }
	public static boolean prepared() { return prepared; }
	public static RenderTarget layer() { return prepared ? layer : null; }

	public static void register(SubmitNodeStorage source, SubmitNodeCollection collection, int order) {
		if (!enabled || source != mainStorage || collections.put(collection, Boolean.TRUE) != null) return;
		var all = collection.allPhases();
		for (int i = 0; i < all.size(); i++) phases.put(all.get(i), new Phase(order, i));
		phases.put(collection.seeThrough, new Phase(0, -1));
	}

	public static boolean collect(FeatureRenderPhase<?> phase, SubmitNode node) {
		if (!enabled || scope == 0 || !(node instanceof TextFeatureRenderer.Submit text)) return false;
		Phase key = phases.get(phase);
		if (key == null) return false; // Shadow, GUI and hand storages keep their own text.
		if (DeferredEffects.worldFog() == null) { fallback++; return false; }
		if (IrisCompat.isDrawingPack()) {
			if (!depthChecked) {
				var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
				depthAvailable = IrisCompat.opaqueDepth(main.width, main.height) != null;
				depthChecked = true;
			}
			if (!depthAvailable) {
				limitation = "pack depth unavailable; retaining vanilla world text"; fallback++; return false;
			}
		}
		texts.add(new Text(key, text, signScope));
		captured++;
		return true;
	}

	@SuppressWarnings("unchecked")
	private static FeatureRenderPhase<SubmitNode> destination(Phase key) {
		return (FeatureRenderPhase<SubmitNode>) (FeatureRenderPhase<?>) (key.index < 0 ? storage.seeThrough()
				: storage.order(key.order).allPhases().get(key.index));
	}

	public static void prepare(GameRenderer renderer) {
		if (!pending()) return;
		// A failed evaluation skips the depth resolve; drop this frame's text like deferred particles, never crash the frame.
		if (!UpscalingRenderer.get().displayDepthResolved()) {
			limitation = "scene depth not resolved; world text dropped for one frame";
			texts.clear();
			return;
		}
		drawLayer(renderer, signLayer, true);
		drawLayer(renderer, layer, false);
		texts.clear();
	}

	private static void drawLayer(GameRenderer renderer, TextureTarget layer, boolean sign) {
		storage = new SubmitNodeStorage();
		var encoder = RenderSystem.getDevice().createCommandEncoder();
		encoder.clearColorAndDepthTextures(layer.getColorTexture(), CLEAR, layer.getDepthTexture(), 0);
		var main = renderer.mainRenderTarget();
		encoder.copyTextureToTexture(main.getDepthTexture(), layer.getDepthTexture(), 0, 0, 0, 0, 0, layer.width, layer.height);
		var savedProjection = RenderSystem.getProjectionMatrixBuffer();
		var savedType = RenderSystem.getProjectionType();
		var savedFog = RenderSystem.getShaderFog();
		var stack = RenderSystem.getModelViewStack();
		stack.pushMatrix();
		try {
			if (projectionBuffer == null) projectionBuffer = new ProjectionMatrixBuffer("Reflexium display world text");
			RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.PERSPECTIVE);
			RenderSystem.setShaderFog(DeferredEffects.worldFog());
			stack.identity().mul(renderer.gameRenderState().levelRenderState.cameraRenderState.viewRotationMatrix);
			for (Text text : texts) if (text.sign == sign) destination(text.phase).submit(text.node);
			try (var frame = renderer.featureRenderDispatcher().prepareFrame(storage);
				var pass = encoder.createRenderPass(() -> "Reflexium depth-tested world text", layer.getColorTextureView(), Optional.empty(), layer.getDepthTextureView(), OptionalDouble.empty())) {
				RenderSystem.bindDefaultUniforms(pass);
				FeatureRenderDispatcher.renderAllFeatures(pass, frame);
				prepared = true;
			}
		} catch (RuntimeException | Error failure) {
			storage = new SubmitNodeStorage();
			throw failure;
		} finally {
			stack.popMatrix();
			RenderSystem.setProjectionMatrix(savedProjection, savedType);
			RenderSystem.setShaderFog(savedFog);
		}
	}

	/** The layer is already occluded and premultiplied. Never depth-test it against the cleared UI depth. */
	public static void composite(RenderTarget target) { composite(target, layer); }
	public static void compositeSigns(RenderTarget target) { composite(target, signLayer); }

	private static void composite(RenderTarget target, RenderTarget layer) {
		if (!prepared) return;
		try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> "Reflexium composite world text", target.getColorTextureView(), Optional.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(COMPOSITE));
			pass.setUniform("TextLayer", layer.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(3, 1, 0, 0);
		}
	}

	static void close() {
		clear();
		if (layer != null) layer.destroyBuffers();
		if (signLayer != null) signLayer.destroyBuffers();
		signLayer = null;
		layer = null;
		if (projectionBuffer != null) projectionBuffer.close();
		projectionBuffer = null;
	}
}
