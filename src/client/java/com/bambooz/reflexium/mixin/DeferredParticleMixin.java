package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.upscale.DeferredEffects;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
public abstract class DeferredParticleMixin {
	@WrapOperation(method = "executeClassicTransparency", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/WeatherEffectRenderer;render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
	private void reflexium$weather(WeatherEffectRenderer renderer, WeatherRenderState state, RenderPass pass, Operation<Void> original) {
		if (DeferredEffects.enabled()) DeferredEffects.deferWeather(renderer, state);
		else original.call(renderer, state, pass);
	}
	@WrapOperation(method = "submitFeatures", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;submit(Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
	private void reflexium$particles(ParticlesRenderState particles, SubmitNodeCollector collector, CameraRenderState camera, Operation<Void> original) {
		if (DeferredEffects.enabled()) DeferredEffects.defer(particles, camera);
		else original.call(particles, collector, camera);
	}
}
