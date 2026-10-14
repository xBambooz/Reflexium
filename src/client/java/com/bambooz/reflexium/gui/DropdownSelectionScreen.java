package com.bambooz.reflexium.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Dropdown selection screen overlay listing available choices with a cancel option.
 */
public final class DropdownSelectionScreen<T> extends Screen {
	private final Screen parent;
	private final T current;
	private final List<T> values;
	private final Function<T, Component> labelProvider;
	private final Consumer<T> onSelect;

	public DropdownSelectionScreen(Screen parent, Component title, T current, List<T> values,
			Function<T, Component> labelProvider, Consumer<T> onSelect) {
		super(title);
		this.parent = parent;
		this.current = current;
		this.values = values;
		this.labelProvider = labelProvider;
		this.onSelect = onSelect;
	}

	@Override
	protected void init() {
		int buttonWidth = 220;
		int buttonHeight = 20;
		int spacing = 24;
		int totalHeight = values.size() * spacing + 30;
		int startY = Math.max(30, (this.height - totalHeight) / 2);
		int startX = (this.width - buttonWidth) / 2;

		for (int i = 0; i < values.size(); i++) {
			T val = values.get(i);
			Component label = labelProvider.apply(val);
			if (val.equals(current)) {
				label = Component.literal("> ").append(label).append(" <");
			}
			int y = startY + i * spacing;
			addRenderableWidget(Button.builder(label, b -> {
				onSelect.accept(val);
				onClose();
			}).bounds(startX, y, buttonWidth, buttonHeight).build());
		}

		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
				.bounds(startX, startY + values.size() * spacing + 10, buttonWidth, buttonHeight).build());
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreenAndShow(parent);
		}
	}
}
