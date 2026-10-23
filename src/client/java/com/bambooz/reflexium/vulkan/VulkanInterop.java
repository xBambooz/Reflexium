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
}
