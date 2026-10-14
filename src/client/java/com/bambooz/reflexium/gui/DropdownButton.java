package com.bambooz.reflexium.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Button widget showing the current selection with a dropdown arrow and opening
 * a choice list on click.
 */
public final class DropdownButton {
	private DropdownButton() {
	}

	public static <T> Button create(Screen parent, Component name, Supplier<T> getter, List<T> values,
			Function<T, Component> labelProvider, Consumer<T> setter) {
		return Button.builder(makeLabel(name, getter.get(), labelProvider), b -> {
			Minecraft.getInstance().setScreenAndShow(new DropdownSelectionScreen<>(parent, name, getter.get(), values, labelProvider, val -> {
				setter.accept(val);
				b.setMessage(makeLabel(name, val, labelProvider));
			}));
		}).bounds(0, 0, 310, 20).build();
	}

	public static <T> Component makeLabel(Component name, T value, Function<T, Component> labelProvider) {
		return Component.empty().append(name).append(": ").append(labelProvider.apply(value)).append("  \u25BC");
	}
}
