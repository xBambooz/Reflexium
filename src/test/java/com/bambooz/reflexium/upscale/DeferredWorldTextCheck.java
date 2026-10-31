package com.bambooz.reflexium.upscale;

import net.minecraft.client.renderer.SubmitNodeStorage;

import java.lang.reflect.Field;
import java.util.Map;

/** Engine phase identities and ordering, without a client or GPU. */
public final class DeferredWorldTextCheck {
	public static void main(String[] args) throws Exception {
		DeferredWorldText.clear();
		if (((org.joml.Vector4fc) field("CLEAR").get(null)).w() != 0) throw new AssertionError("text layer must clear transparent");
		field("enabled").setBoolean(null, true);
		var source = new SubmitNodeStorage();
		var original = source.order(7);
		DeferredWorldText.register(original, 7);
		var phases = (Map<?, ?>) field("phases").get(null);
		var destination = DeferredWorldText.class.getDeclaredMethod("destination", Class.forName(DeferredWorldText.class.getName() + "$Phase"));
		destination.setAccessible(true);
		var target = (SubmitNodeStorage) field("storage").get(null);
		var mapped = target.order(7);
		Object[] inputs = {original.solid, original.texts, original.nameTags, original.seeThrough};
		Object[] outputs = {mapped.solid, mapped.texts, mapped.nameTags, target.seeThrough()};
		for (int i = 0; i < inputs.length; i++) {
			if (destination.invoke(null, phases.get(inputs[i])) != outputs[i]) throw new AssertionError("text phase/order " + i);
		}
		DeferredWorldText.enter();
		DeferredWorldText.enter();
		DeferredWorldText.exit();
		if (field("scope").getInt(null) != 1) throw new AssertionError("nested text scope");
		DeferredWorldText.clear();
		if (!phases.isEmpty() || field("scope").getInt(null) != 0 || DeferredWorldText.pending() || DeferredWorldText.prepared())
			throw new AssertionError("frame reset must discard text state");
		System.out.println("Deferred world text phase, order, scope and reset checks passed");
	}
	private static Field field(String name) throws Exception {
		Field field = DeferredWorldText.class.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}
}
