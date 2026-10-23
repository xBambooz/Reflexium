package com.bambooz.reflexium.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.ObjLongConsumer;

/**
 * Separate dev jar. Controls BM's normal capture path without changing its focus flags or metrics, and records
 * Reflexium counters at BM's own measured-frame decisions: a pass is the difference between the snapshot at its
 * first measured frame start and the snapshot at its last measured frame start, which covers exactly the frames
 * whose intervals BM recorded. No HTTP polling or logging during a pass; tuples are read after the capture.
 */
public final class BmDriver implements ClientModInitializer {
	static final String SCHEMA = "bm-pass-v1";
	private static final int CHECKPOINT = 64;
	private static String[] names;
	private static Consumer<long[]> reader;
	private static String telemetry;
	private static String boundaries = "unavailable";
	private static String label = "";
	private static int passIndex;
	private static long[] first, last;
	private static long lastFrame = -1;
	private static final List<long[]> checkpoints = new ArrayList<>();
	private static final List<JsonObject> passes = new ArrayList<>();

	@Override public void onInitializeClient() {
		try {
			Class<?> api = Class.forName("com.bambooz.agentharness.api.AgentHarness");
			if (!(boolean) api.getMethod("isActive").invoke(null)) return;
			chooseReader();
			try {
				ObjLongConsumer<String> listener = BmDriver::onBoundary;
				Class.forName("com.benchmarkmod.client.capture.FrameCapture").getMethod("setBoundaryListener", ObjLongConsumer.class).invoke(null, listener);
				boundaries = "bm FrameCapture";
			} catch (NoSuchMethodException e) { boundaries = "unavailable (Benchmark Mod lacks setBoundaryListener)"; }
			Class<?> handler = Class.forName("com.bambooz.agentharness.api.EndpointHandler");
			Object endpoint = Proxy.newProxyInstance(handler.getClassLoader(), new Class<?>[]{handler}, (proxy, method, args) -> {
				if (!method.getName().equals("handle")) return method.getName().equals("toString") ? "Benchmark Mod driver" : null;
				@SuppressWarnings("unchecked") Map<String, String> params = (Map<String, String>) args[0].getClass().getMethod("params").invoke(args[0]);
				CompletableFuture<JsonObject> result = new CompletableFuture<>();
				Minecraft.getInstance().execute(() -> {
					try {
						Class<?> controller = Class.forName("com.benchmarkmod.client.run.RunController");
						JsonObject response = new JsonObject();
						if (params.containsKey("scene")) {
							if (Minecraft.getInstance().level != null) throw new IllegalStateException("Start BM from a menu");
							passes.clear(); passIndex = 0; first = null; label = params.getOrDefault("label", "");
							controller.getMethod("start", String.class, String.class).invoke(null, params.get("scene"), label);
						}
						if (params.containsKey("passes")) {
							JsonArray taken = new JsonArray();
							passes.forEach(taken::add);
							passes.clear();
							response.add("passes", taken);
						}
						response.addProperty("ok", true);
						response.addProperty("data_directory", Class.forName("com.benchmarkmod.util.Paths").getMethod("dataRoot").invoke(null).toString());
						response.addProperty("background_capture", true);
						response.addProperty("pass_schema", SCHEMA);
						response.addProperty("pass_telemetry", telemetry);
						response.addProperty("pass_boundaries", boundaries);
						result.complete(response);
					} catch (Exception e) { result.completeExceptionally(e); }
				});
				return result.get(30, TimeUnit.SECONDS);
			});
			api.getMethod("register", String.class, String.class, handler).invoke(null, "bm", "Start Benchmark Mod capture (focus flags preserved)", endpoint);
		} catch (ReflectiveOperationException e) { throw new IllegalStateException("BM driver requires the active agent harness and Benchmark Mod", e); }
	}

