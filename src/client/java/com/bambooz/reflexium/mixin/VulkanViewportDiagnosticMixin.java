package com.bambooz.reflexium.mixin;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.debug.FrameProfiler;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/** Opt-in constructor viewport evidence, excluded from profiling-off FPS rows. */
@Mixin(VulkanRenderPass.class)
public abstract class VulkanViewportDiagnosticMixin {
	@Shadow @Final private int outputWidth;
	@Shadow @Final private int outputHeight;
	@Shadow @Final private RenderPass.RenderArea renderArea;
	@Shadow @Final private Supplier<String> label;
	@Unique private static final boolean reflexium$diagnostic = Boolean.getBoolean("reflexium.viewportDiagnostic");
	@Unique private static final Set<String> reflexium$seen = new HashSet<>();

	@Inject(method = "<init>", at = @At("RETURN"))
	private void reflexium$viewport(CallbackInfo ci) {
		if (!reflexium$diagnostic || !FrameProfiler.enabled()) return;
		String extent = label.get() + " target=" + outputWidth + "x" + outputHeight
				+ " viewport=0,0," + outputWidth + "," + outputHeight + " scissor=" + renderArea;
		// Cap diagnostic labels per JVM; use an API trace for exhaustive long captures.
		if (reflexium$seen.size() < 256 && reflexium$seen.add(extent)) {
			ReflexiumClient.LOGGER.info("Viewport diagnostic: {}", extent);
		}
	}
}
