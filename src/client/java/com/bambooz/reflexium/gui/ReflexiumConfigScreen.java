package com.bambooz.reflexium.gui;

import com.bambooz.reflexium.compat.ApertureCompat;
import com.bambooz.reflexium.compat.VitrailCompat;
import com.bambooz.reflexium.config.ReflexiumConfig;
import com.bambooz.reflexium.dlss.DlssModel;
import com.bambooz.reflexium.dlss.FrameGeneration;
import com.bambooz.reflexium.dlss.FrameGenerationRenderer;
import com.bambooz.reflexium.dlss.UpscaleQuality;
import com.bambooz.reflexium.dlss.Upscaler;
import com.bambooz.reflexium.dlss.UpscalingRenderer;
import com.bambooz.reflexium.reflex.ReflexController;
import com.bambooz.reflexium.reflex.ReflexMode;
import com.bambooz.reflexium.vulkan.VulkanSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class ReflexiumConfigScreen extends OptionsSubScreen {
	private final Screen parentScreen;

	public ReflexiumConfigScreen(Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.translatable("reflexium.title"));
		this.parentScreen = parent;
	}

	@Override
	protected void addOptions() {
		ReflexiumConfig config = ReflexiumConfig.get();
		VulkanSession session = VulkanSession.current();
		boolean isNvidia = session != null && session.isNvidia();

		list.addBig(DropdownButton.create(this, Component.translatable("reflexium.option.reflex_mode"),
				config::reflexMode, List.of(ReflexMode.values()), ReflexMode::label, mode -> {
					ReflexController.get().setMode(mode);
				}));

		list.addBig(ReflexiumOptions.smartReflex());

		list.addHeader(Component.translatable("reflexium.group.upscaling"));

		List<Upscaler> allowedUpscalers = new ArrayList<>();
		allowedUpscalers.add(Upscaler.AUTO);
		allowedUpscalers.add(Upscaler.OFF);
		if (isNvidia) {
			allowedUpscalers.add(Upscaler.DLSS);
		}
		allowedUpscalers.add(Upscaler.FSR);

		list.addBig(DropdownButton.create(this, Component.translatable("reflexium.option.upscaler"),
				config::upscaler, allowedUpscalers, Upscaler::label, upscaler -> {
					ReflexiumOptions.setUpscaler(upscaler);
					rebuild();
				}));

		Upscaler effective = UpscalingRenderer.get().effectiveUpscaler();
		list.addBig(DropdownButton.create(this, Component.translatable("reflexium.option.quality"),
				config::upscaleQuality, List.of(UpscaleQuality.values()), UpscaleQuality::label, quality -> {
					ReflexiumOptions.setUpscaleQuality(quality);
				}));

		if ((effective == Upscaler.DLSS || config.upscaler() == Upscaler.DLSS) && isNvidia && UpscalingRenderer.get().dlssReady()) {
			list.addBig(DropdownButton.create(this, Component.translatable("reflexium.option.dlss_model"),
					config::dlssModel, List.of(DlssModel.values()), DlssModel::label, model -> {
						ReflexiumOptions.setDlssModel(model);
					}));
		}

		if (effective == Upscaler.FSR || config.upscaler() == Upscaler.FSR) {
			list.addBig(ReflexiumOptions.fsrSharpness());
		}

		if (config.upscaler() != Upscaler.OFF) {
			list.addHeader(Component.translatable("reflexium.group.frame_generation"));

			List<FrameGeneration> allowedFg = new ArrayList<>();
			allowedFg.add(FrameGeneration.OFF);
			boolean dlssActive = effective == Upscaler.DLSS || config.upscaler() == Upscaler.DLSS;
			boolean fsrActive = effective == Upscaler.FSR || config.upscaler() == Upscaler.FSR;
			if (dlssActive && isNvidia) {
				if (FrameGenerationRenderer.get().isDlssFgSupported()) {
					allowedFg.add(FrameGeneration.DLSS);
				}
				if (FrameGenerationRenderer.get().isDlssMfgSupported()) {
					allowedFg.add(FrameGeneration.DLSS_MULTI);
				}
			} else if (fsrActive) {
				allowedFg.add(FrameGeneration.FSR);
			}

			list.addBig(DropdownButton.create(this, Component.translatable("reflexium.option.frame_generation"),
					config::frameGeneration, allowedFg, FrameGeneration::label, fg -> {
						ReflexiumOptions.setFrameGeneration(fg);
						rebuild();
					}));

			if (config.frameGeneration() == FrameGeneration.DLSS_MULTI && FrameGenerationRenderer.get().isDlssMfgSupported()) {
				list.addBig(ReflexiumOptions.frameGenerationMultiplier());
			}
		}

		list.addHeader(Component.translatable("reflexium.status"));
		list.addHeader(Component.literal(ReflexController.get().statusLine()));
		list.addHeader(Component.literal(UpscalingRenderer.get().statusLine()));
		list.addHeader(Component.literal(FrameGenerationRenderer.get().statusLine()));
		if (VitrailCompat.isLoaded()) {
			list.addHeader(Component.literal("Vitrail: " + VitrailCompat.status()));
		}
		if (com.bambooz.reflexium.compat.IrisCompat.isLoaded()) {
			list.addHeader(Component.literal("Iris: " + com.bambooz.reflexium.compat.IrisCompat.status()));
		}
		list.addHeader(Component.literal("Aperture: " + ApertureCompat.status()));
	}

	private String shownState = state();
	private int ticks;

	/** What the options and status lines were built from. Reflex pacing text is excluded: it flips at high FPS. */
	private static String state() {
		return UpscalingRenderer.get().statusLine() + "|" + FrameGenerationRenderer.get().statusLine() + "|" + UpscalingRenderer.get().dlssReady()
				+ "|" + FrameGenerationRenderer.get().isDlssFgSupported() + "|" + FrameGenerationRenderer.get().isDlssMfgSupported()
				+ "|" + com.bambooz.reflexium.compat.ShaderPacks.status();
	}

	@Override
	public void tick() {
		super.tick();
		// A finished DLSS download or a runtime failure changes what can be offered; rebuild once per change.
		if (++ticks % 20 == 0 && !state().equals(shownState)) rebuild();
	}

	private void rebuild() {
		if (this.minecraft != null) {
			this.minecraft.setScreenAndShow(new ReflexiumConfigScreen(parentScreen));
		}
	}
}
