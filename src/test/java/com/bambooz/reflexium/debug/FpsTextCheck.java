package com.bambooz.reflexium.debug;

public final class FpsTextCheck {
	public static void main(String[] args) {
		String vanilla = String.format("%d fps", 80);
		if (!FpsText.miniHud(vanilla, 80, 240, 80).equals("240 (80) fps")) throw new AssertionError("FG rate");
		if (!FpsText.miniHud("FPS: 80", 80, 240, 80).equals("FPS: 240 (80)")) throw new AssertionError("translated label");
		if (!FpsText.miniHud("240 fps", 240, 240, 80).equals("240 (80) fps")) throw new AssertionError("displayed vanilla count");
		if (!FpsText.miniHud("missing count", 80, 240, 80).equals("missing count")) throw new AssertionError("missing count");
		for (int[] rates : new int[][] {{80, 80}, {0, 0}, {240, 0}, {79, 80}}) {
			if (FpsText.miniHud(vanilla, 80, rates[0], rates[1]) != vanilla) throw new AssertionError("vanilla preserved");
		}
		System.out.println("FPS text checks passed");
	}
}
