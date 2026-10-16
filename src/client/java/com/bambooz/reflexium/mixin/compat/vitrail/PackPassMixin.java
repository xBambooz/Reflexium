package com.bambooz.reflexium.mixin.compat.vitrail;

import com.bambooz.reflexium.compat.ComplementaryHdr;
import com.bambooz.reflexium.dlss.UpscalingRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.vitrail.render.PackPass", remap = false)
public abstract class PackPassMixin {
	@Shadow private String path;

	@Inject(method = "draw", at = @At("HEAD"))
	private void reflexium$hdrBoundary(CommandEncoder encoder, @Coerce Object targets, GpuTextureView depth,
			GpuTextureView distant, GpuBuffer quad, GpuBufferSlice uniforms, int width, int height, CallbackInfo ci) {
		if (ComplementaryHdr.first(path)) UpscalingRenderer.get().reconstructPackHdr(this, targets);
	}
	@ModifyVariable(method = "draw", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int reflexium$postWidth(int width) {
		return ComplementaryHdr.displaySized(path) ? UpscalingRenderer.get().outputWidth() : width;
	}
	@ModifyVariable(method = "draw", at = @At("HEAD"), argsOnly = true, ordinal = 1)
	private int reflexium$postHeight(int height) {
		return ComplementaryHdr.displaySized(path) ? UpscalingRenderer.get().outputHeight() : height;
	}
	@Inject(method = "draw", at = @At("RETURN"))
	private void reflexium$publishPostWrite(CallbackInfo ci) { ComplementaryHdr.finishPass(); }
	@Inject(method = "view", at = @At("RETURN"), cancellable = true)
	private void reflexium$postAttachment(@Coerce Object targets, @Coerce Object attachment, CallbackInfoReturnable<GpuTextureView> cir) {
		cir.setReturnValue(ComplementaryHdr.attachment(path, cir.getReturnValue()));
	}
	@ModifyVariable(method = "drawFinal", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private GpuTextureView reflexium$displayFinal(GpuTextureView into) {
		return UpscalingRenderer.get().packHdrThisFrame() ? UpscalingRenderer.get().packDisplayColor() : into;
	}
	@WrapOperation(method = "write", at = @At(value = "INVOKE", target = "Ldev/vitrail/render/PackUniforms;write(Lcom/mojang/blaze3d/buffers/Std140Builder;Ldev/vitrail/uniform/WorldState;)V"))
	private void reflexium$postUniforms(@Coerce Object uniforms, Std140Builder builder, @Coerce Object world, Operation<Void> original) {
		original.call(uniforms, builder, ComplementaryHdr.world(path, world));
	}
	@WrapOperation(method = "bindSamplers", at = @At(value = "INVOKE", target = "Ldev/vitrail/render/GraphicsApi;bindTexture(Lcom/mojang/renderpearl/api/commands/RenderPass;Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V"))
	private void reflexium$postSamplers(RenderPass pass, String name, GpuTextureView view, GpuSampler sampler, Operation<Void> original) {
		original.call(pass, name, ComplementaryHdr.sampler(path, name, view), sampler);
	}
}
