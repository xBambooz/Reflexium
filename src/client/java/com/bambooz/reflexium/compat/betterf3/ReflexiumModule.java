package com.bambooz.reflexium.compat.betterf3;

import com.bambooz.reflexium.framegen.FrameGenerationRenderer;
import com.bambooz.reflexium.upscale.UpscalingRenderer;
import com.bambooz.reflexium.reflex.ReflexController;
import me.cominixo.betterf3.modules.BaseModule;
import me.cominixo.betterf3.utils.DebugLine;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;

/**
 * BetterF3 replaces vanilla's debug entry list with its own modules, which hides every other mod's
 * F3 entries. This module shows the same three lines as {@code ReflexiumDebugEntries}. BetterF3 derives
 * the module id ("reflexium") from the class name, so keep the name.
 */
public final class ReflexiumModule extends BaseModule {
	private final DebugLine mode = newLine("reflexium_mode");
	private final DebugLine latency = newLine("reflexium_latency");
	private final DebugLine dlss = newLine("reflexium_dlss");
	private final DebugLine fg = newLine("reflexium_fg");

	public ReflexiumModule() {
		defaultNameColor = legacyColor(ChatFormatting.GREEN);
		defaultValueColor = legacyColor(ChatFormatting.WHITE);
		nameColor = defaultNameColor;
		valueColor = defaultValueColor;
		lines.add(mode);
		lines.add(latency);
		lines.add(dlss);
		lines.add(fg);
	}

	private static DebugLine newLine(String id) {
		DebugLine line = new DebugLine(id);
		line.inReducedDebug = true;
		return line;
	}

	@Override
	public void update(Minecraft minecraft) {
		ReflexController reflex = ReflexController.get();
		mode.value(valueOf(reflex.statusLine()));
		latency.value(valueOf(reflex.latencyLine()));
		dlss.value(valueOf(UpscalingRenderer.get().statusLine()));
		fg.value(valueOf(FrameGenerationRenderer.get().statusLine()));
	}

	@Override
	public boolean updatesEveryFrame() {
		return true;
	}

	/** Drops the "Reflex: " style label, which BetterF3 draws from the line's translation instead. */
	private static String valueOf(String line) {
		int colon = line.indexOf(": ");
		return colon < 0 ? line : line.substring(colon + 2);
	}
}
