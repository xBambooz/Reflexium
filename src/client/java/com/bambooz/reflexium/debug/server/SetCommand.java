package com.bambooz.reflexium.debug.server;

import com.bambooz.reflexium.compat.IrisCompat;
import com.bambooz.reflexium.compat.VitrailCompat;
import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.debug.DebugSettings;
import com.bambooz.reflexium.framegen.FrameGeneration;
import com.bambooz.reflexium.framegen.FrameGenerationRenderer;
import com.bambooz.reflexium.reflex.ReflexController;
import com.bambooz.reflexium.reflex.ReflexMode;
import com.bambooz.reflexium.upscale.UpscaleQuality;
import com.bambooz.reflexium.upscale.Upscaler;
import com.bambooz.reflexium.upscale.UpscalingRenderer;
import com.bambooz.reflexium.upscale.dlss.DlssModel;
import com.bambooz.reflexium.vulkan.FrameTrace;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.bambooz.reflexium.debug.server.DebugHttp.flag;
import static com.bambooz.reflexium.debug.server.DebugHttp.integer;
import static com.bambooz.reflexium.debug.server.DebugHttp.parseEnum;
import static com.bambooz.reflexium.debug.server.DebugHttp.required;
import static com.bambooz.reflexium.debug.server.RenderThread.call;

/** {@code /set?key=&value=}: a setting or dev override. Every value is validated before the render thread is touched. */
final class SetCommand {
	static final List<String> KEYS = List.of(
			// Player settings
			"upscaler", "quality", "dlss_model", "fsr_sharpness", "frame_generation", "fg_multiplier",
			"reflex_mode",
			// Vanilla options
			"vsync", "fullscreen", "exclusive_fullscreen", "pause", "improved_transparency", "crosshair_3d",
			// Upscaling dev overrides
			"jitter", "jitter_sign", "jitter_sign_x", "jitter_sign_y", "mv_debug", "object_motion", "object_static", "hand_motion",
			"foliage_motion", "bob_view", "pack_hand_mask", "jitter_phases", "device_loss", "dlssg_fail",
			"dlss_preset", "mip_bias_offset", "perf_uniforms", "perf_depth", "cloud_motion", "crosshair_defer",
			"particles_defer", "particles_fg_replay", "foliage_clear", "foliage_depth_debug",
			// Shader pack dev overrides
			"shaders", "shader_pack", "pack_taa", "pack_resolve", "pack_filters",
			// Reflex diagnostics
			"reflex_trace", "reflex_interval_us", "pcl_flash");

	private SetCommand() {
	}

