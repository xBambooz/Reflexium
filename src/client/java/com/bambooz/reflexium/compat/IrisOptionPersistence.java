package com.bambooz.reflexium.compat;

import com.bambooz.reflexium.ReflexiumClient;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** User values and absence are kept separately from Iris's effective compile options. */
public final class IrisOptionPersistence {
    private record Snapshot(Set<String> keys, Map<String, String> originals) { }
    private static volatile Snapshot snapshot;
    private static boolean userChangesPending;
    private static final Set<String> loggedRepairs = new HashSet<>();

    private IrisOptionPersistence() { }
    public static void reset() { snapshot = null; userChangesPending = false; }
    public static void userChangesPending(boolean pending) { userChangesPending = pending; }

    static Map<String, String> prepare(Path directory, String pack, Map<String, String> values, Map<String, String> overrides) {
        if (overrides.isEmpty()) { reset(); return values; }
        Path record = directory.resolve(ComplementaryTemporalSource.sha256(java.util.List.of(pack)) + ".properties");
        try {
            Properties previous = new Properties();
            boolean recorded = Files.exists(record);
            if (recorded) try (var input = Files.newInputStream(record)) { previous.load(input); }
            Map<String, String> originals = new HashMap<>(values);
            boolean completeLeak = overrides.entrySet().stream().allMatch(e -> e.getValue().equals(values.get(e.getKey())));
            if (completeLeak && !userChangesPending) {
                for (String key : overrides.keySet()) {
                    if (recorded && !overrides.get(key).equals(previous.getProperty("override." + key)))
                        throw new IllegalStateException("Iris option record does not match the loaded option set: " + pack);
                    String original = recorded ? previous.getProperty("original." + key) : null;
                    if (original == null) originals.remove(key); else originals.put(key, original);
                }
                if (loggedRepairs.add(pack)) ReflexiumClient.LOGGER.info(
                        "Iris {}: repaired leaked Reflexium pack options {} using {}", pack,
                        overrides.keySet().stream().sorted().toList(), recorded ? "recorded user values" : "pack defaults");
            }
            Properties saved = new Properties();
            saved.setProperty("pack", pack);
            for (var entry : overrides.entrySet()) {
                saved.setProperty("override." + entry.getKey(), entry.getValue());
                if (originals.containsKey(entry.getKey())) saved.setProperty("original." + entry.getKey(), originals.get(entry.getKey()));
            }
            Files.createDirectories(directory);
            Path temp = Files.createTempFile(directory, "options-", ".tmp");
            try {
                try (var output = Files.newOutputStream(temp)) { saved.store(output, "Reflexium original Iris user options. Missing original keys mean pack default."); }
                try { Files.move(temp, record, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp, record, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temp); }
            snapshot = new Snapshot(Set.copyOf(overrides.keySet()), Map.copyOf(originals));
            return originals;
        } catch (IOException e) {
            // Fail the load before compile overrides can escape without a durable original-value record.
            throw new IllegalStateException("Could not protect Iris pack options for " + pack, e);
        }
    }

    /** Shared Iris writer, used by startup, reload, pack switches and screen Apply/Done. */
    public static Properties forSave(Properties effective) {
        Snapshot current = snapshot;
        if (current == null) return effective;
        Properties user = new Properties();
        user.putAll(effective);
        for (String key : current.keys) {
            String value = current.originals.get(key);
            if (value == null) user.remove(key); else user.setProperty(key, value);
        }
        return user;
    }
}
