package com.bambooz.reflexium.debug;

import com.google.gson.JsonObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.Arrays;

/** Opt-in render-thread timings. Query results are read only after availability, never with WAIT. */
public final class FrameProfiler {
	public static final int DEPTH = 0, MOTION = 1, OBJECTS = 2, EVALUATE = 3, OUTLINE_DEPTH = 4,
			OUTLINE = 5, BARRIER_BEFORE = 6, BARRIER_AFTER = 7, REACTIVE = 8, OPAQUE_COPY = 9,
			PACK_HAND = 10, REFLEX_SLEEP = 11, SUBMIT = 12, FENCE = 13, ACQUIRE = 14, PRESENT = 15, WORLD = 16, QUEUE = 17,
			REFLEX_DRIVER = 18, REFLEX_WAIT = 19, FENCE_POLL = 20, POOL_RESET = 21;
	private static final String[] NAMES = {"depth_copy", "camera_motion", "object_motion", "evaluate",
			"outline_depth", "outline", "barrier_before", "barrier_after", "reactive", "opaque_copy",
			"pack_hand", "reflex_sleep", "submit_including_wait", "submit_fence_wait", "acquire", "present", "world_including_upscaling", "queue_submit",
			"reflex_driver_call", "reflex_semaphore_wait", "fence_poll", "command_pool_reset"};
	private static final int SLOTS = 16, QUERIES = 64;
	private static final long[] cpu = new long[NAMES.length], counts = new long[NAMES.length],
			gpu = new long[NAMES.length], gpuCounts = new long[NAMES.length];
	private static final int[][] labels = new int[SLOTS][QUERIES / 2];
	private static final int[] used = new int[SLOTS];
	private static final int[] generations = new int[SLOTS];
	private static int generation;
	private static VkDevice device;
	private static long pool;
	private static float period;
	private static int slot = -1, next;
	private static boolean enabled;
	private static long skipped;

	private FrameProfiler() {}

	public static void init(VkDevice value, VkPhysicalDevice physical) {
		device = value;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
			VK10.vkGetPhysicalDeviceProperties(physical, properties);
			period = properties.limits().timestampPeriod();
			LongBuffer out = stack.mallocLong(1);
			int result = VK10.vkCreateQueryPool(device, VkQueryPoolCreateInfo.calloc(stack).sType$Default()
					.queryType(VK10.VK_QUERY_TYPE_TIMESTAMP).queryCount(SLOTS * QUERIES), null, out);
			if (result != VK10.VK_SUCCESS) throw new IllegalStateException("timestamp query pool: " + result);
			pool = out.get(0);
		}
	}

	public static void close() {
		if (pool != 0) VK10.vkDestroyQueryPool(device, pool, null);
		pool = 0;
		device = null;
		slot = -1;
		Arrays.fill(used, 0);
	}

	public static void beginFrame() {
		slot = -1;
		if (!enabled || pool == 0) return;
		int frame = next++;
		if (frame % 16 != 0) return;
		int candidate = (frame / 16) % SLOTS;
		if (!collect(candidate)) { skipped++; return; }
		VK12.vkResetQueryPool(device, pool, candidate * QUERIES, QUERIES);
		used[candidate] = 0;
		generations[candidate] = generation;
		slot = candidate;
	}

	private static boolean collect(int s) {
		if (used[s] == 0) return true;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer data = stack.callocLong(used[s] * 2);
			int result = VK10.vkGetQueryPoolResults(device, pool, s * QUERIES, used[s], data, 16,
					VK10.VK_QUERY_RESULT_64_BIT | VK10.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
			if (result == VK10.VK_NOT_READY) return false;
			if (result != VK10.VK_SUCCESS) throw new IllegalStateException("timestamp query read: " + result);
			for (int i = 0; generations[s] == generation && i < used[s]; i += 2) {
				if (data.get(i * 2 + 1) == 0 || data.get(i * 2 + 3) == 0) return false;
			}
			for (int i = 0; generations[s] == generation && i < used[s]; i += 2) {
				int label = labels[s][i / 2];
				gpu[label] += (long) ((data.get(i * 2 + 2) - data.get(i * 2)) * (double) period);
				gpuCounts[label]++;
			}
			used[s] = 0;
			return true;
		}
	}

	public static long start(int label, VkCommandBuffer commands) {
		if (!enabled) return 0;
		if (commands != null && slot >= 0 && used[slot] + 2 <= QUERIES) {
			int query = used[slot];
			labels[slot][query / 2] = label;
			VK10.vkCmdWriteTimestamp(commands, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool, slot * QUERIES + query);
			used[slot] += 2;
			// High bits retain the query pair so nested CPU scopes cannot corrupt its end index.
			return (System.nanoTime() & 0x0000ffffffffffffL) | ((long) (query + 1) << 48);
		}
		return System.nanoTime() & 0x0000ffffffffffffL;
	}

	public static void end(int label, long start, VkCommandBuffer commands) {
		if (start == 0) return;
		cpu[label] += ((System.nanoTime() & 0x0000ffffffffffffL) - (start & 0x0000ffffffffffffL)) & 0x0000ffffffffffffL;
		counts[label]++;
		int query = (int) (start >>> 48) - 1;
		if (commands != null && query >= 0) VK10.vkCmdWriteTimestamp(commands,
				VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool, slot * QUERIES + query + 1);
	}

	public static JsonObject command(String action) {
		if (action.equals("on") || action.equals("reset")) {
			generation++;
			// Discard outstanding samples by leaving the pool alive and draining before reuse.
			Arrays.fill(cpu, 0); Arrays.fill(counts, 0); Arrays.fill(gpu, 0); Arrays.fill(gpuCounts, 0);
			skipped = 0;
			enabled = true;
		} else if (action.equals("off")) enabled = false;
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("enabled", enabled);
		result.addProperty("skipped_gpu_frames", skipped);
		JsonObject spans = new JsonObject();
		for (int i = 0; i < NAMES.length; i++) {
			JsonObject span = new JsonObject();
			span.addProperty("cpu_samples", counts[i]);
			span.addProperty("cpu_us", counts[i] == 0 ? 0 : cpu[i] / (counts[i] * 1000.0));
			span.addProperty("gpu_samples", gpuCounts[i]);
			span.addProperty("gpu_us", gpuCounts[i] == 0 ? 0 : gpu[i] / (gpuCounts[i] * 1000.0));
			spans.add(NAMES[i], span);
		}
		result.add("spans", spans);
		return result;
	}
}