	static JsonObject set(Map<String, String> q) throws Exception {
		String key = required(q, "key").toLowerCase(Locale.ROOT);
		String value = required(q, "value");
		// Validate before touching the render thread so a typo never half-applies.
		Runnable apply = switch (key) {
			case "crosshair_3d" -> {
				// F3's axis crosshair, for composition-order captures; leaves the rest of the debug overlay alone.
				boolean on = flag(value, true);
				yield () -> Minecraft.getInstance().debugEntries.setStatus(DebugScreenEntries.THREE_DIMENSIONAL_CROSSHAIR,
						on ? DebugScreenEntryStatus.ALWAYS_ON : DebugScreenEntryStatus.IN_OVERLAY);
			}
			case "vsync", "fullscreen", "pause" -> {
				boolean on = flag(value, true);
				yield () -> {
					Minecraft mc = Minecraft.getInstance();
					switch (key) {
						case "vsync" -> mc.options.enableVsync().set(on);
						case "fullscreen" -> { mc.options.fullscreen().set(on); mc.getWindow().setFullscreen(on); }
						case "pause" -> { if (on) mc.pauseGame(false); else mc.setScreenAndShow(null); }
					}
				};
			}
			case "exclusive_fullscreen" -> {
				boolean on = flag(value, true);
				yield () -> Minecraft.getInstance().options.exclusiveFullscreen().set(on);
			}
			case "jitter_phases" -> {
				int phases = Integer.parseInt(value);
				if (phases != 0 && (phases < 1 || phases > 256)) throw new DebugHttp.BadRequest("jitter_phases must be 0 (ratio policy) or 1..256");
				yield () -> DebugSettings.setJitterPhases(phases);
			}
			case "device_loss" -> {
				boolean on = flag(value, true);
				DebugSettings.setSimulatedDeviceLost(on);
				yield () -> { };
			}
			case "dlssg_fail" -> {
				boolean on = flag(value, true);
				DebugSettings.setFailDlssgLoad(on);
				yield () -> { };
			}
			case "cloud_motion" -> {
				boolean on = flag(value, true);
				yield () -> DebugSettings.cloudMotion = on;
			}
			case "particles_fg_replay" -> {
				boolean on = flag(value, true);
				yield () -> DebugSettings.replayFgParticles = on;
			}
			case "particles_defer" -> {
				boolean on = flag(value, true);
				yield () -> DebugSettings.deferParticles = on;
			}
			case "improved_transparency" -> {
				boolean on = flag(value, true);
				yield () -> Minecraft.getInstance().options.improvedTransparency().set(on);
			}
			case "crosshair_defer" -> {
				boolean on = flag(value, true);
				yield () -> DebugSettings.deferCrosshair = on;
			}
			case "perf_uniforms" -> {
				boolean on = flag(value, true);
				yield () -> DebugSettings.fastUniforms = on;
			}
			case "perf_depth" -> {
				boolean on = flag(value, true);
				yield () -> DebugSettings.directDepth = on;
			}
			case "dlss_preset" -> {
				int preset = Integer.parseInt(value);
				if (preset != -1 && preset != 5 && preset != 6 && preset != 10 && preset != 11 && preset != 12 && preset != 13) throw new DebugHttp.BadRequest("Preset must be -1 (quality-aware mapping), 5 (E), 6 (F), 10 (J), 11 (K), 12 (L) or 13 (M)");
				yield () -> DebugSettings.dlssPreset = preset;
			}
			case "mip_bias_offset" -> {
				float offset;
				try { offset = Float.parseFloat(value.trim()); } catch (NumberFormatException e) { throw new DebugHttp.BadRequest("'mip_bias_offset' must be a number"); }
				if (!Float.isFinite(offset) || offset < -4.0f || offset > 4.0f) throw new DebugHttp.BadRequest("mip_bias_offset must be finite within -4..4 (+1 reproduces the pre-baseline bias)");
				yield () -> DebugSettings.setMipBiasOffset(offset);
			}
			case "hand_motion" -> {
				boolean on = flag(value, true);
				yield () -> DebugSettings.setHandMotion(on);
			}
			case "foliage_motion" -> {
				boolean on = flag(value, true);
				yield () -> DebugSettings.setFoliageMotion(on);
			}
			case "pack_hand_mask" -> {
				boolean on = flag(value, true);
				yield () -> DebugSettings.setPackHandMask(on);
			}
			case "bob_view" -> {
				boolean on = flag(value, true);
				yield () -> Minecraft.getInstance().options.bobView().set(on);
			}
			case "pack_taa" -> {
				boolean retained = value.trim().equalsIgnoreCase("retain") || flag(value.equals("retain") ? "1" : value.equals("off") ? "0" : value, true);
				yield () -> VitrailCompat.debugSetPackTaa(retained);
			}
			case "pack_resolve" -> {
				boolean bypass = flag(value, true);
				yield () -> VitrailCompat.debugSetPackResolveBypass(bypass);
			}
			case "pack_filters" -> {
				boolean retained = flag(value, true);
				yield () -> VitrailCompat.debugSetPackPreFilters(retained);
			}
			case "shaders" -> {
				boolean on = flag(value, true);
				yield () -> {
					if (IrisCompat.isLoaded()) IrisCompat.debugSetPackEnabled(on);
					else VitrailCompat.debugSetPackEnabled(on);
				};
			}
			case "shader_pack" -> {
				String pack = value.trim();
				yield () -> IrisCompat.debugSetShaderPack(pack);
			}
			case "reflex_trace" -> {
				int frames = Integer.parseInt(value);
				if (frames < 0 || frames > 32) throw new DebugHttp.BadRequest("Reflex trace must be 0..32 frames");
				yield () -> FrameTrace.arm(frames);
			}
			case "reflex_interval_us" -> {
				int interval = Integer.parseInt(value);
				if (interval < -1 || interval > 1_000_000) throw new DebugHttp.BadRequest("Reflex interval must be -1..1000000 us");
				yield () -> DebugSettings.reflexIntervalUs = interval;
			}
			case "pcl_flash" -> {
				flag(value, true);
				yield () -> ReflexController.get().triggerFlash();
			}
			case "reflex_mode" -> {
				ReflexMode mode = parseEnum(ReflexMode.class, value);
				yield () -> ReflexController.get().setMode(mode);
			}
			case "frame_generation" -> {
				FrameGeneration mode = parseEnum(FrameGeneration.class, value);
				yield () -> { ReflexiumConfig.get().setFrameGeneration(mode); FrameGenerationRenderer.get().retry(); UpscalingRenderer.get().onModeChanged(); };
			}
			case "fg_multiplier" -> {
				int multiplier = "auto".equalsIgnoreCase(value) ? 0 : integer(Map.of("v", value), "v", 0);
				int maximum = Math.max(2, FrameGenerationRenderer.get().maximumMultiplier());
				if (multiplier != 0 && (multiplier < 2 || multiplier > maximum)) throw new DebugHttp.BadRequest("FG multiplier must be auto or 2.." + maximum + " on this GPU");
				yield () -> { ReflexiumConfig.get().setFrameGenerationMultiplier(multiplier); FrameGenerationRenderer.get().retry(); UpscalingRenderer.get().onModeChanged(); };
			}
			case "upscaler" -> {
				Upscaler upscaler = parseEnum(Upscaler.class, value);
				yield () -> { ReflexiumConfig.get().setUpscaler(upscaler); UpscalingRenderer.get().onModeChanged(); };
			}
			case "quality" -> {
				UpscaleQuality quality = parseEnum(UpscaleQuality.class, value);
				yield () -> { ReflexiumConfig.get().setUpscaleQuality(quality); UpscalingRenderer.get().onModeChanged(); };
			}
			case "dlss_model" -> {
				DlssModel model = parseEnum(DlssModel.class, value);
				yield () -> ReflexiumConfig.get().setDlssModel(model);
			}
			case "fsr_sharpness" -> {
				int sharpness = integer(Map.of("v", value), "v", 20);
				yield () -> ReflexiumConfig.get().setFsrSharpness(sharpness);
			}
			case "jitter_sign_x" -> {
				float sign = sign(value);
				yield () -> DebugSettings.setJitterSignX(sign);
			}
			case "jitter_sign_y" -> {
				float sign = sign(value);
				yield () -> DebugSettings.setJitterSignY(sign);
			}
			case "jitter_sign" -> {
				float sign = sign(value);
				yield () -> {
					DebugSettings.setJitterSignX(sign);
					DebugSettings.setJitterSignY(sign);
				};
			}
			case "foliage_clear" -> {
				boolean on = flag(value, true);
				yield () -> DebugSettings.clearFoliageSurface = on;
			}
			case "foliage_depth_debug" -> {
				boolean on = flag(value, false);
				yield () -> DebugSettings.foliageDepthDebug = on;
			}
			case "mv_debug" -> {
				boolean on = flag(value, false);
				yield () -> DebugSettings.setMotionVectorView(on);
			}
			case "jitter" -> {
				boolean on = flag(value, true);
				yield () -> DebugSettings.setJitterEnabled(on);
			}
			case "object_motion" -> {
				boolean on = flag(value, true);
				yield () -> DebugSettings.setObjectMotion(on);
			}
			case "object_static" -> {
				boolean on = flag(value, false);
				yield () -> DebugSettings.setDrawStaticObjects(on);
			}
			default -> throw new DebugHttp.BadRequest("unknown key '" + key + "', expected " + String.join("|", KEYS));
		};
		return call(() -> {
			apply.run();
			UpscalingRenderer.get().onModeChanged();
			return StatusCommands.statusNow();
		});
	}

	/** 1 or -1, or "default" (NaN) for the built-in sign. */
	private static float sign(String value) {
		String text = value.trim().toLowerCase(Locale.ROOT);
		if (text.equals("default")) {
			return Float.NaN;
		}
		try {
			float parsed = Float.parseFloat(text);
			if (parsed != 1.0f && parsed != -1.0f) {
				throw new DebugHttp.BadRequest("jitter sign must be 1 or -1 (or 'default'), got '" + value + "'");
			}
			return parsed;
		} catch (NumberFormatException e) {
			throw new DebugHttp.BadRequest("jitter sign must be 1 or -1 (or 'default'), got '" + value + "'");
		}
	}
}
