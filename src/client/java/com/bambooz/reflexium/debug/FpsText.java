package com.bambooz.reflexium.debug;

/** Puts the rendered frame rate in brackets after the displayed (frame generation) one on F3 lines. */
public final class FpsText {
	private static volatile int displayed;
	private static volatile int rendered;

	private FpsText() {
	}

	/** Called from Minecraft.getFps whenever frame generation multiplies the count. */
	public static void record(int shown, int real) {
		displayed = shown;
		rendered = real;
	}

	/** "240 fps ..." becomes "240 (80) fps ..."; lines that do not start with the shown count are left alone. */
	public static String withRendered(String line, int shown) {
		String prefix = shown + " ";
		if (shown != displayed || shown == rendered || !line.startsWith(prefix)) {
			return line;
		}
		return shown + " (" + rendered + ") " + line.substring(prefix.length());
	}
}
