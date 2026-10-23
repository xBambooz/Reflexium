package com.bambooz.reflexium.debug.server;

import com.bambooz.reflexium.debug.DebugSettings;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Runs debug commands on the render thread and waits for game state from the HTTP threads. */
final class RenderThread {
	private static final long CALL_TIMEOUT_MS = 30_000;

	private RenderThread() {
	}

	static <T> T call(Supplier<T> task) throws Exception {
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

	static void waitUntil(BooleanSupplier condition, long timeoutMs) throws Exception {
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

	static void waitFrames(int count) throws Exception {
		long target = DebugSettings.frames() + count;
		long deadline = System.nanoTime() + 5_000_000_000L;
		while (DebugSettings.frames() < target && System.nanoTime() < deadline) {
			Thread.sleep(5);
		}
	}

	static void requireWorld() throws Exception {
		Boolean loaded = call(() -> {
			Minecraft mc = Minecraft.getInstance();
			return mc.level != null && mc.player != null;
		});
		if (!loaded) {
			throw new DebugHttp.BadRequest("no world loaded (use /join?world=<folder name>)");
		}
	}
}
