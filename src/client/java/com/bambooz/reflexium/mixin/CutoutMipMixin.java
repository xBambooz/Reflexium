package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.debug.DebugSettings;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Transparency;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Clean-room binary-majority experiment. Vanilla already fits filtered alpha coverage. */
@Mixin(MipmapGenerator.class)
public abstract class CutoutMipMixin {
	@Inject(method = "generateMipLevels", at = @At("HEAD"), cancellable = true)
	private static void reflexium$binaryMip(Identifier name, NativeImage[] current, int levels,
			MipmapStrategy strategy, float bias, Transparency transparency,
			CallbackInfoReturnable<NativeImage[]> cir) {
		if (!DebugSettings.binaryCutoutMips || !name.getPath().startsWith("block/")
				|| !transparency.hasTransparent() || levels < current.length) return;
		NativeImage[] result = new NativeImage[levels + 1];
		System.arraycopy(current, 0, result, 0, current.length);
		for (int level = current.length; level <= levels; level++) {
			NativeImage parent = result[level - 1];
			NativeImage image = new NativeImage(parent.getWidth() >> 1, parent.getHeight() >> 1, false);
			result[level] = image;
			for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
				int alpha = 0, red = 0, green = 0, blue = 0;
				for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
					int pixel = parent.getPixel(2 * x + dx, 2 * y + dy);
					int a = pixel >>> 24;
					alpha += a;
					red += (pixel >>> 16 & 255) * a;
					green += (pixel >>> 8 & 255) * a;
					blue += (pixel & 255) * a;
				}
				image.setPixel(x, y, alpha == 0 ? 0 : (alpha >= 510 ? 0xff000000 : 0)
						| red / alpha << 16 | green / alpha << 8 | blue / alpha);
			}
		}
		cir.setReturnValue(result);
	}
}
