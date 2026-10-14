package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.dlss.EntityMotionState;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements EntityMotionState {
	@Unique private int reflexium$entityId = -1;
	@Override public int reflexium$entityId() { return reflexium$entityId; }
	@Override public void reflexium$entityId(int id) { reflexium$entityId = id; }
}
