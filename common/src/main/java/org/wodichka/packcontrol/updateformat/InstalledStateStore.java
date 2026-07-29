package org.wodichka.packcontrol.updateformat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.Optional;

public final class InstalledStateStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path statePath;

    public InstalledStateStore(Path instanceRoot) {
        this.statePath = instanceRoot.toAbsolutePath().normalize()
                .resolve(".packcontrol")
                .resolve("installed-state.json");
    }

    public Path statePath() {
        return statePath;
    }

    public Optional<InstalledPackState> load() throws IOException {
        if (Files.notExists(statePath)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(statePath)) {
            InstalledPackState state = GSON.fromJson(reader, InstalledPackState.class);
            if (state == null) {
                throw new JsonParseException("installed-state.json is empty");
            }
            return Optional.of(state);
        } catch (JsonParseException exception) {
            throw new IOException("Invalid installed-state.json", exception);
        }
    }

    public void save(InstalledPackState state) throws IOException {
        Files.createDirectories(statePath.getParent());
        Path temporary = statePath.resolveSibling(statePath.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary)) {
            GSON.toJson(state, writer);
        }
        moveReplacing(temporary, statePath);
    }

    /**
     * Creates the initial state without replacing a state written by another
     * bootstrap or update process.
     */
    public boolean saveNew(InstalledPackState state) throws IOException {
        Files.createDirectories(statePath.getParent());
        if (Files.exists(statePath)) {
            return false;
        }
        Path temporary = statePath.resolveSibling(
                statePath.getFileName() + ".bootstrap-" + UUID.randomUUID() + ".tmp"
        );
        try {
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                GSON.toJson(state, writer);
            }
            try {
                Files.move(temporary, statePath);
                return true;
            } catch (FileAlreadyExistsException exception) {
                return false;
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void delete() throws IOException {
        Files.deleteIfExists(statePath);
    }

    static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
