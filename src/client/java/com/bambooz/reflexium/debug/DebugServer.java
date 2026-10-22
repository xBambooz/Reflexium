package com.bambooz.reflexium.debug;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.dlss.DlssModel;
import com.bambooz.reflexium.dlss.UpscaleQuality;
import com.bambooz.reflexium.dlss.Upscaler;
import com.bambooz.reflexium.dlss.UpscalingRenderer;
import com.bambooz.reflexium.dlss.FrameGeneration;
import com.bambooz.reflexium.dlss.FrameGenerationRenderer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
	* Dev-only HTTP command server for tuning upscaling without clicking through menus.
	* Started only when the JVM property {@code reflexium.debugPort} is set; binds 127.0.0.1 only.
	* Every command that touches the game runs on the render thread and answers with JSON.
	* Usage: docs/DEBUGGING.md and tools/rxdbg.ps1.
	*/
public final class DebugServer {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final long CALL_TIMEOUT_MS = 30_000;
	private static volatile boolean active;

	private DebugServer() {
	}

	/** Optional dev harness integration, loaded reflectively so release users need no test dependency. */
	public static void registerHarnessEndpoints() {
		if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("agentharness")) return;
		try {
			Class<?> api = Class.forName("com.bambooz.agentharness.api.AgentHarness");
			if (!(boolean) api.getMethod("isActive").invoke(null)) return;
			Class<?> handler = Class.forName("com.bambooz.agentharness.api.EndpointHandler");
			Object endpoint = java.lang.reflect.Proxy.newProxyInstance(handler.getClassLoader(), new Class<?>[] {handler}, (proxy, method, args) -> {
				if (!method.getName().equals("handle")) return method.getName().equals("toString") ? "Reflexium debug endpoint" : null;
				@SuppressWarnings("unchecked")
				Map<String, String> params = (Map<String, String>) args[0].getClass().getMethod("params").invoke(args[0]);
				if (params.containsKey("key")) return set(params);
				if (params.containsKey("dh_targets")) return call(com.bambooz.reflexium.compat.IrisCompat::dhTargets);
				if (params.containsKey("frame_mark")) return frameMark();
				if (params.containsKey("frame_stats_from")) return frameStats(params);
				if (params.containsKey("profile")) return call(() -> FrameProfiler.command(params.get("profile")));
				if (params.containsKey("fg_capture")) return captureGeneratedBatch(params);
					if (params.containsKey("capture")) return captureUpscale(params);
				if (params.containsKey("reflex_timings")) return call(() -> com.bambooz.reflexium.reflex.ReflexController.get().timingReports());
				return status();
			});
			api.getMethod("register", String.class, String.class, handler)
					.invoke(null, "reflexium", "Upscaling status and runtime settings", endpoint);
			api.getMethod("statusContributor", String.class, Supplier.class)
					.invoke(null, "reflexium", (Supplier<JsonObject>) DebugServer::statusNow);
		} catch (ReflectiveOperationException | LinkageError e) {
			ReflexiumClient.LOGGER.warn("Minecraft agent harness integration could not register", e);
		}
	}

	/**
	 * Arms one capture of the upscaler's real input and output from the same evaluated frame and writes
	 * name_input.png (render resolution), name_output.png (display resolution), name_motion.png and name_depth.png
	 * (encoded, see tools/CaptureAnalysis.java) and name.json with the observed frame metadata.
	 */
	private static JsonObject captureUpscale(Map<String, String> params) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		String name = params.get("capture").replaceAll("[^A-Za-z0-9_.-]", "_");
		if (name.isEmpty() || name.equals("1")) name = "capture_" + stamp();
		Path dir = resolve(mc, params.getOrDefault("dir", "screenshots/upscaling"));
		boolean extras = !"0".equals(params.get("extras"));
		com.bambooz.reflexium.dlss.UpscaleCapture request = UpscalingRenderer.get().requestCapture(extras);
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
		} catch (TimeoutException | java.util.concurrent.CancellationException e) {
			UpscalingRenderer.get().cancelCapture(request);
			throw new TimeoutException("upscaler capture did not complete (no evaluated world frame within 20 s)");
		} catch (Exception e) {
			UpscalingRenderer.get().cancelCapture(request);
			throw e;
		}
		return result;
	}

	/** Read every output at one render-thread boundary, rather than comparing unrelated screenshot frames. */
	private static JsonObject captureGeneratedBatch(Map<String, String> params) throws Exception {
		Path dir = resolve(Minecraft.getInstance(), params.getOrDefault("dir", "screenshots/fg_" + stamp()));
		java.util.List<CompletableFuture<NativeImage>> images = new java.util.ArrayList<>();
		try {
			JsonObject meta = call(() -> {
				var fg = FrameGenerationRenderer.get();
				if (fg.refreshPolicy().bypass() || fg.failed()) throw new IllegalStateException("FG is bypassed or failed");
				JsonObject result = statusNow();
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
	/** Called from the client entrypoint. Does nothing unless -Dreflexium.debugPort=<port> is set. */
	public static void startIfRequested() {
		String raw = System.getProperty("reflexium.debugPort");
		if (raw == null || raw.isBlank()) {
			return;
		}
		int port;
		try {
			port = Integer.parseInt(raw.trim());
		} catch (NumberFormatException e) {
			ReflexiumClient.LOGGER.warn("Ignoring invalid reflexium.debugPort '{}'", raw);
			return;
		}
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port), 0);
			server.setExecutor(Executors.newCachedThreadPool(runnable -> {
				Thread thread = new Thread(runnable, "Reflexium debug HTTP");
				thread.setDaemon(true);
				return thread;
			}));
			route(server, "/status", q -> status());
			route(server, "/set", DebugServer::set);
			route(server, "/screenshot", DebugServer::screenshot);
			route(server, "/camera", DebugServer::camera);
			route(server, "/move", q -> script(q, true));
			route(server, "/turn", q -> script(q, false));
			route(server, "/capture_sequence", DebugServer::captureSequence);
			route(server, "/join", DebugServer::join);
			route(server, "/quit", DebugServer::quit);
			route(server, "/", q -> help());
			server.start();
			active = true;
			ReflexiumClient.LOGGER.warn("Reflexium debug server listening on http://127.0.0.1:{}/ (dev only)", port);
		} catch (IOException e) {
			ReflexiumClient.LOGGER.error("Could not start the Reflexium debug server on port {}", port, e);
		}
	}

	/** End of every client tick (render thread). */
	public static void onClientTick() {
		if (!active) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		// A background dev window would otherwise open the pause menu and stop the ticks scripts rely on.
		mc.options.pauseOnLostFocus = false;
		if (!mc.isPaused()) {
			DebugMotion.onClientTick();
		}
	}

	// ---------------------------------------------------------------------------------------------
	// HTTP plumbing

	private interface Command {
		JsonObject run(Map<String, String> query) throws Exception;
	}

	private static final class BadRequest extends RuntimeException {
		BadRequest(String message) {
			super(message);
		}
	}

	private static void route(HttpServer server, String path, Command command) {
		server.createContext(path, exchange -> {
			int code = 200;
			JsonObject body;
			try {
				body = command.run(query(exchange));
				body.addProperty("ok", true);
			} catch (BadRequest e) {
				code = 400;
				body = error(e.getMessage());
			} catch (TimeoutException e) {
				code = 504;
				body = error("timed out (render thread busy or condition never met)");
			} catch (Exception e) {
				code = 500;
				Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
				body = error(cause.getClass().getSimpleName() + ": " + cause.getMessage());
				ReflexiumClient.LOGGER.warn("Debug command {} failed", path, cause);
			}
			respond(exchange, code, body);
		});
	}

	private static JsonObject error(String message) {
		JsonObject o = new JsonObject();
		o.addProperty("ok", false);
		o.addProperty("error", message);
		return o;
	}

	private static void respond(HttpExchange exchange, int code, JsonObject body) throws IOException {
		byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(code, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static Map<String, String> query(HttpExchange exchange) {
		Map<String, String> result = new HashMap<>();
		String raw = exchange.getRequestURI().getRawQuery();
		if (raw != null) {
			for (String pair : raw.split("&")) {
				if (pair.isEmpty()) {
					continue;
				}
				int eq = pair.indexOf('=');
				String key = eq < 0 ? pair : pair.substring(0, eq);
				String value = eq < 0 ? "" : pair.substring(eq + 1);
				result.put(URLDecoder.decode(key, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT),
						URLDecoder.decode(value, StandardCharsets.UTF_8));
			}
		}
		return result;
	}

	private static double number(Map<String, String> q, String key, double fallback) {
		String value = q.get(key);
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			return Double.parseDouble(value.trim());
		} catch (NumberFormatException e) {
			throw new BadRequest("'" + key + "' must be a number, got '" + value + "'");
		}
	}

	private static int integer(Map<String, String> q, String key, int fallback) {
		return (int) Math.round(number(q, key, fallback));
	}

	private static boolean flag(Map<String, String> q, String key, boolean fallback) {
		String value = q.get(key);
		if (value == null || value.isBlank()) {
			return fallback;
		}
		return switch (value.trim().toLowerCase(Locale.ROOT)) {
			case "1", "true", "on", "yes" -> true;
			case "0", "false", "off", "no" -> false;
			default -> throw new BadRequest("'" + key + "' must be 0 or 1, got '" + value + "'");
		};
	}

	private static String required(Map<String, String> q, String key) {
		String value = q.get(key);
		if (value == null || value.isBlank()) {
			throw new BadRequest("missing '" + key + "'");
		}
		return value;
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
		String wanted = value.trim().toUpperCase(Locale.ROOT);
		for (E constant : type.getEnumConstants()) {
			if (constant.name().equals(wanted)) {
				return constant;
			}
		}
		StringBuilder names = new StringBuilder();
		for (E constant : type.getEnumConstants()) {
			names.append(names.isEmpty() ? "" : "|").append(constant.name().toLowerCase(Locale.ROOT));
		}
		throw new BadRequest("unknown value '" + value + "', expected " + names);
	}

	// ---------------------------------------------------------------------------------------------
	// Render thread helpers

	private static <T> T call(Supplier<T> task) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		CompletableFuture<T> future = new CompletableFuture<>();
		mc.execute(() -> {
			try {
				future.complete(task.get());
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		});
		return future.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
	}

	private static void waitUntil(BooleanSupplier condition, long timeoutMs) throws Exception {
		long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
		while (true) {
			try {
				if (call(condition::getAsBoolean)) {
					return;
				}
			} catch (TimeoutException busy) {
				// Render thread is blocked (world load); keep waiting until the deadline.
			}
			if (System.nanoTime() > deadline) {
				throw new TimeoutException();
			}
			Thread.sleep(100);
		}
	}

	private static void waitFrames(int count) throws Exception {
		long target = DebugSettings.frames() + count;
		long deadline = System.nanoTime() + 5_000_000_000L;
		while (DebugSettings.frames() < target && System.nanoTime() < deadline) {
			Thread.sleep(5);
		}
	}

	private static void requireWorld() throws Exception {
		Boolean loaded = call(() -> {
			Minecraft mc = Minecraft.getInstance();
			return mc.level != null && mc.player != null;
		});
		if (!loaded) {
			throw new BadRequest("no world loaded (use /join?world=<folder name>)");
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Commands

	private static JsonObject help() {
		JsonObject o = new JsonObject();
		JsonArray endpoints = new JsonArray();
		for (String line : new String[] {
				"/status",
				"/set?key=<upscaler|quality|dlss_model|fsr_sharpness|frame_generation|fg_multiplier|fg_force_multiplier|jitter_sign_x|jitter_sign_y|jitter_sign|mv_debug|jitter>&value=<v>",
				"/screenshot?path=<file.png>&gui=0|1",
				"/camera?x=&y=&z=&yaw=&pitch=",
				"/move?dx=&dz=&ticks=&async=0|1   (total blocks along world X/Z over ticks)",
				"/turn?dyaw=&dpitch=&ticks=&async=0|1   (total degrees over ticks)",
				"/capture_sequence?count=&interval_ms=&dir=&prefix=&gui=&dx=&dz=&dyaw=&dpitch=&ticks=",
				"/join?world=<save folder name>&timeout_s=",
				"/quit?exit=0|1"}) {
			endpoints.add(line);
		}
		o.add("endpoints", endpoints);
		return o;
	}

	private static JsonObject status() throws Exception {
		return call(DebugServer::statusNow);
	}

	private static JsonObject statusNow() {
		Minecraft mc = Minecraft.getInstance();
		ReflexiumConfig config = ReflexiumConfig.get();
		UpscalingRenderer upscaling = UpscalingRenderer.get();
		UpscalingRenderer.DebugInfo info = upscaling.debugInfo();
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
		o.addProperty("reflex_status", com.bambooz.reflexium.reflex.ReflexController.get().statusLine());
		o.addProperty("smart_reflex", config.smartReflex());
		o.addProperty("reflex_pacing_paused", com.bambooz.reflexium.reflex.ReflexController.get().isPacingPaused());
		o.addProperty("reflex_sleeping", com.bambooz.reflexium.reflex.ReflexController.get().isSleeping());
		o.addProperty("reflex_latency", com.bambooz.reflexium.reflex.ReflexController.get().latencyLine());
		o.addProperty("reflex_submission", DebugSettings.reflexSubmission);
		var session = com.bambooz.reflexium.vulkan.VulkanSession.current();
		o.addProperty("reflex_extension_revision", session == null ? 0 : session.lowLatencyRevision());
		o.addProperty("reflex_attribution", DebugSettings.reflexSubmission && session != null && session.explicitAttributionSupported() ? "explicit (graphics queue only)" : "implicit");
		o.addProperty("reflex_interval_us", DebugSettings.reflexIntervalUs);
		o.addProperty("fsr_sharpness", config.fsrSharpness());
		o.addProperty("frame_generation", config.frameGeneration().serializedName());
		o.addProperty("fg_force_multiplier", config.fgForceMultiplier());
	o.addProperty("fg_multiplier", config.frameGenerationMultiplier());
		o.addProperty("fg_requested_generated", FrameGenerationRenderer.get().requestedGeneratedCount());
		o.addProperty("fg_effective_multiplier", FrameGenerationRenderer.get().refreshPolicy().multiplier());
		o.addProperty("fg_policy_probes", FrameGenerationRenderer.get().refreshPolicy().probes());
	o.addProperty("fg_policy_probing", FrameGenerationRenderer.get().refreshPolicy().probing());
		o.addProperty("fg_refresh_bypass", FrameGenerationRenderer.get().refreshPolicy().bypass());
		o.addProperty("fg_refresh_cap", FrameGenerationRenderer.get().refreshPolicy().cap());
		o.addProperty("fg_policy_real_fps", FrameGenerationRenderer.get().refreshPolicy().realFps());
		o.addProperty("fg_policy_estimate_source", "measured bypass real presents; GPU minus FG is an upper bound");
		o.addProperty("fg_resets_per_s", FrameGenerationRenderer.get().resetsPerSecond());
	o.addProperty("fg_status", FrameGenerationRenderer.get().statusLine());
		o.addProperty("fg_max_multiplier", FrameGenerationRenderer.get().maximumMultiplier());
		var swapchain = com.bambooz.reflexium.vulkan.VulkanLifecycle.swapchainState();
		if (swapchain != null) {
			o.addProperty("swapchain_images_requested", swapchain.requested());
			o.addProperty("swapchain_images_created", swapchain.created());
			o.addProperty("swapchain_present_mode", com.bambooz.reflexium.vulkan.VulkanLifecycle.presentModeName(swapchain.presentMode()));
			o.addProperty("swapchain_surface_min_images", swapchain.surfaceMin());
			o.addProperty("swapchain_surface_max_images", swapchain.surfaceMax());
			o.addProperty("swapchain_extent", swapchain.width() + "x" + swapchain.height());
		}
		long[] counters = new long[MeasurementCounters.NAMES.length];
		MeasurementCounters.read(counters);
		for (int i = 0; i < counters.length; i++) o.addProperty("measure_" + MeasurementCounters.NAMES[i], counters[i]);
		o.addProperty("fg_evaluated_frames", FrameGenerationRenderer.get().evaluatedFrames());
		o.addProperty("fg_generated_presents", FrameGenerationRenderer.get().generatedPresents());
		o.addProperty("fg_real_presents", FrameGenerationRenderer.get().realPresents());
		o.addProperty("fg_submitted_fps", FrameGenerationRenderer.get().submittedFps());
		o.addProperty("fg_submitted_real_fps", FrameGenerationRenderer.get().submittedRealFps());
		o.addProperty("fg_display_feedback", "unavailable");
		var fg = FrameGenerationRenderer.get();
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
		o.addProperty("fg_sdk_present_count", com.bambooz.reflexium.dlss.FsrSwapchain.presentCount());
		o.addProperty("fg_sdk_scheduler", com.bambooz.reflexium.dlss.FsrSwapchain.active());
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
		o.addProperty("pack", com.bambooz.reflexium.compat.ShaderPacks.status());
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

	private static JsonObject cameraJson(LocalPlayer player) {
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
	private static JsonObject frameMark() {
		JsonObject o = new JsonObject();
		o.addProperty("frames", DebugSettings.frames());
		return o;
	}

	/** Render-thread frame intervals after the marked index: mean, percentiles and worst-frame lows. */
	private static JsonObject frameStats(Map<String, String> q) throws Exception {
		long from = Long.parseLong(required(q, "frame_stats_from"));
		double[] ms = DebugSettings.frameIntervalsMs(from);
		JsonObject o = new JsonObject();
		o.addProperty("frames_from", from);
		o.addProperty("frames_to", DebugSettings.frames());
		o.addProperty("intervals", ms.length);
		if (ms.length > 0) {
			double[] sorted = ms.clone();
			java.util.Arrays.sort(sorted);
			double mean = java.util.Arrays.stream(sorted).average().orElse(0);
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

	private static JsonObject set(Map<String, String> q) throws Exception {
		String key = required(q, "key").toLowerCase(Locale.ROOT);
		String value = required(q, "value");
		// Validate before touching the render thread so a typo never half-applies.
		Runnable apply = switch (key) {
			case "crosshair_3d" -> {
				// F3's axis crosshair, for composition-order captures; leaves the rest of the debug overlay alone.
				boolean on = flag(Map.of("v", value), "v", true);
				yield () -> Minecraft.getInstance().debugEntries.setStatus(
						net.minecraft.client.gui.components.debug.DebugScreenEntries.THREE_DIMENSIONAL_CROSSHAIR,
						on ? net.minecraft.client.gui.components.debug.DebugScreenEntryStatus.ALWAYS_ON
								: net.minecraft.client.gui.components.debug.DebugScreenEntryStatus.IN_OVERLAY);
			}
			case "vsync", "fullscreen", "pause" -> {
				boolean on = flag(Map.of("v", value), "v", true);
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
				boolean on = flag(Map.of("v", value), "v", true);
				yield () -> DebugSettings.cloudMotion = on;
			}
			case "particles_fg_replay" -> {
				boolean on = flag(Map.of("v", value), "v", true);
				yield () -> DebugSettings.replayFgParticles = on;
			}
			case "particles_defer" -> {
				boolean on = flag(Map.of("v", value), "v", true);
				yield () -> DebugSettings.deferParticles = on;
			}
			case "improved_transparency" -> {
				boolean on = flag(Map.of("v", value), "v", true);
				yield () -> Minecraft.getInstance().options.improvedTransparency().set(on);
			}
			case "crosshair_defer" -> {
				boolean on = flag(Map.of("v", value), "v", true);
				yield () -> DebugSettings.deferCrosshair = on;
			}
			case "perf_uniforms", "perf_depth" -> {
				boolean on = flag(Map.of("v", value), "v", true);
				yield () -> {
					switch (key) {
						case "perf_uniforms" -> DebugSettings.fastUniforms = on;
						case "perf_depth" -> DebugSettings.directDepth = on;
					}
				};
			}
			case "dlss_preset" -> {
				int preset = Integer.parseInt(value);
				if (preset != -1 && preset != 5 && preset != 6 && preset != 10 && preset != 11 && preset != 12 && preset != 13) throw new BadRequest("Preset must be -1 (quality-aware mapping), 5 (E), 6 (F), 10 (J), 11 (K), 12 (L) or 13 (M)");
				yield () -> DebugSettings.dlssPreset = preset;
			}
			case "mip_bias_offset" -> {
				float offset;
				try { offset = Float.parseFloat(value.trim()); } catch (NumberFormatException e) { throw new BadRequest("'mip_bias_offset' must be a number"); }
				if (!Float.isFinite(offset) || offset < -4.0f || offset > 4.0f) throw new BadRequest("mip_bias_offset must be finite within -4..4 (+1 reproduces the pre-baseline bias)");
				yield () -> DebugSettings.setMipBiasOffset(offset);
			}
			case "pack_shadow_cap" -> {
				// "<resolution>,<distance>"; 0 keeps the pack's value. Recompiles the pack so the options apply.
				String[] parts = value.trim().split(",");
				if (parts.length != 2) throw new BadRequest("pack_shadow_cap must be <resolution>,<distance> (0 = pack value)");
				int resolution, distance;
				try { resolution = Integer.parseInt(parts[0].trim()); distance = Integer.parseInt(parts[1].trim()); }
				catch (NumberFormatException e) { throw new BadRequest("pack_shadow_cap values must be whole numbers"); }
				if (resolution < 0 || resolution > 8192 || distance < 0 || distance > 1024) throw new BadRequest("pack_shadow_cap range is 0..8192,0..1024");
				int r = resolution, d = distance;
				yield () -> {
					com.bambooz.reflexium.config.ReflexiumConfig.get().setPackShadowCaps(r, d);
					com.bambooz.reflexium.compat.IrisCompat.reloadForOptionChange();
				};
			}
			case "hand_motion" -> {
				boolean on = flag(Map.of("v", value), "v", true);
				yield () -> DebugSettings.setHandMotion(on);
			}
			case "pack_taa" -> {
				boolean retained = value.trim().equalsIgnoreCase("retain") || flag(Map.of("v", value.equals("retain") ? "1" : value.equals("off") ? "0" : value), "v", true);
				yield () -> com.bambooz.reflexium.compat.VitrailCompat.debugSetPackTaa(retained);
			}
			case "pack_resolve" -> {
				boolean bypass = flag(Map.of("v", value), "v", true);
				yield () -> com.bambooz.reflexium.compat.VitrailCompat.debugSetPackResolveBypass(bypass);
			}
			case "pack_filters" -> {
				boolean retained = flag(Map.of("v", value), "v", true);
				yield () -> com.bambooz.reflexium.compat.VitrailCompat.debugSetPackPreFilters(retained);
			}
			case "shaders" -> {
				boolean on = flag(Map.of("v", value), "v", true);
				yield () -> {
					if (com.bambooz.reflexium.compat.IrisCompat.isLoaded()) com.bambooz.reflexium.compat.IrisCompat.debugSetPackEnabled(on);
					else com.bambooz.reflexium.compat.VitrailCompat.debugSetPackEnabled(on);
				};
			}
			case "reflex_trace" -> {
				int frames = Integer.parseInt(value);
				if (frames < 0 || frames > 32) throw new BadRequest("Reflex trace must be 0..32 frames");
				yield () -> com.bambooz.reflexium.vulkan.FrameTrace.arm(frames);
			}
			case "reflex_submission" -> {
				boolean enabled = flag(Map.of("v", value), "v", true);
				yield () -> DebugSettings.reflexSubmission = enabled;
			}
			case "reflex_interval_us" -> {
				int interval = Integer.parseInt(value);
				if (interval < -1 || interval > 1_000_000) throw new BadRequest("Reflex interval must be -1..1000000 us");
				yield () -> DebugSettings.reflexIntervalUs = interval;
			}
			case "reflex_mode" -> {
				var mode = parseEnum(com.bambooz.reflexium.reflex.ReflexMode.class, value);
				yield () -> com.bambooz.reflexium.reflex.ReflexController.get().setMode(mode);
			}
			case "smart_reflex" -> {
				boolean enabled = flag(Map.of("v", value), "v", true);
				yield () -> ReflexiumConfig.get().setSmartReflex(enabled);
			}
			case "frame_generation" -> {
				FrameGeneration mode = parseEnum(FrameGeneration.class, value);
				yield () -> { ReflexiumConfig.get().setFrameGeneration(mode); FrameGenerationRenderer.get().retry(); };
			}
			case "fg_force_multiplier" -> {
	boolean enabled = flag(Map.of("v", value), "v", false);
	yield () -> { ReflexiumConfig.get().setFgForceMultiplier(enabled); FrameGenerationRenderer.get().retry(); };
	}
	case "fg_multiplier" -> {
				int multiplier = "auto".equalsIgnoreCase(value) ? 0 : integer(Map.of("v", value), "v", 0);
				int maximum = Math.max(2, FrameGenerationRenderer.get().maximumMultiplier());
				if (multiplier != 0 && (multiplier < 2 || multiplier > maximum)) throw new BadRequest("FG multiplier must be auto or 2.." + maximum + " on this GPU");
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
				boolean on = flag(Map.of("v", value), "v", true);
				yield () -> DebugSettings.clearFoliageSurface = on;
			}
			case "foliage_depth_debug" -> {
				boolean on = flag(Map.of("v", value), "v", false);
				yield () -> DebugSettings.foliageDepthDebug = on;
			}
			case "mv_debug" -> {
				boolean on = flag(Map.of("v", value), "v", false);
				yield () -> DebugSettings.setMotionVectorView(on);
			}
			case "jitter" -> {
				boolean on = flag(Map.of("v", value), "v", true);
				yield () -> DebugSettings.setJitterEnabled(on);
			}
			case "object_motion" -> {
				boolean on = flag(Map.of("v", value), "v", true);
				yield () -> DebugSettings.setObjectMotion(on);
			}
			case "object_static" -> {
				boolean on = flag(Map.of("v", value), "v", false);
				yield () -> DebugSettings.setDrawStaticObjects(on);
			}
			default -> throw new BadRequest("unknown key '" + key
					+ "', expected upscaler|quality|dlss_model|fsr_sharpness|frame_generation|fg_multiplier|fg_force_multiplier|jitter_sign_x|jitter_sign_y|jitter_sign|mv_debug|jitter|object_motion|object_static|dlss_preset|mip_bias_offset|hand_motion|pack_shadow_cap|shaders|pack_taa|pack_resolve|pack_filters");
		};
		return call(() -> {
			apply.run();
			UpscalingRenderer.get().onModeChanged();
			return statusNow();
		});
	}

	private static float sign(String value) {
		String text = value.trim().toLowerCase(Locale.ROOT);
		if (text.equals("default")) {
			return Float.NaN;
		}
		try {
			float parsed = Float.parseFloat(text);
			if (parsed != 1.0f && parsed != -1.0f) {
				throw new BadRequest("jitter sign must be 1 or -1 (or 'default'), got '" + value + "'");
			}
			return parsed;
		} catch (NumberFormatException e) {
			throw new BadRequest("jitter sign must be 1 or -1 (or 'default'), got '" + value + "'");
		}
	}

	private static Path resolve(Minecraft mc, String raw) {
		Path path = Paths.get(raw);
		return path.isAbsolute() ? path : mc.gameDirectory.toPath().resolve(path);
	}

	private static String stamp() {
		return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
	}

	private static JsonObject screenshot(Map<String, String> q) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		String raw = q.get("path");
		Path file = resolve(mc, raw == null || raw.isBlank() ? "screenshots/rx_" + stamp() + ".png" : raw);
		boolean gui = flag(q, "gui", false);
		boolean hiddenByUs = hideHud(gui);
		try {
			return capture(file, integer(q, "fg_index", -1));
		} finally {
			restoreHud(hiddenByUs);
		}
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

	/** Reads back the main render target (display resolution, after upscaling) and writes a PNG. */
	private static JsonObject capture(Path file) throws Exception {
		return capture(file, -1);
	}

	private static JsonObject capture(Path file, int generatedIndex) throws Exception {
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
				m.add("camera", cameraJson(mc.player));
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
			img.writeToFile(file);
			meta.addProperty("width", img.getWidth());
			meta.addProperty("height", img.getHeight());
		}
		meta.addProperty("path", file.toAbsolutePath().toString());
		return meta;
	}

	private static JsonObject camera(Map<String, String> q) throws Exception {
		requireWorld();
		double[] now = call(() -> {
			LocalPlayer p = Minecraft.getInstance().player;
			return new double[] {p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()};
		});
		double x = number(q, "x", now[0]);
		double y = number(q, "y", now[1]);
		double z = number(q, "z", now[2]);
		float yaw = (float) number(q, "yaw", now[3]);
		float pitch = (float) Math.clamp(number(q, "pitch", now[4]), -90.0, 90.0);
		call(() -> {
			Minecraft mc = Minecraft.getInstance();
			IntegratedServer server = mc.getSingleplayerServer();
			LocalPlayer local = mc.player;
			if (server == null) {
				local.setPos(x, y, z);
				local.setYRot(yaw);
				local.setXRot(pitch);
				return null;
			}
			UUID id = local.getUUID();
			server.execute(() -> {
				ServerPlayer player = server.getPlayerList().getPlayer(id);
				if (player != null) {
					player.teleportTo(player.level(), x, y, z, Set.of(), yaw, pitch, true);
				}
			});
			return null;
		});
		boolean arrived;
		try {
			waitUntil(() -> {
				LocalPlayer p = Minecraft.getInstance().player;
				return p != null && p.distanceToSqr(x, y, z) < 0.25 * 0.25
						&& Math.abs(Math.floorMod((int) Math.round(p.getYRot() - yaw) + 180, 360) - 180) <= 1;
			}, 5_000);
			arrived = true;
		} catch (TimeoutException e) {
			arrived = false;
		}
		JsonObject result = call(DebugServer::statusNow);
		result.addProperty("arrived", arrived);
		return result;
	}

	private static JsonObject script(Map<String, String> q, boolean move) throws Exception {
		requireWorld();
		int ticks = integer(q, "ticks", 20);
		if (ticks < 1 || ticks > 20_000) {
			throw new BadRequest("'ticks' must be 1..20000");
		}
		double dx = move ? number(q, "dx", 0) : 0;
		double dz = move ? number(q, "dz", 0) : 0;
		float dyaw = move ? 0 : (float) number(q, "dyaw", 0);
		float dpitch = move ? 0 : (float) number(q, "dpitch", 0);
		boolean async = flag(q, "async", false);
		JsonObject before = call(DebugServer::statusNow);
		CompletableFuture<Void> done = call(() -> DebugMotion.start(dx, dz, dyaw, dpitch, ticks));
		if (!async) {
			done.get(ticks * 200L + 15_000L, TimeUnit.MILLISECONDS);
		}
		JsonObject result = call(DebugServer::statusNow);
		result.add("camera_before", before.get("camera"));
		result.addProperty("finished", !async);
		return result;
	}

	private static JsonObject captureSequence(Map<String, String> q) throws Exception {
		requireWorld();
		Minecraft mc = Minecraft.getInstance();
		int count = integer(q, "count", 8);
		int interval = integer(q, "interval_ms", 100);
		if (count < 1 || count > 1000) {
			throw new BadRequest("'count' must be 1..1000");
		}
		if (interval < 0) {
			throw new BadRequest("'interval_ms' must be >= 0");
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
				JsonObject meta = capture(file);
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

	private static JsonObject join(Map<String, String> q) throws Exception {
		String world = required(q, "world");
		int timeoutSeconds = integer(q, "timeout_s", 240);
		long timeoutMs = timeoutSeconds * 1000L;
		Boolean exists = call(() -> Minecraft.getInstance().getLevelSource().levelExists(world));
		if (!exists) {
			throw new BadRequest("no save folder named '" + world + "' in saves/ (use the folder name)");
		}
		Boolean inWorld = call(() -> Minecraft.getInstance().level != null);
		if (inWorld) {
			throw new BadRequest("already in a world (use /quit first)");
		}
		// The title screen means startup and the resource reload overlay are done.
		waitUntil(() -> Minecraft.getInstance().gui.screen() instanceof TitleScreen, timeoutMs);
		call(() -> {
			Minecraft mc = Minecraft.getInstance();
			mc.createWorldOpenFlows().openWorld(world, () -> { });
			return null;
		});
		int[] stable = {0};
		waitUntil(() -> {
			Minecraft mc = Minecraft.getInstance();
			boolean ready = mc.level != null && mc.player != null && mc.gui.screen() == null
					&& mc.levelRenderer.hasRenderedAllSections();
			stable[0] = ready ? stable[0] + 1 : 0;
			return stable[0] >= 8;
		}, timeoutMs);
		return call(DebugServer::statusNow);
	}

	private static JsonObject quit(Map<String, String> q) throws Exception {
		boolean exit = flag(q, "exit", false);
		if (exit) {
			call(() -> {
				Minecraft.getInstance().stop();
				return null;
			});
			JsonObject o = new JsonObject();
			o.addProperty("exiting", true);
			return o;
		}
		Boolean inWorld = call(() -> Minecraft.getInstance().level != null);
		if (!inWorld) {
			throw new BadRequest("not in a world");
		}
		call(() -> {
			Minecraft.getInstance().disconnectFromWorld(Component.empty());
			return null;
		});
		waitUntil(() -> {
			Minecraft mc = Minecraft.getInstance();
			return mc.level == null && mc.gui.screen() instanceof TitleScreen;
		}, 60_000);
		return call(DebugServer::statusNow);
	}
}
