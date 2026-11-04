package com.bambooz.reflexium.vulkan;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkOffset3D;

/** Raw Vulkan handles behind RenderPearl objects, for work recorded by the native bridges. */
public final class VulkanInterop {
	private VulkanInterop() {
	}

	/** The Vulkan image and view behind a RenderPearl texture, as the native bridges expect them. */
	public static ImageRef image(GpuTexture texture, GpuTextureView view) {
		int aspect = texture.getFormat().hasColorAspect() ? VK10.VK_IMAGE_ASPECT_COLOR_BIT : VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
		return new ImageRef(((VulkanGpuTexture) texture).vkImage(), ((VulkanGpuTextureView) view).vkImageView(),
				VulkanConst.toVk(texture.getFormat()), texture.getWidth(0), texture.getHeight(0), aspect);
	}

	/** RenderPearl's full memory barrier, around work RenderPearl does not track. */
	public static void barrier(VkCommandBuffer commandBuffer) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VulkanCommandEncoder.memoryBarrier(commandBuffer, stack);
		}
	}

	/**
	 * Copies {@code source} into {@code destination} while mirroring the image vertically, which is how
	 * Minecraft presents: its final swapchain blit flips Y. Streamline frame generation composes directly
	 * into the presented backbuffer, so every tagged input must use that same orientation.
	 */
	public static void blitFlip(ImageRef source, ImageRef destination, VkCommandBuffer commandBuffer) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int aspect = source.aspect();
			VkImageSubresourceLayers src = VkImageSubresourceLayers.calloc(stack)
					.aspectMask(aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
			VkImageSubresourceLayers dst = VkImageSubresourceLayers.calloc(stack)
					.aspectMask(destination.aspect()).mipLevel(0).baseArrayLayer(0).layerCount(1);
			VkOffset3D.Buffer srcOffsets = VkOffset3D.calloc(2, stack);
			srcOffsets.get(0).x(0).y(0).z(0);
			srcOffsets.get(1).x(source.width()).y(source.height()).z(1);
			VkOffset3D.Buffer dstOffsets = VkOffset3D.calloc(2, stack);
			dstOffsets.get(0).x(0).y(destination.height()).z(0);
			dstOffsets.get(1).x(destination.width()).y(0).z(1);
			VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
			region.get(0).srcSubresource(src).dstSubresource(dst).srcOffsets(srcOffsets).dstOffsets(dstOffsets);
			VK10.vkCmdBlitImage(commandBuffer, source.image(), VK10.VK_IMAGE_LAYOUT_GENERAL,
					destination.image(), VK10.VK_IMAGE_LAYOUT_GENERAL, region, VK10.VK_FILTER_NEAREST);
		}
	}
}
