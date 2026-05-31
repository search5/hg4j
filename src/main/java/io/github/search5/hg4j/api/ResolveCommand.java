package io.github.search5.hg4j.api;

import io.github.search5.hg4j.core.HgRepository;
import io.github.search5.hg4j.core.SafeFileIO;

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
            String[] lines = content.split("\n");
            int lineNum = 0;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                
                // Skip the first two lines (P1 and P2 node ids) if they are not key-value format
                if (lineNum < 2 && trimmed.length() == 40 && !trimmed.contains("=")) {
                    lineNum++;
                    continue;
                }
                
                int eqIdx = trimmed.indexOf('=');
                if (eqIdx != -1) {
                    // Fallback to legacy path=true/false format
                    String path = trimmed.substring(0, eqIdx).trim();
                    boolean state = Boolean.parseBoolean(trimmed.substring(eqIdx + 1).trim());
                    states.put(path, state);
                } else if (trimmed.startsWith("u ") || trimmed.startsWith("U ")) {
                    String path = trimmed.substring(2).trim();
                    states.put(path, false);
                } else if (trimmed.startsWith("r ") || trimmed.startsWith("R ")) {
                    String path = trimmed.substring(2).trim();
                    states.put(path, true);
                } else {
                    // Other record types or standalone 40-char hashes not matched above
                    if (trimmed.length() == 40) {
                        lineNum++;
                    }
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

            // Extract parents from dirstate for standard compatibility
            String p1 = "0000000000000000000000000000000000000000";
            String p2 = "0000000000000000000000000000000000000000";
            try {
                io.github.search5.hg4j.core.Dirstate dirstate = repository.getDirstate();
                if (dirstate.getParent1() != null) {
                    p1 = io.github.search5.hg4j.core.NodeIdUtil.toHex(dirstate.getParent1());
                }
                if (dirstate.getParent2() != null) {
                    p2 = io.github.search5.hg4j.core.NodeIdUtil.toHex(dirstate.getParent2());
                }
            } catch (Exception ignored) {}

            StringBuilder sb = new StringBuilder();
            sb.append(p1).append("\n");
            sb.append(p2).append("\n");
            for (Map.Entry<String, Boolean> entry : states.entrySet()) {
                String prefix = entry.getValue() ? "r" : "u";
                sb.append(prefix).append(" ").append(entry.getKey()).append("\n");
            }
            SafeFileIO.writeStringAtomic(mergeStateFile, sb.toString());
            
            if (!list) {
                Map<String, Boolean> filtered = new LinkedHashMap<>();
                if (states.containsKey(fileToMark)) {
                    filtered.put(fileToMark, states.get(fileToMark));
                }
                return filtered;
            }
        }

        return states;
    }
}
