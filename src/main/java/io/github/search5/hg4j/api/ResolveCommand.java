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

    /**
     * Creates the command.
     * @param repository the repository whose merge state is used; must not be {@code null}
     * @throws IllegalArgumentException if {@code repository} is {@code null}
     */
    public ResolveCommand(HgRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("Repository cannot be null");
        }
        this.repository = repository;
    }

    /**
     * Sets the file to mark.
     * @param path the repository-relative path of a file in the current merge state; {@code null} (the default)
     *         marks nothing. It only has an effect together with {@link #markResolved} or {@link #markUnresolved}
     * @return {@code this}
     */
    public ResolveCommand setFile(String path) {
        this.fileToMark = path;
        return this;
    }

    /**
     * Requests that the file set with {@link #setFile} be marked resolved.
     * @param resolved {@code true} to mark it (like {@code hg resolve --mark}); default {@code false}
     * @return {@code this}
     */
    public ResolveCommand markResolved(boolean resolved) {
        this.markResolved = resolved;
        return this;
    }

    /**
     * Requests that the file set with {@link #setFile} be marked unresolved.
     * @param unresolved {@code true} to mark it (like {@code hg resolve --unmark}); default {@code false}. Ignored
     *         if {@link #markResolved} is also {@code true}
     * @return {@code this}
     */
    public ResolveCommand markUnresolved(boolean unresolved) {
        this.markUnresolved = unresolved;
        return this;
    }

    /**
     * Sets whether the state of all conflicted files is returned even when a single file was marked.
     * @param list {@code true} to return every file of the merge state (like {@code hg resolve --list}); the default
     *         {@code false} returns only the marked file when one was marked. Without a marking, all files are returned
     *         either way
     * @return {@code this}
     */
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
    public Map<String, Boolean> call() throws IOException, io.github.search5.hg4j.errors.HgLockException {
        // Like real hg, the locks are taken before the first read of the repository state and released when the command ends.
        int lockTimeout = repository.resolvePushLockTimeoutMs();
        try (io.github.search5.hg4j.lib.HgLock wlock = repository.lockWorkingCopy(lockTimeout)) {
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
    }

    private static boolean isResolved(MergeState mergeState, String path) {
        List<String> fields = mergeState.state.get(path);
        if (fields == null || fields.isEmpty()) {
            return false;
        }
        return MergeState.RESOLVED.equals(fields.get(0)) || MergeState.RESOLVED_PATH.equals(fields.get(0));
    }
}
