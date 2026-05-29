package org.hg4j.api;

import org.hg4j.core.HgRepository;
import org.hg4j.core.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Porcelain command to manage merge conflict states (resolved vs unresolved).
 * Emulates Mercurial's 'hg resolve' command and saves metadata inside .hg/merge/state.
 */
public final class ResolveCommand {
    private final HgRepository repository;
    private String fileToMark;
    private boolean markResolved = false;
    private boolean markUnresolved = false;
    private boolean list = false;

    public ResolveCommand(HgRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("Repository cannot be null");
        }
        this.repository = repository;
    }

    public ResolveCommand setFile(String path) {
        this.fileToMark = path;
        return this;
    }

    public ResolveCommand markResolved(boolean resolved) {
        this.markResolved = resolved;
        return this;
    }

    public ResolveCommand markUnresolved(boolean unresolved) {
        this.markUnresolved = unresolved;
        return this;
    }

    public ResolveCommand list(boolean list) {
        this.list = list;
        return this;
    }

    /**
     * Executes conflict resolution status query or update.
     *
     * @return map of path to resolution state (true for resolved, false for unresolved)
     * @throws IOException if merge state file read/write fails
     */
    public Map<String, Boolean> call() throws IOException {
        File mergeStateFile = new File(repository.getHgDir(), "merge/state");
        Map<String, Boolean> states = new LinkedHashMap<>();

        // Load existing merge state
        if (mergeStateFile.exists() && mergeStateFile.isFile()) {
            String content = Files.readString(mergeStateFile.toPath(), StandardCharsets.UTF_8);
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                
                int eqIdx = trimmed.indexOf('=');
                if (eqIdx != -1) {
                    String path = trimmed.substring(0, eqIdx).trim();
                    boolean state = Boolean.parseBoolean(trimmed.substring(eqIdx + 1).trim());
                    states.put(path, state);
                }
            }
        }

        // Apply state updates if requested
        if (fileToMark != null) {
            if (markResolved) {
                states.put(fileToMark, true);
            } else if (markUnresolved) {
                states.put(fileToMark, false);
            }
            
            // Save merge state back to disk
            File parent = mergeStateFile.getParentFile();
            if (!parent.exists() && !parent.mkdirs()) {
                throw new IOException("Failed to create merge state directories");
            }

            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Boolean> entry : states.entrySet()) {
                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
            }
            SafeFileIO.writeStringAtomic(mergeStateFile, sb.toString());
        }

        return states;
    }
}
