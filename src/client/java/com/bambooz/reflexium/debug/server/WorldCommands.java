package com.bambooz.reflexium.debug.server;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.bambooz.reflexium.debug.server.DebugHttp.flag;
import static com.bambooz.reflexium.debug.server.DebugHttp.integer;
import static com.bambooz.reflexium.debug.server.DebugHttp.number;
import static com.bambooz.reflexium.debug.server.DebugHttp.required;
import static com.bambooz.reflexium.debug.server.RenderThread.call;
import static com.bambooz.reflexium.debug.server.RenderThread.requireWorld;
import static com.bambooz.reflexium.debug.server.RenderThread.waitUntil;

/** Joining and leaving worlds, teleporting the camera and scripted motion. */
final class WorldCommands {
	private WorldCommands() {
	}

	static JsonObject camera(Map<String, String> q) throws Exception {
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
		JsonObject result = call(StatusCommands::statusNow);
		result.addProperty("arrived", arrived);
		return result;
	}

	/** {@code /move} ({@code move = true}) or {@code /turn}: a total displacement or rotation spread over ticks. */
	static JsonObject script(Map<String, String> q, boolean move) throws Exception {
		requireWorld();
		int ticks = integer(q, "ticks", 20);
		if (ticks < 1 || ticks > 20_000) {
			throw new DebugHttp.BadRequest("'ticks' must be 1..20000");
		}
		double dx = move ? number(q, "dx", 0) : 0;
		double dz = move ? number(q, "dz", 0) : 0;
		float dyaw = move ? 0 : (float) number(q, "dyaw", 0);
		float dpitch = move ? 0 : (float) number(q, "dpitch", 0);
		boolean async = flag(q, "async", false);
		JsonObject before = call(StatusCommands::statusNow);
		CompletableFuture<Void> done = call(() -> DebugMotion.start(dx, dz, dyaw, dpitch, ticks));
		if (!async) {
			done.get(ticks * 200L + 15_000L, TimeUnit.MILLISECONDS);
		}
		JsonObject result = call(StatusCommands::statusNow);
		result.add("camera_before", before.get("camera"));
		result.addProperty("finished", !async);
		return result;
	}

	static JsonObject join(Map<String, String> q) throws Exception {
		String world = required(q, "world");
		int timeoutSeconds = integer(q, "timeout_s", 240);
		long timeoutMs = timeoutSeconds * 1000L;
		Boolean exists = call(() -> Minecraft.getInstance().getLevelSource().levelExists(world));
		if (!exists) {
			throw new DebugHttp.BadRequest("no save folder named '" + world + "' in saves/ (use the folder name)");
		}
		Boolean inWorld = call(() -> Minecraft.getInstance().level != null);
		if (inWorld) {
			throw new DebugHttp.BadRequest("already in a world (use /quit first)");
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
		return call(StatusCommands::statusNow);
	}

	static JsonObject quit(Map<String, String> q) throws Exception {
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
			throw new DebugHttp.BadRequest("not in a world");
		}
		call(() -> {
			Minecraft.getInstance().disconnectFromWorld(Component.empty());
			return null;
		});
		waitUntil(() -> {
			Minecraft mc = Minecraft.getInstance();
			return mc.level == null && mc.gui.screen() instanceof TitleScreen;
		}, 60_000);
		return call(StatusCommands::statusNow);
	}
}
