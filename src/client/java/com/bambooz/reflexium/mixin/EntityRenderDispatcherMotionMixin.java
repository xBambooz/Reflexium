package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.motion.EntityMotionState;

import com.bambooz.reflexium.motion.ObjectMotion;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMotionMixin {
	@WrapMethod(method = "submit")
	private void reflexium$submit(EntityRenderState state, CameraRenderState camera, double x, double y, double z,
			PoseStack poses, SubmitNodeCollector collector, Operation<Void> original) {
		ObjectMotion motion = ObjectMotion.get();
		motion.enterEntity(((EntityMotionState) state).reflexium$entityId());
		try { original.call(state, camera, x, y, z, poses, collector); }
		finally { motion.leaveContext(); }
	}
}
