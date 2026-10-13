package com.bambooz.reflexium.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The frame's open command buffer, so NGX records in order with Minecraft's own commands. */
@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderAccessor {
	@Invoker("commandBuffer")
	VkCommandBuffer reflexium$commandBuffer();
}
