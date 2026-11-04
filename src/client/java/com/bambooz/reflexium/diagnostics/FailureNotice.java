package com.bambooz.reflexium.diagnostics;

import com.bambooz.reflexium.ReflexiumClient;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * One clear log line and one in-game chat line per failure. Messages are queued until a world exists,
 * so a failure during startup is still visible after joining. Rendering itself is never stopped here.
 */
public final class FailureNotice {
	private static final Set<String> reported = ConcurrentHashMap.newKeySet();
	private static final Queue<String> pending = new ConcurrentLinkedQueue<>();
	private static volatile String last = "";

	private FailureNotice() {
	}

	/** Vulkan's VK_ERROR_DEVICE_LOST. */
	public static boolean deviceLost(int result) {
		return result == -4;
	}

	public static void report(String message) {
		report(message, false);
	}

	public static void report(String message, boolean deviceLost) {
		if (!reported.add(message)) return;
		last = message;
		if (deviceLost) {
			ReflexiumClient.LOGGER.error("Reflexium: {}", message);
		} else {
			ReflexiumClient.LOGGER.warn("Reflexium: {}", message);
		}
		pending.add(message);
	}

	/** Called once per rendered frame. Shows queued messages only once a world and its chat exist. */
	public static void flush() {
		if (pending.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.level == null || mc.gui == null || mc.gui.hud == null) return;
		String message;
		while ((message = pending.poll()) != null) {
			try {
				mc.gui.hud.getChat().addClientSystemMessage(Component.literal("Reflexium: " + message));
			} catch (RuntimeException e) {
				ReflexiumClient.LOGGER.warn("Could not show the Reflexium failure message", e);
				return;
			}
		}
	}

	public static String last() {
		return last;
	}

	/** Test seam: message queue length. */
	public static int pendingCount() {
		return pending.size();
	}
}
