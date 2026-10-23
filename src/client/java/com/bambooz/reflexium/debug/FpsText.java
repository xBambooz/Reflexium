package com.bambooz.reflexium.debug;


import com.bambooz.reflexium.framegen.FrameGenerationRenderer;

/** Puts the rendered frame rate in brackets after the displayed (frame generation) one on F3 lines. */
public final class FpsText {

	private FpsText() {
	}

	/** "240 fps ..." becomes "240 (80) fps ..."; lines that do not start with the shown count are left alone. */
	public static String withRendered(String line, int shown) {
		var fg = FrameGenerationRenderer.get();
		int displayed = fg.submittedFps(), rendered = fg.submittedRealFps();
		String prefix = shown + " ";
		if (displayed <= rendered || rendered == 0 || !line.startsWith(prefix)) {
			return line;
		}
		return displayed + " (" + rendered + ") " + line.substring(prefix.length()).replaceFirst("fps", "fps submitted");
	}
}
