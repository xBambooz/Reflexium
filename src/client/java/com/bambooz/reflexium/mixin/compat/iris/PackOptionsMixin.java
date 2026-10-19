package com.bambooz.reflexium.mixin.compat.iris;

import com.bambooz.reflexium.compat.IrisCompat;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Iris builds a pack's option values from the saved user changes once the option set is parsed, before any
 * program source is transformed. Overriding the pack's own temporal AA toggles here works for every pack that
 * exposes them, without source edits, and the saved option file is never written.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.option.ShaderPackOptions", remap = false)
public abstract class PackOptionsMixin {
	@ModifyArg(method = "<init>", at = @At(value = "INVOKE",
			target = "Lnet/irisshaders/iris/shaderpack/option/values/MutableOptionValues;<init>(Lnet/irisshaders/iris/shaderpack/option/OptionSet;Ljava/util/Map;)V"),
			index = 1)
	private Map<String, String> reflexium$upscalerOwnsTemporalAa(Map<String, String> values) {
		return IrisCompat.temporalOptionOverrides(this, values);
	}
}
