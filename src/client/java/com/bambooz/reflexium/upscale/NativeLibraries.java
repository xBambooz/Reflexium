package com.bambooz.reflexium.upscale;

import com.bambooz.reflexium.upscale.dlss.DlssSupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/** The native DLLs shipped in the jar, extracted to {@code <game>/reflexium/natives} before loading. */
public final class NativeLibraries {
	private NativeLibraries() {
	}

	public static Path extract(String name) throws IOException {
		String resource = "/natives/windows-x86_64/" + name + ".dll";
		byte[] bytes;
		try (InputStream in = NativeLibraries.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IOException("native DLL missing from the jar: " + resource);
			}
			bytes = in.readAllBytes();
		}
		Path dir = DlssSupport.featureFolder().resolve("natives");
		Files.createDirectories(dir);
		Path target = dir.resolve(name + ".dll");
		if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), bytes)) {
			return target;
		}
		Path temp = Files.createTempFile(dir, name, ".tmp");
		Files.write(temp, bytes);
		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return target;
		} catch (IOException locked) {
			// Another running instance holds the old copy open: load a private copy instead.
			Path alternate = dir.resolve(name + "-" + ProcessHandle.current().pid() + ".dll");
			Files.move(temp, alternate, StandardCopyOption.REPLACE_EXISTING);
			alternate.toFile().deleteOnExit();
			return alternate;
		}
	}
}