	/** Current builds expose one allocation-free snapshot; older candidate jars only have their public FG counters. */
	private static void chooseReader() {
		try {
			Class<?> counters = Class.forName("com.bambooz.reflexium.debug.MeasurementCounters");
			names = (String[]) counters.getField("NAMES").get(null);
			MethodHandle read = MethodHandles.publicLookup().findStatic(counters, "read", MethodType.methodType(void.class, long[].class));
			reader = out -> { try { read.invokeExact(out); } catch (Throwable t) { throw new IllegalStateException(t); } };
			telemetry = "full " + counters.getField("SCHEMA").get(null);
			return;
		} catch (ReflectiveOperationException | LinkageError ignored) { }
		String[][] legacy = {{"app_frame_id", "com.bambooz.reflexium.reflex.ReflexController", "lastFrameId"},
				{"fg_evaluated_frames", "com.bambooz.reflexium.framegen.FrameGenerationRenderer", "evaluatedFrames"},
				{"fg_generated_presents", "com.bambooz.reflexium.framegen.FrameGenerationRenderer", "generatedPresents"},
				{"fg_real_presents", "com.bambooz.reflexium.framegen.FrameGenerationRenderer", "realPresents"}};
		names = new String[legacy.length];
		MethodHandle[] getters = new MethodHandle[legacy.length];
		for (int i = 0; i < legacy.length; i++) {
			names[i] = legacy[i][0];
			try {
				Class<?> type = Class.forName(legacy[i][1]);
				Object instance = type.getMethod("get").invoke(null);
				getters[i] = MethodHandles.publicLookup().findVirtual(type, legacy[i][2], MethodType.methodType(long.class)).bindTo(instance);
			} catch (ReflectiveOperationException | LinkageError e) { getters[i] = null; }
		}
		reader = out -> {
			for (int i = 0; i < getters.length; i++) {
				try { out[i] = getters[i] == null ? -1 : (long) getters[i].invokeExact(); } catch (Throwable t) { out[i] = -1; }
			}
		};
		telemetry = "legacy public FG counters";
	}

	/** Render thread, inside BM's frame-start decision. */
	private static void onBoundary(String event, long intervals) {
		if (event.equals("frame")) {
			if (intervals == 0) {
				first = new long[names.length]; last = new long[names.length];
				reader.accept(first);
				System.arraycopy(first, 0, last, 0, first.length);
				checkpoints.clear();
				checkpoints.add(withIndex(first, 0));
			} else if (first != null) {
				reader.accept(last);
				if (intervals % CHECKPOINT == 0) checkpoints.add(withIndex(last, intervals));
			}
			lastFrame = intervals;
		} else if (event.equals("end")) {
			passes.add(tuple(intervals));
			first = null; lastFrame = -1; passIndex++;
		}
	}

	private static long[] withIndex(long[] values, long index) {
		long[] copy = Arrays.copyOf(values, values.length + 1);
		copy[values.length] = index;
		return copy;
	}

	private static JsonObject tuple(long intervals) {
		JsonObject pass = new JsonObject();
		pass.addProperty("schema", SCHEMA);
		pass.addProperty("telemetry", telemetry);
		pass.addProperty("label", label);
		pass.addProperty("pass", passIndex);
		pass.addProperty("intervals", intervals);
		pass.addProperty("last_frame_index", lastFrame);
		pass.addProperty("end_phase", phase());
		boolean complete = first != null && intervals > 0 && lastFrame == intervals;
		pass.addProperty("complete", complete);
		if (first == null) return pass;
		pass.add("first", values(first));
		pass.add("last", values(last));
		pass.add("delta", delta(first, last));
		long[] middle = checkpoints.getFirst();
		for (long[] point : checkpoints) if (Math.abs(point[names.length] - intervals / 2) < Math.abs(middle[names.length] - intervals / 2)) middle = point;
		pass.addProperty("mid_interval", middle[names.length]);
		pass.add("early", delta(first, middle));
		pass.add("late", delta(middle, last));
		return pass;
	}

	private static JsonObject values(long[] snapshot) {
		JsonObject out = new JsonObject();
		for (int i = 0; i < names.length; i++) out.addProperty(names[i], snapshot[i]);  // -1: unavailable or unconfigured
		return out;
	}

	/** Cumulative fields as differences (null when the build lacks one); state fields keep their end values. */
	private static JsonObject delta(long[] from, long[] to) {
		JsonObject out = new JsonObject();
		for (int i = 0; i < names.length; i++) {
			boolean state = names[i].equals("fg_failed") || names[i].equals("fg_configured_generated") || names[i].endsWith("_mode");
			out.addProperty(names[i], state ? Long.valueOf(to[i]) : from[i] < 0 || to[i] < 0 ? null : Long.valueOf(to[i] - from[i]));
		}
		return out;
	}

	private static String phase() {
		try {
			Object state = Class.forName("com.benchmarkmod.run.RunStates").getMethod("get").invoke(null);
			return String.valueOf(state.getClass().getMethod("phase").invoke(state));
		} catch (ReflectiveOperationException | LinkageError e) { return "unknown"; }
	}
}
