package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.debug.ReflexiumDebugEntries;
import com.mojang.datafixers.DataFixer;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.debug.DebugScreenProfile;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.util.Map;

/** Shows Reflexium's lines in the F3 overlay by default; players can still hide them per entry. */
@Mixin(DebugScreenEntryList.class)
public abstract class DebugScreenEntryListMixin {
	@Shadow @Final private Map<Identifier, DebugScreenEntryStatus> allStatuses;

	@Shadow public abstract void rebuildCurrentList();

	@Inject(method = "<init>", at = @At("RETURN"))
	private void reflexium$defaultsOnCreate(File gameDirectory, DataFixer dataFixer, CallbackInfo ci) {
		reflexium$addDefaults();
	}

	@Inject(method = "loadProfile", at = @At("RETURN"))
	private void reflexium$defaultsOnProfile(DebugScreenProfile profile, CallbackInfo ci) {
		reflexium$addDefaults();
	}

	@Unique
	private void reflexium$addDefaults() {
		boolean changed = false;
		for (Identifier id : ReflexiumDebugEntries.ENTRIES.keySet()) {
			changed |= allStatuses.putIfAbsent(id, DebugScreenEntryStatus.IN_OVERLAY) == null;
		}
		if (changed) {
			rebuildCurrentList();
		}
	}
}
