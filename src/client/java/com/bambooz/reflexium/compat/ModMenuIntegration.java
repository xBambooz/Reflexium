package com.bambooz.reflexium.compat;

import com.bambooz.reflexium.gui.ReflexiumConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ReflexiumConfigScreen::new;
	}
}
