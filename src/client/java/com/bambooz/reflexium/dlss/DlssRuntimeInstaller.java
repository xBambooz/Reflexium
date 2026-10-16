package com.bambooz.reflexium.dlss;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Downloads NVIDIA's nvngx_dlss.dll on first use so players do not install it by hand. The download
 * runs on its own thread. A file that already exists is never overwritten, whatever its hash.
 */
public final class DlssRuntimeInstaller {
	private static final Logger LOGGER = LoggerFactory.getLogger("Reflexium");

	/*
	 * NVIDIA/DLSS release v310.9.1 (latest on 2026-10-13). The URL names the commit behind that tag
	 * so it cannot move. The SHA-256 is of lib/Windows_x86_64/rel/nvngx_dlss.dll at that commit.
	 */
	static final String VERSION = "310.9.1";
	static final URI SOURCE = URI.create("https://raw.githubusercontent.com/NVIDIA/DLSS/374959484e79a640feaba44c93ac8cfb0a03f5b5/lib/Windows_x86_64/rel/nvngx_dlss.dll");
	static final String SHA256 = "3975567b8943c53acce397f2b72380092f84f162d00b0d2c7d08a1025c563983";
	static final String FILE_NAME = "nvngx_dlss.dll";
	static final String FG_FILE_NAME = "nvngx_dlssg.dll";
	static final URI FG_SOURCE = URI.create("https://raw.githubusercontent.com/NVIDIA/DLSS/374959484e79a640feaba44c93ac8cfb0a03f5b5/lib/Windows_x86_64/rel/nvngx_dlssg.dll");
	static final String FG_SHA256 = "ff6e90eb78b827927dff5b4ecc6b1c870c2e9bca29ed9f48c7d348cc9e170b82";
	static final String LICENSE_NAME = "NVIDIA_DLSS_LICENSE.txt";
	/** Set to {@code false} with -D to stop automatic downloads. */
	static final String OPT_OUT_PROPERTY = "reflexium.dlss.autoDownload";

	private static final String LICENSE_TEXT = String.join(System.lineSeparator(),
			"NVIDIA DLSS runtime: " + FILE_NAME + " version " + VERSION,
			"",
			"This file is the NVIDIA DLSS Super Resolution runtime, redistributed under the NVIDIA RTX SDKs license.",
			"Source: https://github.com/NVIDIA/DLSS (release v" + VERSION + ")",
			"SHA-256: " + SHA256,
			"Reflexium downloads this file automatically when it is missing.",
			"");

	public enum Phase {IDLE, READY, DOWNLOADING, INSTALLED, FAILED, DISABLED}

	/** Immutable snapshot for UI. {@code detail} holds the failure reason or the disabled explanation. */
	public record State(Phase phase, long downloadedBytes, String detail) {
		public String message() {
			return switch (phase) {
				case DOWNLOADING -> String.format(Locale.ROOT, "downloading DLSS runtime (%.1f MB)", downloadedBytes / 1048576.0);
				case INSTALLED -> "DLSS runtime installed, enabling";
				case FAILED -> "DLSS runtime download failed: " + detail;
				case DISABLED -> detail;
				case IDLE, READY -> "";
			};
		}
	}

	private static volatile State state = new State(Phase.IDLE, 0, "");
	private static CompletableFuture<Path> pending;
	/** Frame generation's runtime is optional: it never holds back super resolution. */
	private static CompletableFuture<Path> fgPending;

	private DlssRuntimeInstaller() {
	}

	public static State state() {
		return state;
	}
	/** Super resolution's runtime only. */
	static synchronized boolean hasPendingWork() { return pending != null && !pending.isDone(); }

	/**
	 * Forgets failed downloads so the next {@link #ensureInstalledAsync()} starts again, for example after the
	 * network returns or the player places the DLL by hand. Explicit, never per frame. True when anything reset.
	 */
	public static synchronized boolean retryFailed() {
		boolean reset = false;
		if (pending != null && pending.isCompletedExceptionally()) { pending = null; reset = true; }
		if (fgPending != null && fgPending.isCompletedExceptionally()) { fgPending = null; reset = true; }
		if (reset) state = new State(Phase.IDLE, 0, "");
		return reset;
	}

