package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.motion.EntityMotionState;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMotionMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"))
	private void reflexium$identity(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo ci) {
		((EntityMotionState) state).reflexium$entityId(entity.getId());
	}
}
