package com.bambooz.reflexium.compat;

import com.bambooz.reflexium.dlss.UpscalingRenderer;
import com.google.gson.JsonObject;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** One reviewed pack instance's previous deformation inputs. Sources only read; advance runs once per pack frame. */
public final class PackMotionState {
	private static PackMotionState latest;
	public static void resetEvidence() { latest = null; }
	private final float[] now = new float[4], previous = new float[4];
	private Object world, catalog, carrier;
	private Method read, component, source, counter, time, vector, scalar;
	private int frame = -1;
	private boolean valid;

	public void install(Object values) {
		if (!ComplementaryHdr.ENABLED || !ComplementaryHdr.reviewedArchive()) return;
		try {
			Class<?> api = Class.forName("dev.vitrail.uniform.UniformCatalog");
			Class<?> shape = Class.forName("dev.vitrail.uniform.UniformShape");
			Class<?> provider = Class.forName("dev.vitrail.uniform.UniformSource");
			Class<?> state = Class.forName("dev.vitrail.uniform.WorldState");
			Class<?> val = Class.forName("dev.vitrail.uniform.Val");
			read = provider.getMethod("read", state, val); component = val.getMethod("f", int.class);
			source = api.getMethod("source", String.class); counter = state.getMethod("frameCounter"); time = state.getMethod("frameTimeCounter");
			vector = val.getMethod("set", float.class, float.class, float.class, float.class); scalar = val.getMethod("set", float.class);
			carrier = val.getConstructor().newInstance();
			world = values.getClass().getMethod("world").invoke(values);
			catalog = values.getClass().getMethod("catalog").invoke(values);
			Method builderMethod = api.getMethod("builder", api);
			Class<?> builderClass = Class.forName("dev.vitrail.uniform.UniformCatalog$Builder");
			Method add = builderClass.getMethod("add", String.class, shape, provider), build = builderClass.getMethod("build");
			for (String fieldName : new String[]{"catalog", "geometry", "shadowGeometry"}) {
				var field = values.getClass().getDeclaredField(fieldName); field.setAccessible(true);
				Object builder = builderMethod.invoke(null, field.get(values));
				for (String name : new String[]{"rxPreviousWave", "rxJitter", "rxMotionValid"}) {
					Object callback = Proxy.newProxyInstance(provider.getClassLoader(), new Class<?>[]{provider}, (proxy, method, args) -> {
						if (!method.getName().equals("read")) return null;
						Object out = args[1];
						if (name.equals("rxMotionValid")) scalar.invoke(out, valid ? 1f : 0f);
						else if (name.equals("rxPreviousWave")) vector.invoke(out, previous[0], previous[1], previous[2], previous[3]);
						else UpscalingRenderer.get().writePackJitter(out, vector);
						return null;
					});
					add.invoke(builder, name, shape.getField(name.equals("rxMotionValid") ? "FLOAT" : "VEC4").get(null), callback);
				}
				field.set(values, build.invoke(builder));
			}
		} catch (ReflectiveOperationException e) { throw new IllegalStateException("Vitrail pack motion uniform ABI changed", e); }
	}

	public void advance() {
		if (world == null) return;
		try {
			int next = (int) counter.invoke(world);
			if (next == frame) return;
			valid = frame >= 0 && next == frame + 1;
			System.arraycopy(now, 0, previous, 0, 4);
			now[0] = (float) time.invoke(world);
			String[] names = {"rainFactor", "inSnowy", "eyeBrightnessM"};
			for (int i = 0; i < names.length; i++) {
				Object reader = source.invoke(catalog, names[i]);
				if (reader == null) throw new IllegalStateException("Pack deformation parameter absent: " + names[i]);
				read.invoke(reader, world, carrier); now[i + 1] = (float) component.invoke(carrier, 0);
			}
			if (!valid) System.arraycopy(now, 0, previous, 0, 4);
			frame = next;
			latest = this;
		} catch (ReflectiveOperationException e) { throw new IllegalStateException("Pack previous deformation parameters unavailable", e); }
	}
	public static JsonObject evidence() {
		var out = new JsonObject();
		if (latest == null) return out;
		out.addProperty("pack_frame", latest.frame); out.addProperty("previous_parameters_valid", latest.valid);
		String[] names = {"time", "rain", "snow", "eye_light"};
		for (int i = 0; i < names.length; i++) { out.addProperty("wave_" + names[i], latest.now[i]); out.addProperty("previous_wave_" + names[i], latest.previous[i]); }
		return out;
	}
}
