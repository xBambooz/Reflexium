package com.bambooz.reflexium.debug.server;

import com.bambooz.reflexium.ReflexiumClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** HTTP plumbing and query parameter parsing for the debug server. */
final class DebugHttp {
	static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private DebugHttp() {
	}

	interface Command {
		JsonObject run(Map<String, String> query) throws Exception;
	}

	/** A request the caller can fix; answered with HTTP 400 and the message. */
	static final class BadRequest extends RuntimeException {
		BadRequest(String message) {
			super(message);
		}
	}

	static void route(HttpServer server, String path, Command command) {
		server.createContext(path, exchange -> {
			int code = 200;
			JsonObject body;
			try {
				body = command.run(query(exchange));
				body.addProperty("ok", true);
			} catch (BadRequest e) {
				code = 400;
				body = error(e.getMessage());
			} catch (TimeoutException e) {
				code = 504;
				body = error("timed out (render thread busy or condition never met)");
			} catch (Exception e) {
				code = 500;
				Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
				body = error(cause.getClass().getSimpleName() + ": " + cause.getMessage());
				ReflexiumClient.LOGGER.warn("Debug command {} failed", path, cause);
			}
			respond(exchange, code, body);
		});
	}

	private static JsonObject error(String message) {
		JsonObject o = new JsonObject();
		o.addProperty("ok", false);
		o.addProperty("error", message);
		return o;
	}

	private static void respond(HttpExchange exchange, int code, JsonObject body) throws IOException {
		byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(code, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static Map<String, String> query(HttpExchange exchange) {
		Map<String, String> result = new HashMap<>();
		String raw = exchange.getRequestURI().getRawQuery();
		if (raw != null) {
			for (String pair : raw.split("&")) {
				if (pair.isEmpty()) {
					continue;
				}
				int eq = pair.indexOf('=');
				String key = eq < 0 ? pair : pair.substring(0, eq);
				String value = eq < 0 ? "" : pair.substring(eq + 1);
				result.put(URLDecoder.decode(key, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT),
						URLDecoder.decode(value, StandardCharsets.UTF_8));
			}
		}
		return result;
	}

	static double number(Map<String, String> q, String key, double fallback) {
		String value = q.get(key);
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			return Double.parseDouble(value.trim());
		} catch (NumberFormatException e) {
			throw new BadRequest("'" + key + "' must be a number, got '" + value + "'");
		}
	}

	static int integer(Map<String, String> q, String key, int fallback) {
		return (int) Math.round(number(q, key, fallback));
	}

	static boolean flag(Map<String, String> q, String key, boolean fallback) {
		String value = q.get(key);
		if (value == null || value.isBlank()) {
			return fallback;
		}
		return switch (value.trim().toLowerCase(Locale.ROOT)) {
			case "1", "true", "on", "yes" -> true;
			case "0", "false", "off", "no" -> false;
			default -> throw new BadRequest("'" + key + "' must be 0 or 1, got '" + value + "'");
		};
	}

	/** {@link #flag} for a bare value, as {@code /set} receives it. */
	static boolean flag(String value, boolean fallback) {
		return flag(Map.of("v", value), "v", fallback);
	}

	static String required(Map<String, String> q, String key) {
		String value = q.get(key);
		if (value == null || value.isBlank()) {
			throw new BadRequest("missing '" + key + "'");
		}
		return value;
	}

	static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
		String wanted = value.trim().toUpperCase(Locale.ROOT);
		for (E constant : type.getEnumConstants()) {
			if (constant.name().equals(wanted)) {
				return constant;
			}
		}
		StringBuilder names = new StringBuilder();
		for (E constant : type.getEnumConstants()) {
			names.append(names.isEmpty() ? "" : "|").append(constant.name().toLowerCase(Locale.ROOT));
		}
		throw new BadRequest("unknown value '" + value + "', expected " + names);
	}
}
