package com.bambooz.reflexium.dlss;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * One opt-in capture of the upscaler's actual input and output from a single evaluated frame. The HTTP
 * thread arms it and waits on the futures. The render thread fills it during that frame's evaluation, so
 * input color (render resolution), output color (display resolution), the encoded motion vectors and the
 * encoded scene depth always belong to the same frame as {@link #metadata}.
 */
public final class UpscaleCapture {
	public final CompletableFuture<NativeImage> input = new CompletableFuture<>();
	public final CompletableFuture<NativeImage> output = new CompletableFuture<>();
	public final CompletableFuture<NativeImage> motion = new CompletableFuture<>();
	public final CompletableFuture<NativeImage> depth = new CompletableFuture<>();
	public final CompletableFuture<JsonObject> metadata = new CompletableFuture<>();
	final boolean extras;
	private volatile boolean cancelled;

	UpscaleCapture(boolean extras) {
		this.extras = extras;
	}

	public boolean cancelled() {
		return cancelled;
	}

	/** Drops the request. Images that arrive afterwards are closed by the render thread callbacks. */
	public synchronized void cancel() {
		if (cancelled) return;
		cancelled = true;
		// The request owns every delivered image, including completed futures the HTTP handler never read.
		for (CompletableFuture<NativeImage> future : List.of(input, output, motion, depth)) {
			future.thenAccept(NativeImage::close);
			future.cancel(false);
		}
		metadata.cancel(false);
	}

	/** Completes a future with an image, or closes the image when the request was cancelled meanwhile. */
	void deliver(CompletableFuture<NativeImage> future, NativeImage image) {
		if (cancelled || !future.complete(image)) {
			image.close();
		}
	}
}
