package com.bambooz.reflexium.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets DLSS swap a render-resolution target's textures into Minecraft's main target for the world pass. */
@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {
	@Accessor("colorTexture")
	GpuTexture reflexium$colorTexture();

	@Accessor("colorTexture")
	void reflexium$setColorTexture(GpuTexture texture);

	@Accessor("colorTextureView")
	GpuTextureView reflexium$colorTextureView();

	@Accessor("colorTextureView")
	void reflexium$setColorTextureView(GpuTextureView view);

	@Accessor("depthTexture")
	GpuTexture reflexium$depthTexture();

	@Accessor("depthTexture")
	void reflexium$setDepthTexture(GpuTexture texture);

	@Accessor("depthTextureView")
	GpuTextureView reflexium$depthTextureView();

	@Accessor("depthTextureView")
	void reflexium$setDepthTextureView(GpuTextureView view);
}
