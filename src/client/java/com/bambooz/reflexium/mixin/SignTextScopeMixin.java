package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.debug.DebugSettings;
import com.bambooz.reflexium.upscale.DeferredWorldText;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractSignRenderer.class)
public abstract class SignTextScopeMixin {
	@WrapMethod(method = "submitSignText")
	private void reflexium$scope(SignRenderState state, PoseStack pose, SubmitNodeCollector collector,
			SignText text, Operation<Void> original) {
		DeferredWorldText.enter();
		try { original.call(state, pose, collector, text); }
		finally { DeferredWorldText.exit(); }
	}
	@Inject(method = "submitSignText", at = @At("HEAD"), cancellable = true)
	private void reflexium$diagnoseGlyphs(CallbackInfo ci) {
		if (DebugSettings.omitSignText) ci.cancel();
	}
}
