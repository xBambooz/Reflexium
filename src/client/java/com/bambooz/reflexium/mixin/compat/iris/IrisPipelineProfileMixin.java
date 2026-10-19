package com.bambooz.reflexium.mixin.compat.iris;

import com.bambooz.reflexium.debug.FrameProfiler;
import com.bambooz.reflexium.dlss.UpscalingRenderer;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opt-in GPU timestamps around Iris's shadow pass and its composite chains, so the share of the frame that does
 * not scale with render resolution is measured instead of inferred. Each scope is a no-op unless the profiler is on.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPipelineProfileMixin {
	@Unique private long reflexium$shadows, reflexium$begin, reflexium$deferred, reflexium$composite, reflexium$final;
	@Unique private long reflexium$phaseTiming;
	@Unique private int reflexium$phaseLabel = -1;
	@Unique private boolean reflexium$inShadows;

	@Unique
	private static VkCommandBuffer reflexium$commands() {
		return UpscalingRenderer.get().profilingCommands();
	}

	@Unique
	private static long reflexium$start(int label) {
		return FrameProfiler.enabled() && reflexium$commands() != null ? FrameProfiler.start(label, reflexium$commands()) : 0;
	}

	@Unique
	private static void reflexium$end(int label, long timing) {
		if (timing != 0) FrameProfiler.end(label, timing, reflexium$commands());
	}

	@Inject(method = "renderShadows", at = @At("HEAD"))
	private void reflexium$shadowsStart(CallbackInfo ci) {
		reflexium$closePhase();
		reflexium$inShadows = true;
		reflexium$shadows = reflexium$start(FrameProfiler.IRIS_SHADOWS);
	}

	@Inject(method = "renderShadows", at = @At("RETURN"))
	private void reflexium$shadowsEnd(CallbackInfo ci) {
		reflexium$closePhase();
		reflexium$inShadows = false;
		reflexium$end(FrameProfiler.IRIS_SHADOWS, reflexium$shadows);
		reflexium$shadows = 0;
	}

	@Inject(method = "beginLevelRendering", at = @At(value = "INVOKE",
			target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V"))
	private void reflexium$beginStart(CallbackInfo ci) {
		reflexium$begin = reflexium$start(FrameProfiler.IRIS_BEGIN);
	}

	@Inject(method = "beginLevelRendering", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
			target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V"))
	private void reflexium$beginEnd(CallbackInfo ci) {
		reflexium$end(FrameProfiler.IRIS_BEGIN, reflexium$begin);
		reflexium$begin = 0;
	}

	@Inject(method = "beginTranslucents", at = @At(value = "INVOKE",
			target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V"))
	private void reflexium$deferredStart(CallbackInfo ci) {
		reflexium$deferred = reflexium$start(FrameProfiler.IRIS_DEFERRED);
	}

	@Inject(method = "beginTranslucents", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
			target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V"))
	private void reflexium$deferredEnd(CallbackInfo ci) {
		reflexium$end(FrameProfiler.IRIS_DEFERRED, reflexium$deferred);
		reflexium$deferred = 0;
	}

	@Inject(method = "finalizeLevelRendering", at = @At(value = "INVOKE",
			target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V"))
	private void reflexium$compositeStart(CallbackInfo ci) {
		reflexium$closePhase();
		reflexium$composite = reflexium$start(FrameProfiler.IRIS_COMPOSITE);
	}

	@Inject(method = "finalizeLevelRendering", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
			target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V"))
	private void reflexium$compositeEnd(CallbackInfo ci) {
		reflexium$end(FrameProfiler.IRIS_COMPOSITE, reflexium$composite);
		reflexium$composite = 0;
	}

	@Inject(method = "finalizeLevelRendering", at = @At(value = "INVOKE",
			target = "Lnet/irisshaders/iris/pipeline/FinalPassRenderer;renderFinalPass()V"))
	private void reflexium$finalStart(CallbackInfo ci) {
		reflexium$final = reflexium$start(FrameProfiler.IRIS_FINAL);
	}

	@Inject(method = "finalizeLevelRendering", at = @At("RETURN"))
	private void reflexium$finalEnd(CallbackInfo ci) {
		reflexium$end(FrameProfiler.IRIS_FINAL, reflexium$final);
		reflexium$final = 0;
	}

	@Unique
	private void reflexium$closePhase() {
		if (reflexium$phaseTiming != 0) reflexium$end(reflexium$phaseLabel, reflexium$phaseTiming);
		reflexium$phaseTiming = 0;
		reflexium$phaseLabel = -1;
	}

	@Unique
	private static int reflexium$phaseGroup(String phase) {
		return switch (phase) {
			case "SKY", "SUNSET", "CUSTOM_SKY", "SUN", "MOON", "STARS", "VOID" -> FrameProfiler.PHASE_SKY;
			case "TERRAIN_SOLID" -> FrameProfiler.PHASE_TERRAIN_SOLID;
			case "TERRAIN_CUTOUT_MIPPED", "TERRAIN_CUTOUT" -> FrameProfiler.PHASE_TERRAIN_CUTOUT;
			case "ENTITIES", "BLOCK_ENTITIES" -> FrameProfiler.PHASE_ENTITIES;
			case "TERRAIN_TRANSLUCENT", "TRIPWIRE", "PARTICLES" -> FrameProfiler.PHASE_TRANSLUCENT;
			case "CLOUDS", "RAIN_SNOW" -> FrameProfiler.PHASE_CLOUDS_WEATHER;
			case "HAND_SOLID", "HAND_TRANSLUCENT" -> FrameProfiler.PHASE_HAND;
			case "NONE" -> -1;
			default -> FrameProfiler.PHASE_OTHER;
		};
	}

	/** Each Iris world phase is one contiguous scope until the next phase starts or the phase is removed. */
	@Inject(method = "setPhase", at = @At("HEAD"))
	private void reflexium$phaseChange(@Coerce Object phase, CallbackInfo ci) {
		if (reflexium$inShadows || !FrameProfiler.enabled()) return;
		reflexium$closePhase();
		int group = reflexium$phaseGroup(((Enum<?>) phase).name());
		if (group < 0) return;
		reflexium$phaseTiming = reflexium$start(group);
		reflexium$phaseLabel = group;
	}

	@Inject(method = "removePhaseIfNeeded", at = @At("HEAD"))
	private void reflexium$phaseRemoved(CallbackInfo ci) {
		if (!reflexium$inShadows) reflexium$closePhase();
	}
}
