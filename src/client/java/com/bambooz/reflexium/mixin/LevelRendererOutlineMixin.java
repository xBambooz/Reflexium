package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.upscale.UpscalingRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererOutlineMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void reflexium$recordDrawnView(GraphicsResourceAllocator allocator, boolean outlines, CameraRenderState camera,
			GpuBufferSlice fog, Vector4f fogColor, boolean sky, boolean clouds, CallbackInfo ci) {
		UpscalingRenderer.get().recordLevelDrawView(camera);
	}

	@ModifyVariable(method = "submitHitOutline", at = @At("HEAD"), argsOnly = true)
	private float reflexium$scaleOutlineWidth(float width) {
		return UpscalingRenderer.get().scaleLineWidth(width);
	}
}
