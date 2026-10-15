package com.bambooz.reflexium.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Accessor("hud3DTarget")
	RenderTarget reflexium$hud3DTarget();

	@Accessor("resourcePool")
	CrossFrameResourcePool reflexium$resourcePool();

	@Accessor("appliedPostEffects")
	List<PostChain> reflexium$appliedPostEffects();

	@Accessor("globalSettingsUniform")
	GlobalSettingsUniform reflexium$globalSettingsUniform();
}
