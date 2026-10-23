package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.motion.ObjectMotion;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Scopes hand and held-item vertex capture to the exact submission window, with the modelview the GPU will
 * draw them with. Vanilla's hand pose starts with the inverse camera rotation and the modelview stack
 * re-applies it; Vitrail's own hand draw resets the stack to identity before submitting the same geometry.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class HandMotionMixin {
	@WrapMethod(method = "submitHandsWithItems")
	private void reflexium$handContext(float partialTick, PoseStack poseStack, SubmitNodeCollector collector,
			PlayerRenderState player, FirstPersonHandsAndItemsRenderState state, Operation<Void> original) {
		ObjectMotion motion = ObjectMotion.get();
		if (!motion.isActive()) {
			original.call(partialTick, poseStack, collector, player, state);
			return;
		}
		motion.enterHand(RenderSystem.getModelViewStack());
		try {
			original.call(partialTick, poseStack, collector, player, state);
		} finally {
			motion.leaveContext();
		}
	}
}
