package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.dlss.ObjectMotion;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMotionMixin {
	@WrapMethod(method = "submit")
	private void reflexium$submit(BlockEntityRenderState state, PoseStack poses, SubmitNodeCollector collector,
			CameraRenderState camera, Operation<Void> original) {
		ObjectMotion motion = ObjectMotion.get();
		motion.enterBlockEntity(state.blockPos.asLong());
		try { original.call(state, poses, collector, camera); }
		finally { motion.leaveContext(); }
	}
}
