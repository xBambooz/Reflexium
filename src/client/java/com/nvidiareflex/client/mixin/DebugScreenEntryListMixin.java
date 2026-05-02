package com.nvidiareflex.client.mixin;

import com.nvidiareflex.client.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.debug.DebugScreenProfile;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.util.Map;

@Mixin(DebugScreenEntryList.class)
public abstract class DebugScreenEntryListMixin {
	@Shadow @Final private Map<Identifier, DebugScreenEntryStatus> allStatuses;

	@Shadow public abstract void rebuildCurrentList();

	@Inject(method = "<init>", at = @At("RETURN"))
	private void nvidia_reflex$addDefaultDebugStatuses(File gameDirectory, com.mojang.datafixers.DataFixer dataFixer, CallbackInfo ci) {
		if (nvidia_reflex$addMissingDebugStatuses()) {
			rebuildCurrentList();
		}
	}

	@Inject(method = "loadProfile", at = @At("RETURN"))
	private void nvidia_reflex$addProfileDebugStatuses(DebugScreenProfile profile, CallbackInfo ci) {
		if (nvidia_reflex$addMissingDebugStatuses()) {
			rebuildCurrentList();
		}
	}

	private boolean nvidia_reflex$addMissingDebugStatuses() {
		boolean changed = false;
		changed |= allStatuses.putIfAbsent(DebugScreenEntries.REFLEX_STATE, DebugScreenEntryStatus.IN_OVERLAY) == null;
		changed |= allStatuses.putIfAbsent(DebugScreenEntries.REFLEX_LATENCY, DebugScreenEntryStatus.IN_OVERLAY) == null;
		return changed;
	}
}
