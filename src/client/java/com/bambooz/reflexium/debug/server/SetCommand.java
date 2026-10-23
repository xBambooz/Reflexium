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
			"upscaler", "quality", "dlss_model", "fsr_sharpness", "frame_generation", "fg_multiplier", "fg_force_multiplier",
			"reflex_mode", "pack_shadow_cap",
			// Vanilla options
			"vsync", "fullscreen", "pause", "improved_transparency", "crosshair_3d",
			// Upscaling dev overrides
			"jitter", "jitter_sign", "jitter_sign_x", "jitter_sign_y", "mv_debug", "object_motion", "object_static", "hand_motion",
			"dlss_preset", "mip_bias_offset", "perf_uniforms", "perf_depth", "cloud_motion", "crosshair_defer",
			"particles_defer", "particles_fg_replay", "foliage_clear", "foliage_depth_debug",
			// Shader pack dev overrides
			"shaders", "pack_taa", "pack_resolve", "pack_filters",
			// Reflex diagnostics
			"reflex_trace", "reflex_submission", "reflex_interval_us");

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
			case "pack_shadow_cap" -> {
				// "<resolution>,<distance>"; 0 keeps the pack's value. Recompiles the pack so the options apply.
				String[] parts = value.trim().split(",");
				if (parts.length != 2) throw new DebugHttp.BadRequest("pack_shadow_cap must be <resolution>,<distance> (0 = pack value)");
				int resolution, distance;
				try { resolution = Integer.parseInt(parts[0].trim()); distance = Integer.parseInt(parts[1].trim()); }
				catch (NumberFormatException e) { throw new DebugHttp.BadRequest("pack_shadow_cap values must be whole numbers"); }
				if (resolution < 0 || resolution > 8192 || distance < 0 || distance > 1024) throw new DebugHttp.BadRequest("pack_shadow_cap range is 0..8192,0..1024");
				yield () -> {
					ReflexiumConfig.get().setPackShadowCaps(resolution, distance);
					IrisCompat.reloadForOptionChange();
				};
			}
			case "hand_motion" -> {
				boolean on = flag(value, true);
				yield () -> DebugSettings.setHandMotion(on);
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
			case "reflex_trace" -> {
				int frames = Integer.parseInt(value);
				if (frames < 0 || frames > 32) throw new DebugHttp.BadRequest("Reflex trace must be 0..32 frames");
				yield () -> FrameTrace.arm(frames);
			}
			case "reflex_submission" -> {
				boolean enabled = flag(value, true);
				yield () -> DebugSettings.reflexSubmission = enabled;
			}
			case "reflex_interval_us" -> {
				int interval = Integer.parseInt(value);
				if (interval < -1 || interval > 1_000_000) throw new DebugHttp.BadRequest("Reflex interval must be -1..1000000 us");
				yield () -> DebugSettings.reflexIntervalUs = interval;
			}
			case "reflex_mode" -> {
				ReflexMode mode = parseEnum(ReflexMode.class, value);
				yield () -> ReflexController.get().setMode(mode);
			}
			case "frame_generation" -> {
				FrameGeneration mode = parseEnum(FrameGeneration.class, value);
				yield () -> { ReflexiumConfig.get().setFrameGeneration(mode); FrameGenerationRenderer.get().retry(); };
			}
			case "fg_force_multiplier" -> {
				boolean enabled = flag(value, false);
				yield () -> { ReflexiumConfig.get().setFgForceMultiplier(enabled); FrameGenerationRenderer.get().retry(); };
			}
			case "fg_multiplier" -> {
				int multiplier = "auto".equalsIgnoreCase(value) ? 0 : integer(Map.of("v", value), "v", 0);
				int maximum = Math.max(2, FrameGenerationRenderer.get().maximumMultiplier());
				if (multiplier != 0 && (multiplier < 2 || multiplier > maximum)) throw new DebugHttp.BadRequest("FG multiplier must be auto or 2.." + maximum + " on this GPU");
				yield () -> { ReflexiumConfig.get().setFrameGenerationMultiplier(multiplier); FrameGenerationRenderer.get().retry(); };
			}
			case "upscaler" -> {
				Upscaler upscaler = parseEnum(Upscaler.class, value);
				yield () -> ReflexiumConfig.get().setUpscaler(upscaler);
			}
			case "quality" -> {
				UpscaleQuality quality = parseEnum(UpscaleQuality.class, value);
				yield () -> ReflexiumConfig.get().setUpscaleQuality(quality);
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
