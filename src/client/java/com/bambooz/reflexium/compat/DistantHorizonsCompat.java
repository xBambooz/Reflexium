package com.bambooz.reflexium.compat;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.upscale.UpscalingRenderer;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.fabricmc.loader.api.FabricLoader;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Optional, exact-version hooks for DH's separate LOD depth and temporal sampling. Render thread only. */
public final class DistantHorizonsCompat {
	/** Diagnostic startup switch. Disables all DH mixins, capture and API overrides. */
	public static final boolean DISABLED = Boolean.getBoolean("reflexium.disableDhHooks");
	public static final String SUPPORTED = "3.3.5-dev";
	private static final String OWNER = "Reflexium";
	private static boolean versionLogged, broken;
	private static DepthFrame frame;
	private static boolean rejectedFrame, waiting;
	static final class FrameNotReady extends IllegalStateException {
		FrameNotReady(String message) { super(message); }
	}
	public static boolean waitingForFrame() { return waiting; }
	public static boolean rejectedFrame() { return rejectedFrame; }
	static void retryFrame(FrameNotReady error) {
		frame = null;
		rejectedFrame = true;
		if (!waiting) ReflexiumClient.LOGGER.warn("Waiting for Distant Horizons: {}", error.getMessage());
		waiting = true;
	}
	private static Object aaEntry, previousAa;
	private static String previousAaOwner;
	private static boolean overridingAa;
	private static Method setAa, getAa, getAaOwner, getEffectiveAa;
	private static Field renderProxy, resultSuccess, resultPayload, nativeViewProjection, nativeFar;
	private static Method depthWrapper, wrappedObjects, toJoml;
	private static Method irisDepth, irisProjection, irisView, irisFar, irisShadows;

	private DistantHorizonsCompat() {
	}

	public record DepthFrame(GpuTextureView depth, Matrix4f viewProjection, float farPlane) {
	}

	public static boolean isSupportedVersion() {
		if (DISABLED) return false;
		var mod = FabricLoader.getInstance().getModContainer("distanthorizons");
		if (mod.isEmpty()) return false;
		String version = mod.get().getMetadata().getVersion().getFriendlyString();
		boolean supported = version.equals(SUPPORTED);
		if (!supported && !versionLogged) {
			versionLogged = true;
			ReflexiumClient.LOGGER.warn("Distant Horizons {} is not supported (expected {}); upscaling is paused", version, SUPPORTED);
		}
		return supported;
	}

	public static boolean canUpscale() {
		return DISABLED || !FabricLoader.getInstance().isModLoaded("distanthorizons") || isSupportedVersion() && !broken;
	}

	public static void beginFrame() {
		frame = null;
		rejectedFrame = false;
	}