	/**
	 * Starts the download when nvngx_dlss.dll is missing and returns without waiting. The future
	 * completes with the DLL path, or fails with the reason. Callers check for an NVIDIA GPU first.
	 */
	public static synchronized CompletableFuture<Path> ensureInstalledAsync() {
		Path folder = DlssSupport.featureFolder();
		if (pending == null) pending = startInstall(folder, SOURCE, SHA256);
		// Sequential so one download runs at a time, but SR completes (and initializes) without waiting for FG.
		if (fgPending == null) fgPending = pending.thenCompose(sr -> startInstall(folder, FG_SOURCE, FG_SHA256, FG_FILE_NAME));
		return pending;
	}

	static CompletableFuture<Path> startInstall(Path folder, URI source, String sha256) {
		return startInstall(folder, source, sha256, FILE_NAME);
	}

	private static CompletableFuture<Path> startInstall(Path folder, URI source, String sha256, String fileName) {
		Path target = folder.resolve(fileName);
		if (Files.isRegularFile(target)) {
			state = new State(Phase.READY, 0, "");
			return CompletableFuture.completedFuture(target);
		}
		if (!isWindows64()) {
			return disabled("DLSS runtime missing, automatic download needs 64-bit Windows");
		}
		if ("false".equalsIgnoreCase(System.getProperty(OPT_OUT_PROPERTY))) {
			return disabled("DLSS runtime missing, automatic download disabled");
		}
		CompletableFuture<Path> future = new CompletableFuture<>();
		state = new State(Phase.DOWNLOADING, 0, "");
		Thread.ofPlatform().name("Reflexium DLSS runtime").daemon(true).start(() -> {
			try {
				future.complete(install(folder, source, sha256, fileName));
			} catch (Exception e) {
				String reason = e.getMessage() != null ? e.getMessage() : e.toString();
				state = new State(Phase.FAILED, 0, reason);
				LOGGER.warn("DLSS runtime download failed: {}", reason);
				future.completeExceptionally(e);
			}
		});
		return future;
	}

	/**
	 * Downloads to a .part file, checks the SHA-256, then moves it into place. Package-private so
	 * tests can run it against a temp folder.
	 */
	static Path install(Path folder, URI source, String sha256) throws IOException, InterruptedException, NoSuchAlgorithmException {
		return install(folder, source, sha256, FILE_NAME);
	}

	static Path install(Path folder, URI source, String sha256, String fileName) throws IOException, InterruptedException, NoSuchAlgorithmException {
		Path target = folder.resolve(fileName);
		Files.createDirectories(folder);
		Path part = folder.resolve(fileName + ".part");
		Files.deleteIfExists(part);
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			HttpClient client = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(30))
					.followRedirects(HttpClient.Redirect.NORMAL)
					.build();
			HttpRequest request = HttpRequest.newBuilder(source).timeout(Duration.ofMinutes(10)).GET().build();
			HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() != 200) {
				response.body().close();
				throw new IOException("HTTP " + response.statusCode());
			}
			long copied = 0;
			try (InputStream in = response.body(); OutputStream out = new DigestOutputStream(Files.newOutputStream(part), digest)) {
				byte[] buffer = new byte[64 * 1024];
				for (int n; (n = in.read(buffer)) != -1; ) {
					out.write(buffer, 0, n);
					copied += n;
					state = new State(Phase.DOWNLOADING, copied, "");
				}
			}
			String actual = HexFormat.of().formatHex(digest.digest());
			if (!actual.equalsIgnoreCase(sha256)) {
				throw new IOException("SHA-256 mismatch (got " + actual + ")");
			}
			if (Files.exists(target)) {
				// A user file appeared during the download. Keep it.
				state = new State(Phase.READY, 0, "");
				return target;
			}
			Files.writeString(folder.resolve(LICENSE_NAME), LICENSE_TEXT + "\nFrame Generation runtime: " + FG_FILE_NAME + "\nSHA-256: " + FG_SHA256 + "\n");
			Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
			state = new State(Phase.INSTALLED, copied, "");
			LOGGER.info("DLSS runtime {} installed ({} MB) at {}", VERSION, String.format(Locale.ROOT, "%.1f", copied / 1048576.0), target);
			return target;
		} finally {
			Files.deleteIfExists(part);
		}
	}

	private static CompletableFuture<Path> disabled(String reason) {
		state = new State(Phase.DISABLED, 0, reason);
		return CompletableFuture.failedFuture(new IOException(reason));
	}

	private static boolean isWindows64() {
		String arch = System.getProperty("os.arch", "");
		return System.getProperty("os.name", "").startsWith("Windows") && (arch.equals("amd64") || arch.equals("x86_64"));
	}
}
