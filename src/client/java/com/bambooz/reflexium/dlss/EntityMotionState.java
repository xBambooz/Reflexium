package com.bambooz.reflexium.dlss;

/** Entity identity survives render-state extraction, including deferred feature preparation. */
public interface EntityMotionState {
	int reflexium$entityId();
	void reflexium$entityId(int id);
}
