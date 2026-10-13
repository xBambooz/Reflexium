package com.bambooz.reflexium.compat.betterf3;

import com.bambooz.reflexium.config.ReflexiumConfig;
import me.cominixo.betterf3.config.ModConfigFile;
import me.cominixo.betterf3.modules.BaseModule;
import me.cominixo.betterf3.utils.PositionEnum;

/** Called from {@code BetterF3ConfigMixin}, which only applies when BetterF3 is loaded. */
public final class BetterF3Compat {
	private static ReflexiumModule module;

	private BetterF3Compat() {
	}

	/** Before BetterF3 reads config/betterf3.json: make "reflexium" a known module id so a saved layout keeps it. */
	public static void registerModule() {
		if (module == null) {
			module = new ReflexiumModule();
			BaseModule.allModules.add(module);
		}
	}

	/**
	 * After the layout is loaded: add the module to the right column once and save BetterF3's config so
	 * the placement persists. Later starts follow the saved layout, so removing the module in BetterF3's
	 * settings sticks.
	 */
	public static void placeOnce() {
		ReflexiumConfig config = ReflexiumConfig.get();
		if (module == null || config.betterF3ModulePlaced()) {
			return;
		}
		if (!BaseModule.modules.contains(module) && !BaseModule.modulesRight.contains(module)) {
			module.init(PositionEnum.RIGHT);
			ModConfigFile.saveRunnable.run();
		}
		config.setBetterF3ModulePlaced(true);
	}
}
