package com.bambooz.reflexium.debug;

public final class FrameProfilerCheck {
	public static void main(String[] args) throws Exception {
		try (var in = FrameProfilerCheck.class.getClassLoader().getResourceAsStream("com/mojang/renderpearl/backend/vulkan/VulkanGpuSurface.class")) {
			var node = new org.objectweb.asm.tree.ClassNode();
			new org.objectweb.asm.ClassReader(in.readAllBytes()).accept(node, 0);
			var blit = node.methods.stream().filter(m -> m.name.equals("blitFromTexture")).findFirst().orElseThrow();
			boolean found = false;
			for (var instruction : blit.instructions) if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
					&& call.owner.equals("org/lwjgl/vulkan/VK12") && call.name.equals("vkCmdBlitImage")) found = true;
			if (!found) throw new AssertionError("Transient surface blit profiling target changed");
		}
		FrameProfiler.command("off");
		if (FrameProfiler.enabled()) throw new AssertionError("disabled state");
		if (FrameProfiler.start(FrameProfiler.EVALUATE, null) != 0) throw new AssertionError("disabled profiler");
		FrameProfiler.command("reset");
		if (!FrameProfiler.enabled()) throw new AssertionError("enabled state");
		long start = FrameProfiler.start(FrameProfiler.EVALUATE, null);
		FrameProfiler.end(FrameProfiler.EVALUATE, start, null);
		var span = FrameProfiler.command("status").getAsJsonObject("spans").getAsJsonObject("evaluate");
		if (span.get("cpu_samples").getAsLong() != 1 || span.get("cpu_us").getAsDouble() < 0
				|| span.get("gpu_samples").getAsLong() != 0) throw new AssertionError(span);
		span = FrameProfiler.command("reset").getAsJsonObject("spans").getAsJsonObject("evaluate");
		if (span.get("cpu_samples").getAsLong() != 0) throw new AssertionError("reset counters");
		FrameProfiler.command("off");
		System.out.println("Frame profiler CPU/disabled/reset checks passed");
	}
}
