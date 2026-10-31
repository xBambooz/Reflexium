package com.bambooz.reflexium.mixin.compat.meshelium;

import com.bambooz.reflexium.compat.MesheliumCompat;
import com.bambooz.reflexium.upscale.MaterialBinding;
import com.bambooz.reflexium.upscale.UpscalingRenderer;
import com.mojang.renderpearl.api.textures.GpuSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.deds.meshelium.sodium.MesheliumChunkRendererBase", remap = false)
public abstract class ChunkRendererMixin {
	// Decline before suspending the main pass, leaving Iris's terrain pipeline intact.
	@Inject(method = "wantsDraw", at = @At("HEAD"), cancellable = true)
	private void reflexium$packTerrain(CallbackInfoReturnable<Boolean> cir) {
		if (MesheliumCompat.delegateTerrain()) cir.setReturnValue(false);
	}

	// The drawer pushes raw Vulkan descriptors, bypassing FrontendRenderPass.setUniform.
	@ModifyVariable(method = "draw", at = @At("HEAD"), argsOnly = true)
	private GpuSampler reflexium$atlasBias(GpuSampler sampler) {
		return UpscalingRenderer.get().materialSampler(sampler, MaterialBinding.BLOCK_TEX);
	}
}
