package com.bambooz.reflexium.gui;

import com.bambooz.reflexium.compat.ApertureCompat;
import com.bambooz.reflexium.compat.VitrailCompat;
import com.bambooz.reflexium.dlss.UpscalingRenderer;
import com.bambooz.reflexium.reflex.ReflexController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

public final class ReflexiumConfigScreen extends OptionsSubScreen {
	public ReflexiumConfigScreen(Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.translatable("reflexium.title"));
	}

	@Override
	protected void addOptions() {
		list.addBig(ReflexiumOptions.reflexMode());
		list.addBig(ReflexiumOptions.dlssDetection());
		list.addHeader(Component.translatable("reflexium.group.upscaling"));
		list.addBig(ReflexiumOptions.upscaler());
		list.addBig(ReflexiumOptions.upscaleQuality());
		list.addBig(ReflexiumOptions.dlssModel());
		list.addBig(ReflexiumOptions.fsrSharpness());
		list.addHeader(Component.translatable("reflexium.group.frame_generation"));
		ReflexiumOptions.addFrameGeneration(list);

		list.addHeader(Component.translatable("reflexium.status"));
		list.addHeader(Component.literal(ReflexController.get().statusLine()));
		list.addHeader(Component.literal(UpscalingRenderer.get().statusLine()));
		list.addHeader(Component.literal(com.bambooz.reflexium.dlss.FrameGenerationRenderer.get().statusLine()));
		if (VitrailCompat.isLoaded()) {
			list.addHeader(Component.literal("Vitrail: " + VitrailCompat.status()));
		}
		list.addHeader(Component.literal("Aperture: " + ApertureCompat.status()));
	}
}
