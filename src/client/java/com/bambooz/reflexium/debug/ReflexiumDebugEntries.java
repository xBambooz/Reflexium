package com.bambooz.reflexium.debug;

import com.bambooz.reflexium.ReflexiumClient;
import com.bambooz.reflexium.framegen.FrameGenerationRenderer;
import com.bambooz.reflexium.upscale.UpscalingRenderer;
import com.bambooz.reflexium.reflex.ReflexController;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Map;
import java.util.function.Supplier;

/** F3 lines, registered into vanilla's debug entry table (toggleable in the debug options screen). */
public final class ReflexiumDebugEntries {
	public static final Identifier REFLEX = id("reflex");
	public static final Identifier LATENCY = id("reflex_latency");
	public static final Identifier DLSS = id("dlss");
	public static final Identifier FRAME_GENERATION = id("frame_generation");

	public static final Map<Identifier, DebugScreenEntry> ENTRIES = Map.of(
			REFLEX, new Line(() -> ReflexController.get().statusLine()),
			LATENCY, new Line(() -> ReflexController.get().latencyLine()),
			DLSS, new Line(() -> UpscalingRenderer.get().statusLine()),
			FRAME_GENERATION, new Line(() -> FrameGenerationRenderer.get().statusLine()));

	private ReflexiumDebugEntries() {
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(ReflexiumClient.MOD_ID, path);
	}

	private record Line(Supplier<String> text) implements DebugScreenEntry {
		@Override
		public void display(DebugScreenDisplayer displayer, Level level, LevelChunk clientChunk, LevelChunk serverChunk) {
			displayer.addLine(text.get());
		}

		@Override
		public boolean isAllowed(boolean reducedDebugInfo) {
			return true;
		}
	}
}
