package com.bambooz.reflexium.dlss;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.LongBuffer;

/**
 * DLSS writes its output through a storage image, and Minecraft's texture API has no storage usage,
 * so the output is a raw VkImage that gets copied into Minecraft's display-size target. Kept in
 * VK_IMAGE_LAYOUT_GENERAL like every image in the 26.3 backend.
 */
final class OutputImage {
	static final int FORMAT = VK10.VK_FORMAT_R8G8B8A8_UNORM;

	final long image;
	final long view;
	final int width;
	final int height;
	private final long allocation;
	private final VulkanDevice device;

	OutputImage(VulkanDevice device, VkCommandBuffer commandBuffer, int width, int height) {
		this.device = device;
		this.width = width;
		this.height = height;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default()
					.imageType(VK10.VK_IMAGE_TYPE_2D)
					.format(FORMAT)
					.mipLevels(1)
					.arrayLayers(1)
					.samples(VK10.VK_SAMPLE_COUNT_1_BIT)
					.tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
					.usage(VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT
							| VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
					.sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
					.initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
			info.extent().set(width, height, 1);
			VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
			LongBuffer pImage = stack.callocLong(1);
			PointerBuffer pAllocation = stack.callocPointer(1);
			check(Vma.vmaCreateImage(device.vma(), info, allocInfo, pImage, pAllocation, null), "vmaCreateImage");
			image = pImage.get(0);
			allocation = pAllocation.get(0);

			VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
					.image(image)
					.viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
					.format(FORMAT);
			viewInfo.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
			LongBuffer pView = stack.callocLong(1);
			int viewResult = VK10.vkCreateImageView(device.vkDevice(), viewInfo, null, pView);
			if (viewResult != VK10.VK_SUCCESS) {
				Vma.vmaDestroyImage(device.vma(), image, allocation);
				check(viewResult, "vkCreateImageView");
			}
			view = pView.get(0);

			VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
					.oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)
					.newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
					.srcAccessMask(0)
					.dstAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT)
					.srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
					.dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
					.image(image);
			barrier.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
			VK10.vkCmdPipelineBarrier(commandBuffer, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
					VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, barrier);
		}
	}

	NgxBridge.Image asNgx() {
		return new NgxBridge.Image(image, view, FORMAT, width, height, VK10.VK_IMAGE_ASPECT_COLOR_BIT);
	}

	/** Caller guarantees the GPU no longer uses the image (device idle). */
	void destroy() {
		VK10.vkDestroyImageView(device.vkDevice(), view, null);
		Vma.vmaDestroyImage(device.vma(), image, allocation);
	}

	private static void check(int result, String call) {
		if (result != VK10.VK_SUCCESS) {
			throw new IllegalStateException(call + " failed: " + result);
		}
	}
}
