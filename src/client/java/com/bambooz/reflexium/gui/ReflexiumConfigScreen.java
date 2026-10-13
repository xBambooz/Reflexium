package com.bambooz.reflexium.gui;

import com.bambooz.reflexium.compat.ApertureCompat;
import com.bambooz.reflexium.compat.VitrailCompat;
import com.bambooz.reflexium.dlss.DlssSupport;
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
		list.addSmall(ReflexiumOptions.frameLimiter(), ReflexiumOptions.dlssDetection());

		list.addHeader(Component.translatable("reflexium.status"));
		list.addHeader(Component.literal(ReflexController.get().statusLine()));
		list.addHeader(Component.literal("DLSS: " + DlssSupport.status().shortSummary()));
		if (VitrailCompat.isLoaded()) {
			list.addHeader(Component.literal("Vitrail: " + VitrailCompat.status()));
		}
		list.addHeader(Component.literal("Aperture: " + ApertureCompat.status()));
	}
}
