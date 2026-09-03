package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.merge.MergeState;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import io.github.search5.hg4j.errors.HgValidationException;
import java.util.List;

/**
 * Porcelain command to inspect/update merge conflict resolution state — the read/manage
 * counterpart of {@link MergeCommand}. Both now operate on the same real on-disk state
 * ({@code .hg/merge/state2}, via {@link MergeState}), so a conflict left by {@code
 * MergeCommand} can actually be marked resolved/unresolved here, and the result is visible
 * to real {@code hg resolve --list} (see {@code MergeStateInteropTest}).
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
     * Executes conflict resolution status query or update against {@code .hg/merge/state2}.
     *
     * @return map of path to resolution state (true for resolved, false for unresolved)
     * @throws IOException if merge state file read/write fails, or if a resolution update
     *         was requested while there is no merge in progress (or for a path that is not
     *         part of it) — matching real hg's refusal to resolve outside a merge.
     */
    public Map<String, Boolean> call() throws IOException {
        File mergeStateFile = new File(repository.getHgDir(), "merge/state2");
        MergeState mergeState = MergeState.read(mergeStateFile);

        if (fileToMark != null && (markResolved || markUnresolved)) {
            if (!mergeState.isActive()) {
                throw new HgValidationException(
                        "resolve command not applicable when not merging");
            }
            if (!mergeState.hasFile(fileToMark)) {
                throw new HgValidationException(
                        fileToMark + " is not part of the current merge, nothing to resolve");
            }

            if (markResolved) {
                mergeState.markResolved(fileToMark);
            } else {
                mergeState.markUnresolved(fileToMark);
            }
            mergeState.write(mergeStateFile);

            if (!list) {
                Map<String, Boolean> filtered = new LinkedHashMap<>();
                filtered.put(fileToMark, isResolved(mergeState, fileToMark));
                return filtered;
            }
        }

        Map<String, Boolean> states = new LinkedHashMap<>();
        for (String path : mergeState.state.keySet()) {
            states.put(path, isResolved(mergeState, path));
        }
        return states;
    }

    private static boolean isResolved(MergeState mergeState, String path) {
        List<String> fields = mergeState.state.get(path);
        if (fields == null || fields.isEmpty()) {
            return false;
        }
        return MergeState.RESOLVED.equals(fields.get(0)) || MergeState.RESOLVED_PATH.equals(fields.get(0));
    }
}
