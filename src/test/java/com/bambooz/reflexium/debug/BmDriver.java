package com.bambooz.reflexium.debug;

import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Separate dev jar. Controls BM's normal capture path without changing its focus flags or metrics. */
public final class BmDriver implements ClientModInitializer {
	@Override public void onInitializeClient() {
		try {
			Class<?> api = Class.forName("com.bambooz.agentharness.api.AgentHarness");
			if (!(boolean) api.getMethod("isActive").invoke(null)) return;
			Class<?> handler = Class.forName("com.bambooz.agentharness.api.EndpointHandler");
			Object endpoint = Proxy.newProxyInstance(handler.getClassLoader(), new Class<?>[]{handler}, (proxy, method, args) -> {
				if (!method.getName().equals("handle")) return method.getName().equals("toString") ? "Benchmark Mod driver" : null;
				@SuppressWarnings("unchecked") Map<String, String> params = (Map<String, String>) args[0].getClass().getMethod("params").invoke(args[0]);
				CompletableFuture<JsonObject> result = new CompletableFuture<>();
				Minecraft.getInstance().execute(() -> {
					try {
						Class<?> controller = Class.forName("com.benchmarkmod.client.run.RunController");
						if (params.containsKey("scene")) {
							if (Minecraft.getInstance().level != null) throw new IllegalStateException("Start BM from a menu");
							controller.getMethod("start", String.class, String.class).invoke(null, params.get("scene"), params.getOrDefault("label", ""));
						}
						JsonObject response = new JsonObject();
						response.addProperty("ok", true);
						response.addProperty("data_directory", Class.forName("com.benchmarkmod.util.Paths").getMethod("dataRoot").invoke(null).toString());
						response.addProperty("background_capture", true);
						result.complete(response);
					} catch (Exception e) { result.completeExceptionally(e); }
				});
				return result.get(30, TimeUnit.SECONDS);
			});
			api.getMethod("register", String.class, String.class, handler).invoke(null, "bm", "Start Benchmark Mod capture (focus flags preserved)", endpoint);
		} catch (ReflectiveOperationException e) { throw new IllegalStateException("BM driver requires the active agent harness and Benchmark Mod", e); }
	}
}
