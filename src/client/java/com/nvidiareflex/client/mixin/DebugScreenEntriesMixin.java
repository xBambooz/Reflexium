package com.nvidiareflex.client.mixin;

import com.nvidiareflex.client.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(net.minecraft.client.gui.components.debug.DebugScreenEntries.class)
public abstract class DebugScreenEntriesMixin {
	@Shadow @Final private static Map<Identifier, DebugScreenEntry> ENTRIES_BY_ID;

	@Inject(method = "<clinit>", at = @At("TAIL"))
	private static void nvidia_reflex$registerDebugEntries(CallbackInfo ci) {
		ENTRIES_BY_ID.put(DebugScreenEntries.REFLEX_STATE, new DebugScreenEntries.ReflexStateEntry());
		ENTRIES_BY_ID.put(DebugScreenEntries.REFLEX_LATENCY, new DebugScreenEntries.ReflexLatencyEntry());
	}
}
