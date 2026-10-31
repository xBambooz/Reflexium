package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.upscale.DeferredWorldText;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SubmitNodeCollection.class)
public abstract class NameTagScopeMixin {
	@WrapMethod(method = "submitNameTag")
	private void reflexium$scope(PoseStack pose, Vec3 offset, int y, Component name, boolean seeThrough,
			int light, CameraRenderState camera, Operation<Void> original) {
		DeferredWorldText.enter();
		try { original.call(pose, offset, y, name, seeThrough, light, camera); }
		finally { DeferredWorldText.exit(); }
	}
}
