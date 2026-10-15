package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.dlss.UpscalingRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** DLSS frame hooks; see {@link UpscalingRenderer} for the sequence. Every hook is a no-op while DLSS is off. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Unique private boolean reflexium$originalConsistentDepth;
	@ModifyVariable(method = "render3dHud", at = @At("HEAD"), argsOnly = true)
	private boolean reflexium$preserveWorldDepth(boolean consistentDepthRequired) {
		reflexium$originalConsistentDepth = consistentDepthRequired;
		return consistentDepthRequired || UpscalingRenderer.get().isUpscalingThisFrame() && UpscalingRenderer.get().usesDirectDepth();
	}

	@WrapOperation(method = "render3dHud", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;integrate3DHudDepth()V"))
	private void reflexium$integrateOnlyForPostEffects(GameRenderer renderer, Operation<Void> original) {
		// The motion pass already combines world and hand depth for SR and FG.
		if (reflexium$originalConsistentDepth) original.call(renderer);
	}
	@WrapMethod(method = "render")
	private void reflexium$frame(Operation<Void> original) {
		GameRenderer self = (GameRenderer) (Object) this;
		try {
			UpscalingRenderer.get().beginFrame(self);
			original.call();
		} finally {
			UpscalingRenderer.get().endFrame(self);
		}
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GlobalSettingsUniform;update(IIDJFILnet/minecraft/world/phys/Vec3;Z)V"))
	private void reflexium$captureGlobals(GlobalSettingsUniform uniform, int width, int height, double glintAlpha, long gameTime,
			float worldPartialTicks, int menuBlurRadius, Vec3 cameraPos, boolean useRgss, Operation<Void> original) {
		original.call(uniform, width, height, glintAlpha, gameTime, worldPartialTicks, menuBlurRadius, cameraPos, useRgss);
		UpscalingRenderer.get().captureGlobals(uniform, glintAlpha, gameTime, worldPartialTicks, menuBlurRadius, cameraPos, useRgss);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
	private void reflexium$beforeLevel(CallbackInfo ci) {
		UpscalingRenderer.get().beforeLevel((GameRenderer) (Object) this);
	}

	/** Super resolution belongs before post effects: reconstruct the scene, then let them run at display size. */
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V"))
	private void reflexium$upscale(CallbackInfo ci) {
		UpscalingRenderer.get().reconstructScene((GameRenderer) (Object) this);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V",
			shift = At.Shift.AFTER))
	private void reflexium$completeScene(CallbackInfo ci) {
		UpscalingRenderer.get().completeScene((GameRenderer) (Object) this);
	}

	@ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private Matrix4f reflexium$jitterLevel(Matrix4f projection) {
		GameRenderer self = (GameRenderer) (Object) this;
		return UpscalingRenderer.get().jitterLevelProjection(projection, self.gameRenderState().levelRenderState.cameraRenderState);
	}

	@Inject(method = "renderLevel", at = @At("RETURN"))
	private void reflexium$shaderPackMotion(CallbackInfo ci) {
		UpscalingRenderer.get().afterLevel((GameRenderer) (Object) this);
	}

	@WrapOperation(method = "render3dHud", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lnet/minecraft/client/renderer/Projection;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private GpuBufferSlice reflexium$jitterHud(ProjectionMatrixBuffer buffer, Projection projection, Operation<GpuBufferSlice> original) {
		UpscalingRenderer dlss = UpscalingRenderer.get();
		if (!dlss.isUpscalingThisFrame()) {
			return original.call(buffer, projection);
		}
		Matrix4f unjittered = projection.getMatrix(new Matrix4f());
		Matrix4f jittered = dlss.jitterHudProjection(unjittered);
		dlss.noteHandProjection(unjittered, jittered);
		return buffer.getBuffer(jittered);
	}

	@Inject(method = "render3dHud", at = @At("HEAD"))
	private void reflexium$captureWorldDepth(CameraRenderState cameraState, PlayerRenderState playerState, OptionsRenderState optionsState,
			boolean consistentDepthRequired, CallbackInfo ci) {
		UpscalingRenderer.get().captureWorldDepth((GameRenderer) (Object) this);
	}

	@Inject(method = "render3dHud", at = @At("RETURN"))
	private void reflexium$motionVectors(CameraRenderState cameraState, PlayerRenderState playerState, OptionsRenderState optionsState,
			boolean consistentDepthRequired, CallbackInfo ci) {
		UpscalingRenderer.get().renderMotionVectors((GameRenderer) (Object) this, consistentDepthRequired);
	}

	@Inject(method = "resize", at = @At("TAIL"))
	private void reflexium$afterResize(int width, int height, CallbackInfo ci) {
		UpscalingRenderer.get().afterVanillaResize((GameRenderer) (Object) this);
	}
}
