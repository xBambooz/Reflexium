package com.bambooz.reflexium.upscale.dlss;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

/** First-use runtime download against a loopback server. Run by Gradle check. */
public final class DlssRuntimeInstallerCheck {
	public static void main(String[] args) throws Exception {
		byte[] payload = "Reflexium runtime fixture".getBytes(StandardCharsets.UTF_8);
		String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
		var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/runtime", exchange -> {
			exchange.sendResponseHeaders(200, payload.length);
			try (var out = exchange.getResponseBody()) { out.write(payload); }
		});
		Path folder = Files.createTempDirectory("reflexium-runtime-check");
		server.start();
		try {
			var source = URI.create("http://localhost:" + server.getAddress().getPort() + "/runtime");
			for (String name : new String[] {DlssRuntimeInstaller.FILE_NAME, DlssRuntimeInstaller.FG_FILE_NAME}) {
				Path installed = DlssRuntimeInstaller.install(folder, source, hash, name);
				if (!Arrays.equals(Files.readAllBytes(installed), payload)) throw new AssertionError("Corrupted runtime " + name);
			}
			Files.writeString(folder.resolve(DlssRuntimeInstaller.FILE_NAME), "user runtime");
			DlssRuntimeInstaller.startInstall(folder, source, hash).join();
			if (!Files.readString(folder.resolve(DlssRuntimeInstaller.FILE_NAME)).equals("user runtime")) throw new AssertionError("Replaced user runtime");
			try {
				DlssRuntimeInstaller.install(folder, source, "0".repeat(64), "bad.dll");
				throw new AssertionError("Accepted wrong runtime hash");
			} catch (IOException expected) {
				if (Files.exists(folder.resolve("bad.dll")) || Files.exists(folder.resolve("bad.dll.part"))) throw new AssertionError("Left failed runtime download");
			}
		} finally {
			server.stop(0);
			try (var files = Files.list(folder)) { for (Path file : files.toList()) Files.delete(file); }
			Files.delete(folder);
		}
		System.out.println("DLSS runtime download checks passed: install, user runtime preserved, hash rejection");
	}
}
