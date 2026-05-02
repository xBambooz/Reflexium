package com.nvidiareflex.client.mixin;

import com.nvidiareflex.client.ReflexManager;
import com.nvidiareflex.client.VulkanStateTracker;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Instruments Minecraft's render loop with Nvidia Reflex latency markers
 * so FrameView and the driver can measure and reduce PC latency.
 *
 * Marker flow per frame:
 *   runTick HEAD          → vkLatencySleepNV (driver-guided sleep)
 *   runTick before tick() → SIMULATION_START
 *   runTick after ticks   → SIMULATION_END
 *   renderFrame before gameRenderer.render() → RENDERSUBMIT_START
 *   renderFrame after  gameRenderer.render() → RENDERSUBMIT_END
 *   renderFrame before windowSurface.present() → PRESENT_START
 *   renderFrame after  windowSurface.present() → PRESENT_END
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

	@Shadow private GameRenderer gameRenderer;

	// -------------------------------------------------------------------------
	// runTick — simulation phase
	// -------------------------------------------------------------------------

	/**
	 * At the very start of runTick: issue the Reflex sleep.
	 * The driver will signal our semaphore when it's optimal to start CPU work.
	 */
	@Inject(method = "runTick", at = @At("HEAD"))
	private void nvidia_reflex$onRunTickHead(boolean advanceGameTime, CallbackInfo ci) {
		if (!VulkanStateTracker.isVulkanActive()) return;
		ReflexManager.getInstance().onFrameStart();
	}

	/**
	 * Just before the tick() loop executes: mark simulation start.
	 * Targets the profiler.push("tick") call that precedes the tick loop.
	 */
	@Inject(
		method = "runTick",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/Minecraft;tick()V",
			ordinal = 0
		)
	)
	private void nvidia_reflex$onSimulationStart(boolean advanceGameTime, CallbackInfo ci) {
		if (!VulkanStateTracker.isVulkanActive()) return;
		ReflexManager.getInstance().onSimulationStart();
	}

	/**
	 * After the tick loop and before renderFrame: mark simulation end.
	 * Targets the renderFrame call (simulation is complete at this point).
	 */
	@Inject(
		method = "runTick",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V"
		)
	)
	private void nvidia_reflex$onSimulationEnd(boolean advanceGameTime, CallbackInfo ci) {
		if (!VulkanStateTracker.isVulkanActive()) return;
		ReflexManager.getInstance().onSimulationEnd();
	}

	// -------------------------------------------------------------------------
	// renderFrame — render and present phase
	// -------------------------------------------------------------------------

	/**
	 * Just before gameRenderer.render(): mark render submission start.
	 */
	@Inject(
		method = "renderFrame",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"
		)
	)
	private void nvidia_reflex$onRenderSubmitStart(boolean advanceGameTime, CallbackInfo ci) {
		if (!VulkanStateTracker.isVulkanActive()) return;
		ReflexManager.getInstance().onRenderSubmitStart();
	}

	/**
	 * Just after gameRenderer.render(): mark render submission end.
	 */
	@Inject(
		method = "renderFrame",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V",
			shift = At.Shift.AFTER
		)
	)
	private void nvidia_reflex$onRenderSubmitEnd(boolean advanceGameTime, CallbackInfo ci) {
		if (!VulkanStateTracker.isVulkanActive()) return;
		ReflexManager.getInstance().onRenderSubmitEnd();
	}

	/**
	 * Just before windowSurface.present(): mark present start.
	 * Targets the GpuSurface.present() call inside renderFrame.
	 */
	@Inject(
		method = "renderFrame",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V"
		)
	)
	private void nvidia_reflex$onPresentStart(boolean advanceGameTime, CallbackInfo ci) {
		if (!VulkanStateTracker.isVulkanActive()) return;
		ReflexManager.getInstance().onPresentStart();
	}

	/**
	 * Just after windowSurface.present(): mark present end.
	 */
	@Inject(
		method = "renderFrame",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V",
			shift = At.Shift.AFTER
		)
	)
	private void nvidia_reflex$onPresentEnd(boolean advanceGameTime, CallbackInfo ci) {
		if (!VulkanStateTracker.isVulkanActive()) return;
		ReflexManager.getInstance().onPresentEnd();
	}
}