	/** The API override leaves the saved setting untouched and restores any earlier override we replaced. */
	public static void configureUpscaling(boolean enabled) {
		if (!isSupportedVersion()) return;
		boolean wanted = enabled && !broken && !IrisCompat.isDrawingPack();
		if (!wanted && !overridingAa) return;
		try {
			if (aaEntry == null) {
				aaEntry = Class.forName("com.seibel.distanthorizons.core.config.Config$Client$Advanced$Graphics")
						.getField("enableAntiAliasing").get(null);
				Class<?> type = aaEntry.getClass();
				setAa = type.getMethod("setApiValue", Object.class, String.class);
				getAa = type.getMethod("getApiValue");
				getAaOwner = type.getMethod("getApiUser");
				getEffectiveAa = type.getMethod("get");
			}
			if (wanted) {
				if (!overridingAa) {
					previousAa = getAa.invoke(aaEntry);
					previousAaOwner = (String) getAaOwner.invoke(aaEntry);
					overridingAa = true;
					setAa.invoke(aaEntry, false, OWNER);
				}
				if (!Boolean.FALSE.equals(getEffectiveAa.invoke(aaEntry))) {
					throw new IllegalStateException("DH anti-aliasing override was not accepted");
				}
			} else {
				restoreAa();
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			disable(e);
		}
	}

	private static void restoreAa() throws ReflectiveOperationException {
		if (!overridingAa) return;
		if (OWNER.equals(getAaOwner.invoke(aaEntry)) && Boolean.FALSE.equals(getAa.invoke(aaEntry))) {
			setAa.invoke(aaEntry, previousAa, previousAaOwner == null ? "" : previousAaOwner);
		}
		overridingAa = false;
	}

	private static void disable(Throwable error) {
		frame = null;
		if (!broken) ReflexiumClient.LOGGER.warn("Distant Horizons integration failed; upscaling is paused", error);
		broken = true;
		try { restoreAa(); }
		catch (ReflectiveOperationException | RuntimeException e) { error.addSuppressed(e); }
	}

	/** Called only after native terrain rendering, so a skipped DH frame never reuses old depth. */
	public static void captureNative(Object params) {
		if (DISABLED || broken || !UpscalingRenderer.get().isUpscalingThisFrame() || IrisCompat.isDrawingPack()) return;
		try {
			if (renderProxy == null) {
				Class<?> param = Class.forName("com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam");
				nativeViewProjection = param.getField("dhMvmProjMatrix");
				nativeFar = param.getField("farClipPlane");
				toJoml = Class.forName("com.seibel.distanthorizons.core.util.math.DhMat4f")
						.getMethod("createJomlMatrix", nativeViewProjection.getType());
				depthWrapper = Class.forName("com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy")
						.getMethod("getDhDepthTextureBlazeWrapper");
				Class<?> result = Class.forName("com.seibel.distanthorizons.api.objects.DhApiResult");
				resultSuccess = result.getField("success");
				resultPayload = result.getField("payload");
				wrappedObjects = Class.forName("com.seibel.distanthorizons.api.interfaces.render.IDhApiBlazeTextureWrapper")
						.getMethod("getWrappedMcObject");
				renderProxy = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed").getField("renderProxy");
			}
			Object proxy = renderProxy.get(null);
			if (proxy == null) throw new FrameNotReady("DH rendered without a render proxy");
			Object result = depthWrapper.invoke(proxy);
			if (!resultSuccess.getBoolean(result)) throw new FrameNotReady("DH rendered without accessible depth");
			Object wrapper = resultPayload.get(result);
			if (wrapper == null) throw new FrameNotReady("DH depth wrapper is missing");
			Object[] textures = (Object[]) wrappedObjects.invoke(wrapper);
			Matrix4f viewProjection = (Matrix4f) toJoml.invoke(null, nativeViewProjection.get(params));
			frame = checkedFrame((GpuTextureView) textures[1], viewProjection, nativeFar.getFloat(params));
		} catch (FrameNotReady e) {
			retryFrame(e);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			disable(e);
		}
	}

	/** Iris stores pack GL clip coordinates but DepthTransformer writes reverse-Z Vulkan depth. */
	public static void captureIris(Object compat) {
		if (DISABLED || broken || !UpscalingRenderer.get().isUpscalingThisFrame()) return;
		try {
			if (irisDepth == null) {
				Class<?> type = Class.forName("net.irisshaders.iris.compat.dh.DHCompatInternal");
				irisProjection = type.getMethod("getProjection");
				irisView = type.getMethod("getModelView");
				irisFar = type.getMethod("getFarPlane");
				irisShadows = Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState")
						.getMethod("areShadowsCurrentlyBeingRendered");
				irisDepth = type.getMethod("getDepthTexture", boolean.class);
			}
			if ((boolean) irisShadows.invoke(null)) return;
			GpuTextureView depth = (GpuTextureView) irisDepth.invoke(compat, false);
			if (depth == null) throw new FrameNotReady("Iris DH rendered without accessible depth");
			Matrix4fc sourceProjection = (Matrix4fc) irisProjection.invoke(compat);
			Matrix4fc view = (Matrix4fc) irisView.invoke(compat);
			if ((!sourceProjection.isFinite() || !view.isFinite()) && !waiting)
				ReflexiumClient.LOGGER.warn("DH startup matrices: projection={}, view={}", sourceProjection, view);
			frame = checkedFrame(depth, irisDeviceProjection(sourceProjection).mul(view), (float) irisFar.invoke(null));
		} catch (FrameNotReady e) {
			retryFrame(e);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			disable(e);
		}
	}

	public static Matrix4f irisDeviceProjection(Matrix4fc projection) {
		return new Matrix4f().m22(-0.5f).m32(0.5f).mul(projection);
	}

	static DepthFrame checkedFrame(GpuTextureView depth, Matrix4f viewProjection, float far) {
		if (depth.isClosed() || depth.texture().getFormat() != GpuFormat.D32_FLOAT
				|| !viewProjection.isFinite() || Math.abs(viewProjection.determinant()) < 1.0e-12f
				|| !Float.isFinite(far) || far <= 0) throw new FrameNotReady("Invalid DH depth or projection: closed=" + depth.isClosed()
					+ ", format=" + depth.texture().getFormat() + ", size=" + depth.getWidth(0) + "x" + depth.getHeight(0)
					+ ", far=" + far + ", determinant=" + viewProjection.determinant() + ", matrix=" + viewProjection);
		if (waiting) ReflexiumClient.LOGGER.info("Distant Horizons inputs ready; temporal reconstruction can resume");
		waiting = false;
		return new DepthFrame(depth, viewProjection, far);
	}

	public static DepthFrame depthFrame(int width, int height) {
		if (frame == null || broken) return null;
		if (frame.depth().isClosed() || frame.depth().getWidth(0) != width || frame.depth().getHeight(0) != height) {
			retryFrame(new FrameNotReady("DH depth does not match this frame's render size"));
			return null;
		}
		return frame;
	}

	/** Affine clip-Z remap: preserve the main near plane and extend its finite reverse-Z far plane. */
	public static Matrix4f depthTransform(float near, float far, float outputFar) {
		if (outputFar == far) return new Matrix4f();
		float scale = (float) ((double) outputFar * (far - (double) near) / (far * (outputFar - (double) near)));
		float offset = (float) ((double) near * (outputFar - (double) far) / (far * (outputFar - (double) near)));
		return new Matrix4f().m22(scale).m32(offset);
	}
}
