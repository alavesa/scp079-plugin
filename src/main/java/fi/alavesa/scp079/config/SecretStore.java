package fi.alavesa.scp079.config;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.logging.Logger;

/**
 * The Gemini API key. It lives in secrets.yml, apart from config.yml, so that a config pasted into a
 * support chat never contains it; the environment variable {@value #ENV} overrides the file. The key is
 * never logged and only ever shown masked.
 */
public final class SecretStore {

    public static final String ENV = "SCP079_GEMINI_KEY";
    private static final String PATH = "gemini-api-key";
    private static final String HEADER = """
        SCP079 secrets - keep this file private.
        gemini-api-key: your Google AI Studio key (https://aistudio.google.com/apikey).
        The environment variable SCP079_GEMINI_KEY, when set, is used instead of this file.""";

    private final Path file;
    private final Logger log;
    /** Read by the HTTP threads. */
    private volatile String fileKey = "";

    public SecretStore(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public void load() {
        if (!Files.exists(file)) {
            write(""); // so the admin finds a file to edit
            return;
        }
        fileKey = clean(YamlConfiguration.loadConfiguration(file.toFile()).getString(PATH, ""));
    }

    /** @return the key in use, or "" when none is set */
    public String key() {
        String env = clean(System.getenv(ENV));
        return env.isEmpty() ? fileKey : env;
    }

    public boolean hasKey() { return !key().isEmpty(); }

    public boolean fromEnvironment() { return !clean(System.getenv(ENV)).isEmpty(); }

    public boolean set(String key) {
        fileKey = clean(key);
        return write(fileKey);
    }

    public boolean remove() {
        fileKey = "";
        return write("");
    }

    /** "AIzaSyD...wxyz" -> "AIza****wxyz"; short keys are hidden completely. */
    public static String mask(String key) {
        if (key == null || key.isEmpty()) return "";
        if (key.length() < 12) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    /** Replaces the key, should it ever appear in a text (an error body echoing the request), by its mask. */
    public String scrub(String text) {
        String key = key();
        return text == null || key.isEmpty() ? text : text.replace(key, mask(key));
    }

    private boolean write(String key) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(HEADER.lines().toList());
        yaml.set(PATH, key);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, yaml.saveToString());
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // Windows, or a file system without POSIX permissions: nothing to tighten
            }
            return true;
        } catch (IOException e) {
            log.warning("Could not write secrets.yml: " + e.getMessage());
            return false;
        }
    }

    private static String clean(String key) { return key == null ? "" : key.trim(); }
}
