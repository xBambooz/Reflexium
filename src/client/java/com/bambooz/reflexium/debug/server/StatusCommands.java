package com.bambooz.reflexium.debug.server;

import com.bambooz.reflexium.compat.ShaderPacks;
import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.debug.DebugSettings;
import com.bambooz.reflexium.debug.MeasurementCounters;
import com.bambooz.reflexium.framegen.FrameGenerationRenderer;
import com.bambooz.reflexium.reflex.ReflexController;
import com.bambooz.reflexium.upscale.UpscalingRenderer;
import com.bambooz.reflexium.upscale.fsr.FsrSwapchain;
import com.bambooz.reflexium.vulkan.VulkanLifecycle;
import com.bambooz.reflexium.vulkan.VulkanSession;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Map;

import static com.bambooz.reflexium.debug.server.DebugHttp.required;
import static com.bambooz.reflexium.debug.server.RenderThread.call;

/** {@code /status}, the harness status contributor, and frame interval statistics. */
final class StatusCommands {
	private StatusCommands() {
	}

	static JsonObject status() throws Exception {
		return call(StatusCommands::statusNow);
	}

	/** Render thread. Requested settings, effective state and counters, as one flat JSON object. */
	static JsonObject statusNow() {
		Minecraft mc = Minecraft.getInstance();
		ReflexiumConfig config = ReflexiumConfig.get();
		UpscalingRenderer upscaling = UpscalingRenderer.get();
		UpscalingRenderer.DebugInfo info = upscaling.debugInfo();
		ReflexController reflex = ReflexController.get();
		FrameGenerationRenderer fg = FrameGenerationRenderer.get();
		JsonObject o = new JsonObject();
		o.addProperty("upscaler", config.upscaler().serializedName());
		o.addProperty("upscaler_effective", upscaling.effectiveUpscaler().serializedName());
		o.addProperty("upscaler_built", info.builtUpscaler());
		o.addProperty("quality", config.upscaleQuality().serializedName());
		o.addProperty("dlss_model", config.dlssModel().serializedName());
		o.addProperty("dlss_preset_hint", upscaling.presetHint());
		o.addProperty("history_reset_frames", upscaling.historyResetFrames());
		o.addProperty("transient_motion_uniforms", upscaling.usesTransientUniforms());
		o.addProperty("direct_scene_depth", upscaling.usesDirectDepth());
		o.addProperty("reflex_mode", config.reflexMode().name());
		o.addProperty("reflex_status", reflex.statusLine());
		o.addProperty("smart_reflex", config.smartReflex());
		o.addProperty("reflex_pacing_paused", reflex.isPacingPaused());
		o.addProperty("reflex_sleeping", reflex.isSleeping());
		o.addProperty("reflex_latency", reflex.latencyLine());
		o.addProperty("reflex_submission", DebugSettings.reflexSubmission);
		VulkanSession session = VulkanSession.current();
		o.addProperty("reflex_extension_revision", session == null ? 0 : session.lowLatencyRevision());
		o.addProperty("reflex_attribution", DebugSettings.reflexSubmission && session != null && session.explicitAttributionSupported() ? "explicit (graphics queue only)" : "implicit");
		o.addProperty("reflex_interval_us", DebugSettings.reflexIntervalUs);
		o.addProperty("fsr_sharpness", config.fsrSharpness());
		o.addProperty("frame_generation", config.frameGeneration().serializedName());
		o.addProperty("fg_force_multiplier", config.fgForceMultiplier());
		o.addProperty("fg_multiplier", config.frameGenerationMultiplier());
		o.addProperty("fg_requested_generated", fg.requestedGeneratedCount());
		o.addProperty("fg_effective_multiplier", fg.refreshPolicy().multiplier());
		o.addProperty("fg_policy_probes", fg.refreshPolicy().probes());
		o.addProperty("fg_policy_probing", fg.refreshPolicy().probing());
		o.addProperty("fg_refresh_bypass", fg.refreshPolicy().bypass());
		o.addProperty("fg_refresh_cap", fg.refreshPolicy().cap());
		o.addProperty("fg_policy_real_fps", fg.refreshPolicy().realFps());
		o.addProperty("fg_policy_estimate_source", "measured bypass real presents; GPU minus FG is an upper bound");
		o.addProperty("fg_resets_per_s", fg.resetsPerSecond());
		o.addProperty("fg_status", fg.statusLine());
		o.addProperty("fg_max_multiplier", fg.maximumMultiplier());
		var swapchain = VulkanLifecycle.swapchainState();
		if (swapchain != null) {
			o.addProperty("swapchain_images_requested", swapchain.requested());
			o.addProperty("swapchain_images_created", swapchain.created());
			o.addProperty("swapchain_present_mode", VulkanLifecycle.presentModeName(swapchain.presentMode()));
			o.addProperty("swapchain_surface_min_images", swapchain.surfaceMin());
			o.addProperty("swapchain_surface_max_images", swapchain.surfaceMax());
			o.addProperty("swapchain_extent", swapchain.width() + "x" + swapchain.height());
		}
		long[] counters = new long[MeasurementCounters.NAMES.length];
		MeasurementCounters.read(counters);
		for (int i = 0; i < counters.length; i++) o.addProperty("measure_" + MeasurementCounters.NAMES[i], counters[i]);
		o.addProperty("fg_evaluated_frames", fg.evaluatedFrames());
		o.addProperty("fg_generated_presents", fg.generatedPresents());
		o.addProperty("fg_real_presents", fg.realPresents());
		o.addProperty("fg_submitted_fps", fg.submittedFps());
		o.addProperty("fg_submitted_real_fps", fg.submittedRealFps());
		o.addProperty("fg_display_feedback", "unavailable");
		o.addProperty("fg_metering_feedback", fg.meteringFeedback());
		o.addProperty("fg_metering_feedback_raw", fg.meteringFeedbackRaw());
		o.addProperty("fg_metering_result", fg.meteringResult());
		o.addProperty("fg_metering_batch_configs", fg.meteringBatchConfigs());
		o.addProperty("fg_metering_reset_configs", fg.meteringResetConfigs());
		o.addProperty("fg_metering_unreported", fg.meteringUnreported());
		o.addProperty("fg_metering_accepted", fg.meteringAccepted());
		o.addProperty("fg_metering_status", fg.meteringStatus());
		o.addProperty("fg_metering_rejected", fg.meteringRejected());
		o.addProperty("fg_metering_rejection_streak", fg.meteringRejectionStreak());
		o.addProperty("fg_metering_reset_pending", fg.meteringResetPending());
		o.addProperty("fg_metering_problem", fg.meteringProblem());
		o.addProperty("fg_batches_started", fg.batchesStarted());
		o.addProperty("fg_batches_completed", fg.batchesCompleted());
		o.addProperty("fg_failed", fg.failed());
		o.addProperty("fg_problem", fg.problem());
		o.addProperty("fg_sdk_present_count", FsrSwapchain.presentCount());
		o.addProperty("fg_sdk_scheduler", FsrSwapchain.active());
		o.addProperty("upscaling_active", info.active());
		o.addProperty("status_line", upscaling.statusLine());
		o.add("render_size", pair("w", info.renderWidth(), "h", info.renderHeight()));
		o.add("display_size", pair("w", info.displayWidth(), "h", info.displayHeight()));
		o.addProperty("jitter_index", info.jitterIndex());
		JsonObject jitter = new JsonObject();
		jitter.addProperty("x", info.jitterX());
		jitter.addProperty("y", info.jitterY());
		o.add("jitter_px", jitter);
		JsonObject sign = new JsonObject();
		sign.addProperty("x", info.jitterSignX());
		sign.addProperty("y", info.jitterSignY());
		o.add("jitter_sign", sign);
		o.addProperty("jitter_enabled", DebugSettings.jitterEnabled());
		o.addProperty("mv_debug", DebugSettings.motionVectorView());
		o.addProperty("foliage_depth_debug", DebugSettings.foliageDepthDebug);
		o.addProperty("object_motion", DebugSettings.objectMotion());
		o.addProperty("object_vertices_captured", info.capturedVertices());
		o.addProperty("object_vertices_drawn", info.drawnVertices());
		o.addProperty("object_entities", info.entities());
		o.addProperty("object_block_entities", info.blockEntities());
		o.addProperty("mip_bias", info.mipBias());
		o.addProperty("reactive_mask", info.reactiveMask());
		o.addProperty("evaluated_frames", info.evaluatedFrames());
		o.addProperty("pack_hand_mask", info.packHandMask());
		o.add("observed", upscaling.observedInputs());
		o.addProperty("pack", ShaderPacks.status());
		o.addProperty("fps", mc.getFps());
		o.addProperty("frame_time_ms", Math.round(DebugSettings.frameTimeMs() * 100f) / 100f);
		o.addProperty("frames", DebugSettings.frames());
		LocalPlayer player = mc.player;
		o.addProperty("world_loaded", mc.level != null && player != null);
		o.addProperty("screen", mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
		o.addProperty("hud_hidden", mc.gui.hud.isHidden());
		o.addProperty("motion_running", DebugMotion.running());
		if (player != null) {
			o.add("camera", cameraJson(player));
		}
		o.addProperty("last_error", info.lastError());
		return o;
	}

	static JsonObject cameraJson(LocalPlayer player) {
		JsonObject camera = new JsonObject();
		Vec3 eye = player.getEyePosition();
		camera.addProperty("x", round(player.getX()));
		camera.addProperty("y", round(player.getY()));
		camera.addProperty("z", round(player.getZ()));
		camera.addProperty("eye_y", round(eye.y));
		camera.addProperty("yaw", round(player.getYRot()));
		camera.addProperty("pitch", round(player.getXRot()));
		return camera;
	}

	private static JsonObject pair(String a, int av, String b, int bv) {
		JsonObject o = new JsonObject();
		o.addProperty(a, av);
		o.addProperty(b, bv);
		return o;
	}

	private static double round(double value) {
		return Math.round(value * 1000.0) / 1000.0;
	}

	/** Current frame index. Pass it as {@code frame_stats_from} later to measure the frames rendered in between. */
	static JsonObject frameMark() {
		JsonObject o = new JsonObject();
		o.addProperty("frames", DebugSettings.frames());
		return o;
	}

	/** Render-thread frame intervals after the marked index: mean, percentiles and worst-frame lows. */
	static JsonObject frameStats(Map<String, String> q) {
		long from = Long.parseLong(required(q, "frame_stats_from"));
		double[] ms = DebugSettings.frameIntervalsMs(from);
		JsonObject o = new JsonObject();
		o.addProperty("frames_from", from);
		o.addProperty("frames_to", DebugSettings.frames());
		o.addProperty("intervals", ms.length);
		if (ms.length > 0) {
			double[] sorted = ms.clone();
			Arrays.sort(sorted);
			double mean = Arrays.stream(sorted).average().orElse(0);
			o.addProperty("mean_ms", round(mean));
			o.addProperty("p50_ms", round(sorted[sorted.length / 2]));
			o.addProperty("p99_ms", round(sorted[(int) Math.min(sorted.length - 1, Math.floor(sorted.length * 0.99))]));
			o.addProperty("avg_fps", round(1000.0 / mean));
			o.addProperty("low_1pct_fps", round(1000.0 / worstMean(sorted, 0.01)));
			o.addProperty("low_0_1pct_fps", round(1000.0 / worstMean(sorted, 0.001)));
		}
		return o;
	}

	/** Mean of the slowest {@code fraction} of ascending-sorted intervals, at least one interval. */
	private static double worstMean(double[] sorted, double fraction) {
		int count = Math.max(1, (int) Math.ceil(sorted.length * fraction));
		double sum = 0;
		for (int i = sorted.length - count; i < sorted.length; i++) {
			sum += sorted[i];
		}
		return sum / count;
	}
}
