package com.bambooz.reflexium.mixin.compat;

import com.bambooz.reflexium.debug.FpsText;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Keep MiniHUD's translated label and styling while adding the real submitted rate. */
@Pseudo
@Mixin(targets = "fi.dy.masa.minihud.info.InfoLine", remap = false)
public abstract class MiniHudFpsMixin {
	@ModifyExpressionValue(method = "translate", at = @At(value = "INVOKE", target =
			"Lfi/dy/masa/malilib/util/StringUtils;translate(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"))
	private String reflexium$renderedFps(String line, String key, Object[] args) {
		return key.equals("minihud.info_line.fps") && args.length == 1 && args[0] instanceof Integer shown
				? FpsText.miniHud(line, shown) : line;
	}
}
