package com.bambooz.reflexium.debug.server;

import com.bambooz.reflexium.debug.DebugSettings;
import com.bambooz.reflexium.framegen.FrameGenerationRenderer;
import com.bambooz.reflexium.upscale.UpscaleCapture;
import com.bambooz.reflexium.upscale.UpscalingRenderer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.bambooz.reflexium.debug.server.DebugHttp.GSON;
import static com.bambooz.reflexium.debug.server.DebugHttp.flag;
import static com.bambooz.reflexium.debug.server.DebugHttp.integer;
import static com.bambooz.reflexium.debug.server.DebugHttp.number;
import static com.bambooz.reflexium.debug.server.RenderThread.call;
import static com.bambooz.reflexium.debug.server.RenderThread.requireWorld;
import static com.bambooz.reflexium.debug.server.RenderThread.waitFrames;

/** Screenshots, screenshot sequences, and captures of the upscaler's and frame generation's actual inputs and outputs. */
final class CaptureCommands {
	private CaptureCommands() {
	}

	/**
	 * Arms one capture of the upscaler's real input and output from the same evaluated frame and writes
	 * name_input.png (render resolution), name_output.png (display resolution), name_motion.png and name_depth.png
	 * (encoded, see tools/CaptureAnalysis.java) and name.json with the observed frame metadata.
	 */
	static JsonObject captureUpscale(Map<String, String> params) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		String name = params.get("capture").replaceAll("[^A-Za-z0-9_.-]", "_");
		if (name.isEmpty() || name.equals("1")) name = "capture_" + stamp();
		Path dir = resolve(mc, params.getOrDefault("dir", "screenshots/upscaling"));
		boolean extras = !"0".equals(params.get("extras"));
		UpscaleCapture request = UpscalingRenderer.get().requestCapture(extras);
		JsonObject result = new JsonObject();
		try {
			JsonObject meta = request.metadata.get(20, TimeUnit.SECONDS);
			Files.createDirectories(dir);
			String[] names = {"input", "output", "motion", "depth"};
			NativeImage[] images = new NativeImage[4];
			try {
				images[0] = request.input.get(20, TimeUnit.SECONDS);
				images[1] = request.output.get(20, TimeUnit.SECONDS);
				if (extras) {
					images[2] = request.motion.get(20, TimeUnit.SECONDS);
					images[3] = request.depth.get(20, TimeUnit.SECONDS);
				}
				JsonObject files = new JsonObject();
				for (int i = 0; i < images.length; i++) {
					if (images[i] == null) continue;
					Path file = dir.resolve(name + "_" + names[i] + ".png");
					images[i].writeToFile(file);
					JsonObject entry = new JsonObject();
					entry.addProperty("path", file.toAbsolutePath().toString());
					entry.addProperty("width", images[i].getWidth());
					entry.addProperty("height", images[i].getHeight());
					files.add(names[i], entry);
				}
				meta.add("files", files);
				Path json = dir.resolve(name + ".json");
				Files.writeString(json, GSON.toJson(meta), StandardCharsets.UTF_8);
				result.add("capture", meta);
				result.addProperty("json", json.toAbsolutePath().toString());
			} finally {
				UpscalingRenderer.get().cancelCapture(request);
			}
		} catch (TimeoutException | CancellationException e) {
			UpscalingRenderer.get().cancelCapture(request);
			throw new TimeoutException("upscaler capture did not complete (no evaluated world frame within 20 s)");
		} catch (Exception e) {
			UpscalingRenderer.get().cancelCapture(request);
			throw e;
		}
		return result;
	}

	/** Read every output at one render-thread boundary, rather than comparing unrelated screenshot frames. */
	static JsonObject captureGeneratedBatch(Map<String, String> params) throws Exception {
		Path dir = resolve(Minecraft.getInstance(), params.getOrDefault("dir", "screenshots/fg_" + stamp()));
		List<CompletableFuture<NativeImage>> images = new ArrayList<>();
		try {
			JsonObject meta = call(() -> {
				var fg = FrameGenerationRenderer.get();
				if (fg.refreshPolicy().bypass() || fg.failed()) throw new IllegalStateException("FG is bypassed or failed");
				JsonObject result = StatusCommands.statusNow();
				int count = fg.debugGeneratedCount();
				if (count < 1) throw new IllegalStateException("No generated outputs ready for this frame");
				result.addProperty("captured_generated_count", count);
				for (int i = -1; i < count; i++) {
					CompletableFuture<NativeImage> image = new CompletableFuture<>();
					images.add(image);
					Screenshot.takeScreenshot(i < 0 ? Minecraft.getInstance().gameRenderer.mainRenderTarget() : fg.debugTarget(i), image::complete);
				}
				return result;
			});
			Files.createDirectories(dir);
			for (int i = 0; i < images.size(); i++) {
				NativeImage image = images.get(i).get(20, TimeUnit.SECONDS);
				image.writeToFile(dir.resolve(i == 0 ? "real.png" : "generated_" + (i - 1) + ".png"));
			}
			Files.writeString(dir.resolve("metadata.json"), GSON.toJson(meta), StandardCharsets.UTF_8);
			meta.addProperty("capture_directory", dir.toString());
			return meta;
		} finally {
			for (var image : images) image.thenAccept(NativeImage::close);
		}
	}

	static JsonObject screenshot(Map<String, String> q) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		String raw = q.get("path");
		Path file = resolve(mc, raw == null || raw.isBlank() ? "screenshots/rx_" + stamp() + ".png" : raw);
		boolean gui = flag(q, "gui", false);
		boolean hiddenByUs = hideHud(gui);
		try {
			return capture(file, integer(q, "fg_index", -1), q);
		} finally {
			restoreHud(hiddenByUs);
		}
	}

	static JsonObject captureSequence(Map<String, String> q) throws Exception {
		requireWorld();
		Minecraft mc = Minecraft.getInstance();
		int count = integer(q, "count", 8);
		int interval = integer(q, "interval_ms", 100);
		if (count < 1 || count > 1000) {
			throw new DebugHttp.BadRequest("'count' must be 1..1000");
		}
		if (interval < 0) {
			throw new DebugHttp.BadRequest("'interval_ms' must be >= 0");
		}
		String dirRaw = q.get("dir");
		Path dir = resolve(mc, dirRaw == null || dirRaw.isBlank() ? "screenshots/rx_seq_" + stamp() : dirRaw);
		String prefix = q.getOrDefault("prefix", "frame");
		boolean hiddenByUs = hideHud(flag(q, "gui", false));
		try {
			int ticks = integer(q, "ticks", 0);
			double dx = number(q, "dx", 0);
			double dz = number(q, "dz", 0);
			float dyaw = (float) number(q, "dyaw", 0);
			float dpitch = (float) number(q, "dpitch", 0);
			CompletableFuture<Void> motion = null;
			if (ticks > 0 && (dx != 0 || dz != 0 || dyaw != 0 || dpitch != 0)) {
				motion = call(() -> DebugMotion.start(dx, dz, dyaw, dpitch, ticks));
			}
			JsonArray frames = new JsonArray();
			long start = System.nanoTime();
			for (int i = 0; i < count; i++) {
				long due = start + (long) i * interval * 1_000_000L;
				long wait = (due - System.nanoTime()) / 1_000_000L;
				if (wait > 0) {
					Thread.sleep(wait);
				}
				Path file = dir.resolve(String.format(Locale.ROOT, "%s_%03d.png", prefix, i));
				JsonObject meta = capture(file, -1);
				meta.addProperty("index", i);
				meta.addProperty("t_ms", (System.nanoTime() - start) / 1_000_000L);
				frames.add(meta);
			}
			if (motion != null) {
				motion.get(ticks * 200L + 15_000L, TimeUnit.MILLISECONDS);
			}
			JsonObject result = new JsonObject();
			result.addProperty("dir", dir.toAbsolutePath().toString());
			result.addProperty("count", count);
			result.add("frames", frames);
			return result;
		} finally {
			restoreHud(hiddenByUs);
		}
	}

	/** Reads back the main render target (display resolution, after upscaling), or a generated frame, and writes a PNG. */
	private static JsonObject capture(Path file, int generatedIndex) throws Exception {
		return capture(file, generatedIndex, Map.of());
	}

	private static JsonObject capture(Path file, int generatedIndex, Map<String, String> params) throws Exception {
		CompletableFuture<NativeImage> image = new CompletableFuture<>();
		JsonObject meta = call(() -> {
			Minecraft mc = Minecraft.getInstance();
			JsonObject m = new JsonObject();
			UpscalingRenderer.DebugInfo info = UpscalingRenderer.get().debugInfo();
			m.addProperty("frame", DebugSettings.frames());
			m.addProperty("jitter_index", info.jitterIndex());
			JsonObject jitter = new JsonObject();
			jitter.addProperty("x", info.jitterX());
			jitter.addProperty("y", info.jitterY());
			m.add("jitter_px", jitter);
			if (mc.player != null) {
				m.add("camera", StatusCommands.cameraJson(mc.player));
			}
			RenderTarget target = generatedIndex < 0 ? mc.gameRenderer.mainRenderTarget() : FrameGenerationRenderer.get().debugTarget(generatedIndex);
			Screenshot.takeScreenshot(target, image::complete);
			return m;
		});
		try (NativeImage img = image.get(20, TimeUnit.SECONDS)) {
			Path parent = file.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			int w = integer(params, "crop_width", img.getWidth()), h = integer(params, "crop_height", img.getHeight());
			int x = integer(params, "crop_x", (img.getWidth() - w) / 2), y = integer(params, "crop_y", (img.getHeight() - h) / 2);
			if (w <= 0 || h <= 0 || x < 0 || y < 0 || w > img.getWidth() - x || h > img.getHeight() - y)
				throw new DebugHttp.BadRequest("Crop must fit inside the rendered image");
			int pairSlot = integer(params, "crop_pair_slot", -1);
			if (pairSlot < -1 || pairSlot > 1) throw new DebugHttp.BadRequest("crop_pair_slot must be 0 or 1");
			try (NativeImage crop = new NativeImage(pairSlot < 0 ? w : w * 2, h, true)) {
				if (pairSlot == 1) {
					try (var input = Files.newInputStream(file); NativeImage first = NativeImage.read(input)) {
						if (first.getWidth() != w * 2 || first.getHeight() != h) throw new DebugHttp.BadRequest("Crop pair dimensions changed");
						first.copyRect(crop, 0, 0, 0, 0, w, h, false, false);
					}
				}
				img.copyRect(crop, x, y, pairSlot < 0 ? 0 : pairSlot * w, 0, w, h, false, false);
				crop.writeToFile(file);
			}
			meta.addProperty("width", w);
			meta.addProperty("height", h);
			meta.addProperty("crop_x", x);
			meta.addProperty("crop_y", y);
		}
		meta.addProperty("path", file.toAbsolutePath().toString());
		return meta;
	}

	/** Hides the HUD for a clean world image; returns true if this call hid it. */
	private static boolean hideHud(boolean keepGui) throws Exception {
		if (keepGui) {
			return false;
		}
		boolean hidden = call(() -> {
			var hud = Minecraft.getInstance().gui.hud;
			if (hud.isHidden()) {
				return false;
			}
			hud.toggle();
			return true;
		});
		if (hidden) {
			waitFrames(3);
		}
		return hidden;
	}

	private static void restoreHud(boolean hiddenByUs) throws Exception {
		if (hiddenByUs) {
			call(() -> {
				var hud = Minecraft.getInstance().gui.hud;
				if (hud.isHidden()) {
					hud.toggle();
				}
				return null;
			});
		}
	}

	/** Relative paths resolve from the game directory. */
	private static Path resolve(Minecraft mc, String raw) {
		Path path = Paths.get(raw);
		return path.isAbsolute() ? path : mc.gameDirectory.toPath().resolve(path);
	}

	private static String stamp() {
		return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
	}
}
