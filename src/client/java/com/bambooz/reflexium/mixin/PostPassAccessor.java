package com.bambooz.reflexium.mixin;

import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(PostPass.class)
public interface PostPassAccessor {
	@Accessor("inputs")
	List<PostPass.Input> reflexium$inputs();
}
