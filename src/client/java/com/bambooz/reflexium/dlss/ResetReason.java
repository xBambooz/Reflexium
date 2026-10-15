package com.bambooz.reflexium.dlss;

/** Why temporal history was discarded. Counted separately so a stable scene shows which cut fired. */
public enum ResetReason {
	FIRST_FRAME("first frame"), LEVEL("level change"), TELEPORT("teleport"), GAP("frame gap"),
	CAMERA("camera mode or entity"), ROTATION("rotation cut"), LOADING("loading frame"),
	REBUILD("feature rebuild"), MENU("frame without a world");

	final String label;

	ResetReason(String label) {
		this.label = label;
	}
}
