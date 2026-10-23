package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.motion.ObjectMotion;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

@Mixin(RenderTypeFeatureRenderer.class)
public abstract class RenderTypeFeatureMotionMixin {
	@WrapMethod(method = "prepareGroup")
	private void reflexium$prepare(FeatureFrameContext context, List<SubmitNode> submits, boolean ordered, Operation<Void> original) {
		ObjectMotion motion = ObjectMotion.get();
		if (!motion.isActive()) { original.call(context, submits, ordered); return; }
		try { original.call(context, motion.observe(submits), ordered); }
		finally { motion.endGroup(); }
	}

	@Inject(method = "getVertexBuilder", at = @At("RETURN"), cancellable = true)
	private void reflexium$capture(RenderType type, CallbackInfoReturnable<VertexConsumer> cir) {
		if (ObjectMotion.get().isActive()) cir.setReturnValue(ObjectMotion.get().capture(cir.getReturnValue(), type));
	}
}
