package com.bambooz.reflexium.debug.server;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.compat.IrisCompat;
import com.bambooz.reflexium.debug.FrameProfiler;
import com.bambooz.reflexium.reflex.ReflexController;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static com.bambooz.reflexium.debug.server.RenderThread.call;

/**
	* Dev-only HTTP command server for tuning upscaling without clicking through menus.
	* Started only when the JVM property {@code reflexium.debugPort} is set; binds 127.0.0.1 only.
	* Every command that touches the game runs on the render thread and answers with JSON.
	* Usage: docs/DEBUGGING.md and tools/rxdbg.ps1.
	*/
public final class DebugServer {
	private static volatile boolean active;

	private DebugServer() {
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
			DebugHttp.route(server, "/status", q -> StatusCommands.status());
			DebugHttp.route(server, "/set", SetCommand::set);
			DebugHttp.route(server, "/screenshot", CaptureCommands::screenshot);
			DebugHttp.route(server, "/camera", WorldCommands::camera);
			DebugHttp.route(server, "/move", q -> WorldCommands.script(q, true));
			DebugHttp.route(server, "/turn", q -> WorldCommands.script(q, false));
			DebugHttp.route(server, "/capture_sequence", CaptureCommands::captureSequence);
			DebugHttp.route(server, "/join", WorldCommands::join);
			DebugHttp.route(server, "/quit", WorldCommands::quit);
			DebugHttp.route(server, "/", q -> help());
			server.start();
			active = true;
			ReflexiumClient.LOGGER.warn("Reflexium debug server listening on http://127.0.0.1:{}/ (dev only)", port);
		} catch (IOException e) {
			ReflexiumClient.LOGGER.error("Could not start the Reflexium debug server on port {}", port, e);
		}
	}

	/** Optional dev harness integration, loaded reflectively so release users need no test dependency. */
	public static void registerHarnessEndpoints() {
		if (!FabricLoader.getInstance().isModLoaded("agentharness")) return;
		try {
			Class<?> api = Class.forName("com.bambooz.agentharness.api.AgentHarness");
			if (!(boolean) api.getMethod("isActive").invoke(null)) return;
			Class<?> handler = Class.forName("com.bambooz.agentharness.api.EndpointHandler");
			Object endpoint = Proxy.newProxyInstance(handler.getClassLoader(), new Class<?>[] {handler}, (proxy, method, args) -> {
				if (!method.getName().equals("handle")) return method.getName().equals("toString") ? "Reflexium debug endpoint" : null;
				@SuppressWarnings("unchecked")
				Map<String, String> params = (Map<String, String>) args[0].getClass().getMethod("params").invoke(args[0]);
				if (params.containsKey("key")) return SetCommand.set(params);
				if (params.containsKey("dh_targets")) return call(IrisCompat::dhTargets);
				if (params.containsKey("frame_mark")) return StatusCommands.frameMark();
				if (params.containsKey("frame_stats_from")) return StatusCommands.frameStats(params);
				if (params.containsKey("profile")) return call(() -> FrameProfiler.command(params.get("profile")));
				if (params.containsKey("fg_capture")) return CaptureCommands.captureGeneratedBatch(params);
				if (params.containsKey("capture")) return CaptureCommands.captureUpscale(params);
				if (params.containsKey("reflex_timings")) return call(() -> ReflexController.get().timingReports());
				return StatusCommands.status();
			});
			api.getMethod("register", String.class, String.class, handler)
					.invoke(null, "reflexium", "Upscaling status and runtime settings", endpoint);
			api.getMethod("statusContributor", String.class, Supplier.class)
					.invoke(null, "reflexium", (Supplier<JsonObject>) StatusCommands::statusNow);
		} catch (ReflectiveOperationException | LinkageError e) {
			ReflexiumClient.LOGGER.warn("Minecraft agent harness integration could not register", e);
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

	private static JsonObject help() {
		JsonObject o = new JsonObject();
		JsonArray endpoints = new JsonArray();
		for (String line : new String[] {
				"/status",
				"/set?key=<" + String.join("|", SetCommand.KEYS) + ">&value=<v>",
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
}
