package com.bambooz.reflexium.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;

/**
 * Scripted camera motion for the debug harness: a total displacement and rotation spread evenly over N
 * client ticks, applied at the end of each tick so the renderer interpolates it smoothly.
 */
final class DebugMotion {
	private static volatile Script active;

	private DebugMotion() {
	}

	/** Render thread. Returns a future that completes when the script has run its last tick. */
	static CompletableFuture<Void> start(double dx, double dz, float dyaw, float dpitch, int ticks) {
		Script previous = active;
		if (previous != null) {
			previous.done.complete(null);
		}
		Script script = new Script(dx / ticks, dz / ticks, dyaw / ticks, dpitch / ticks, ticks);
		active = script;
		return script.done;
	}

	static boolean running() {
		return active != null;
	}

	/** Called at the end of every client tick (render thread). */
	static void onClientTick() {
		Script script = active;
		if (script == null) {
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			active = null;
			script.done.completeExceptionally(new IllegalStateException("player left the world"));
			return;
		}
		if (script.stepX != 0 || script.stepZ != 0) {
			player.move(MoverType.SELF, new Vec3(script.stepX, 0, script.stepZ));
		}
		if (script.stepYaw != 0) {
			player.setYRot(player.getYRot() + script.stepYaw);
		}
		if (script.stepPitch != 0) {
			player.setXRot(Math.clamp(player.getXRot() + script.stepPitch, -90.0f, 90.0f));
		}
		if (--script.remaining <= 0) {
			active = null;
			script.done.complete(null);
		}
	}

	private static final class Script {
		final double stepX;
		final double stepZ;
		final float stepYaw;
		final float stepPitch;
		final CompletableFuture<Void> done = new CompletableFuture<>();
		int remaining;

		Script(double stepX, double stepZ, float stepYaw, float stepPitch, int ticks) {
			this.stepX = stepX;
			this.stepZ = stepZ;
			this.stepYaw = stepYaw;
			this.stepPitch = stepPitch;
			this.remaining = ticks;
		}
	}
}
