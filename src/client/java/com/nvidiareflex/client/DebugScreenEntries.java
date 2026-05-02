package com.nvidiareflex.client;

import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

public final class DebugScreenEntries {
	public static final Identifier REFLEX_STATE = Identifier.fromNamespaceAndPath("nvidia-reflex-mod", "reflex_state");
	public static final Identifier REFLEX_LATENCY = Identifier.fromNamespaceAndPath("nvidia-reflex-mod", "reflex_latency");

	private DebugScreenEntries() {
	}

	public static final class ReflexStateEntry implements DebugScreenEntry {
		@Override
		public void display(DebugScreenDisplayer displayer, Level level, LevelChunk clientChunk, LevelChunk serverChunk) {
			displayer.addLine(ReflexManager.getInstance().getDebugStateLine());
		}

		@Override
		public boolean isAllowed(boolean reducedDebugInfo) {
			return true;
		}
	}

	public static final class ReflexLatencyEntry implements DebugScreenEntry {
		@Override
		public void display(DebugScreenDisplayer displayer, Level level, LevelChunk clientChunk, LevelChunk serverChunk) {
			displayer.addLine(ReflexManager.getInstance().getDebugLatencyLine());
		}

		@Override
		public boolean isAllowed(boolean reducedDebugInfo) {
			return true;
		}
	}
}
