package com.bambooz.reflexium.vulkan;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A Vulkan image as the native bridges receive it. Mirrors RxImage in native/reflexium_sl.cpp and
 * native/reflexium_fsr.cpp. {@code aspect} is a VkImageAspectFlags value.
 */
public record ImageRef(long image, long view, int vkFormat, int width, int height, int aspect) {
	/** Size of one RxImage in native memory. */
	public static final long SIZE = 32;

	/** Writes this image into {@code base} at {@code offset} and returns that slice. */
	public MemorySegment write(MemorySegment base, long offset) {
		MemorySegment s = base.asSlice(offset, SIZE);
		s.set(JAVA_LONG, 0, image);
		s.set(JAVA_LONG, 8, view);
		s.set(JAVA_INT, 16, vkFormat);
		s.set(JAVA_INT, 20, width);
		s.set(JAVA_INT, 24, height);
		s.set(JAVA_INT, 28, aspect);
		return s;
	}
}
