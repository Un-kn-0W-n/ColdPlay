package coldplay.config;

import coldplay.util.ChatUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;

/** Loads and saves one JSON file with defaults, corrupt-file backup and atomic replace on save. */
public final class JsonStore<T> {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final File file;
    private final Class<T> type;
    private final Supplier<T> defaults;
    private final String label;
    private boolean saveBlocked;
    private boolean errorReported;

    public JsonStore(File file, Class<T> type, Supplier<T> defaults, String label) {
        this.file = file;
        this.type = type;
        this.defaults = defaults;
        this.label = label;
    }

    public T load() {
        if (!file.exists()) {
            return defaultValue();
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            T parsed = GSON.fromJson(reader, type);
            return parsed == null ? defaultValue() : parsed;
        } catch (Exception exception) {
            report("load", exception.getMessage());
            saveBlocked = !backupCorruptFile();
            return defaultValue();
        }
    }

    public boolean save(T value) {
        if (saveBlocked) {
            return false;
        }

        File directory = file.getParentFile();
        if (directory != null && !directory.exists() && !directory.mkdirs() && !directory.exists()) {
            report("create directory for", String.valueOf(directory));
            return false;
        }

        File temporary = new File(directory, file.getName() + ".tmp");
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(temporary), StandardCharsets.UTF_8))) {
            GSON.toJson(value, writer);
        } catch (Exception exception) {
            report("save", exception.getMessage());
            deleteQuietly(temporary);
            return false;
        }

        try {
            try {
                Files.move(temporary.toPath(), file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception exception) {
            report("replace", exception.getMessage());
            deleteQuietly(temporary);
            return false;
        }
    }

    public File getFile() {
        return file;
    }

    public boolean isSaveBlocked() {
        return saveBlocked;
    }

    private T defaultValue() {
        return defaults.get();
    }

    /** Logs every failure but reports to chat only once per store, so a dead disk does not spam. */
    private void report(String what, String detail) {
        System.err.println("[ColdPlay] Failed to " + what + " " + label + ": " + detail);
        if (!errorReported) {
            errorReported = true;
            ChatUtil.error("Failed to " + what + " " + label);
        }
    }

    private boolean backupCorruptFile() {
        File backup = new File(file.getParentFile(), file.getName() + ".bak");
        try {
            Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception exception) {
            report("back up corrupt", exception.getMessage());
            return false;
        }
    }

    private static void deleteQuietly(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (Exception ignored) {
        }
    }
}
