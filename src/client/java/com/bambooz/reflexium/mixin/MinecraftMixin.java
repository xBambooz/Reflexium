package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.debug.DebugServer;
import com.bambooz.reflexium.reflex.ReflexController;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reflex frame pacing. The sleep must happen before SDL event polling in run(): input is sampled
 * there, so sleeping any later (e.g. at the top of runTick) would add latency instead of removing it.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "run", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/RenderSystem;pollEvents(Lcom/mojang/blaze3d/platform/SDLEventHandler;)V"))
	private void reflexium$sleepBeforeInput(CallbackInfo ci) {
		ReflexController.get().beginFrame();
	}

	/** Dev debug harness scripted motion; a no-op unless -Dreflexium.debugPort is set. */
	@Inject(method = "tick", at = @At("TAIL"))
	private void reflexium$debugTick(CallbackInfo ci) {
		DebugServer.onClientTick();
	}

	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V"))
	private void reflexium$simulationEnd(boolean advanceGameTime, CallbackInfo ci) {
		ReflexController.get().endSimulation();
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;update(Lnet/minecraft/client/DeltaTracker;)V"))
	private void reflexium$renderSubmitStart(boolean advanceGameTime, CallbackInfo ci) {
		ReflexController.get().beginRenderSubmit();
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V"))
	private void reflexium$presentStart(boolean advanceGameTime, CallbackInfo ci) {
		ReflexController.get().beforePresent();
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
			target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V"))
	private void reflexium$presentEnd(boolean advanceGameTime, CallbackInfo ci) {
		ReflexController.get().afterPresent();
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE_STRING",
			target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=frameLimiter"))
	private void reflexium$observeFrameLimit(boolean advanceGameTime, CallbackInfo ci) {
		Minecraft minecraft = (Minecraft) (Object) this;
		ReflexController.get().updateFrameLimit(minecraft.gameRenderer.gameRenderState().framerateLimit,
				minecraft.options.enableVsync().get(), minecraft.getWindow());
	}

	/** While Reflex paces frames with the same cap, vanilla's extra post-present sleep would only add latency. */
	@WrapWithCondition(method = "renderFrame", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V"))
	private boolean reflexium$skipVanillaLimiter(int fpsLimit) {
		return !ReflexController.get().ownsFrameLimit();
	}
}
