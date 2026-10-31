package com.bambooz.reflexium.mixin.compat.iris;

import com.bambooz.reflexium.compat.IrisCompat;
import com.bambooz.reflexium.compat.IrisOptionPersistence;
import java.util.Properties;
import java.util.Map;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Startup, reload keybind, pack switch and shader toggle all load through loadShaderpack. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
public abstract class IrisLoadMixin {
	@Shadow public static Map<String, String> getShaderPackOptionQueue() { throw new AssertionError(); }
	@Shadow public static boolean shouldResetShaderPackOptionsOnNextReload() { throw new AssertionError(); }

	@Inject(method = "loadExternalShaderpack", at = @At("HEAD"))
	private static void reflexium$userChanges(String pack, CallbackInfoReturnable<Boolean> cir) {
		// Screen Apply merges these changes before constructing options. They are user intent, not a leak.
		IrisOptionPersistence.userChangesPending(!getShaderPackOptionQueue().isEmpty() || shouldResetShaderPackOptionsOnNextReload());
	}

	@ModifyVariable(method = "tryUpdateConfigPropertiesFile", at = @At("HEAD"), argsOnly = true)
	private static Properties reflexium$saveUserOptions(Properties values) {
		return IrisOptionPersistence.forSave(values);
	}

	@Inject(method = "loadShaderpack", at = @At("HEAD"))
	private static void reflexium$packLoading(CallbackInfo ci) {
		IrisCompat.onPackLoading();
	}
}
