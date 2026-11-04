package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;

import java.io.IOException;
import io.github.search5.hg4j.errors.HgValidationException;

/**
 * Porcelain command corresponding to {@code hg forget} — stops tracking a file without touching
 * it on disk (unlike {@link RemoveCommand}, which deletes the working copy file).
 */
public class ForgetCommand {
    private final HgRepository repository;
    private String file;

    public ForgetCommand(HgRepository repository) {
        this.repository = repository;
    }


    private final FileSelection selection = new FileSelection();
    private final java.util.List<String> warnings = new java.util.ArrayList<>();

    /** The files to work on, as patterns ({@code kind:pattern}, {@code set:EXPRESSION}); a bare name is relative to the current directory. */
    public ForgetCommand setPatterns(String... patterns) {
        selection.patterns(patterns);
        return this;
    }

    /** {@code -I}: only files matching these patterns (globs by default). */
    public ForgetCommand setInclude(String... patterns) {
        selection.include(patterns);
        return this;
    }

    /** {@code -X}: not the files matching these patterns (globs by default). */
    public ForgetCommand setExclude(String... patterns) {
        selection.exclude(patterns);
        return this;
    }

    /** The current directory relative to the repository root, against which relative patterns are read. */
    public ForgetCommand setCwd(String cwd) {
        selection.cwd(cwd);
        return this;
    }

    /** What the last {@link #callSelected()} left undone, in Mercurial's words. */
    public java.util.List<String> getWarnings() {
        return warnings;
    }

    /**
     * {@code hg forget [FILE]...}: stops tracking the tracked files the patterns select (a file added but not committed becomes
     * unknown, a committed one is marked removed; the files stay on the disk). Needs at least one pattern.
     *
     * @return the forgotten files
     */
    public java.util.List<String> callSelected() throws IOException, HgLockException {
        if (selection.patternCount() == 0) {
            throw new HgValidationException("no files specified");
        }
        warnings.clear();
        io.github.search5.hg4j.match.Matcher matcher = selection.matcher(repository, null);
        java.util.List<String> forgotten = new java.util.ArrayList<>();
        try (HgLock wlock = repository.lockWorkingCopy()) {
            Dirstate dirstate = repository.getDirstate();
            for (java.util.Map.Entry<String, Dirstate.Entry> e : new java.util.TreeMap<>(dirstate.getEntries()).entrySet()) {
                String path = e.getKey();
                if (e.getValue().getState() == 'r' || !matcher.matches(path)) {
                    continue;
                }
                if (e.getValue().getState() == 'a') {
                    dirstate.removeEntry(path);
                } else {
                    dirstate.addEntry(path, new Dirstate.Entry('r', 0, 0, 0));
                }
                forgotten.add(path);
            }
            repository.writeDirstate(dirstate);
        }
        return forgotten;
    }

    public ForgetCommand setFile(String file) {
        this.file = file;
        return this;
    }

    public void call() throws IOException, HgLockException {
        if (file == null || file.isEmpty()) {
            throw new IllegalStateException("File path must be specified.");
        }
        try (HgLock wlock = repository.lockWorkingCopy()) {
            Dirstate dirstate = repository.getDirstate();
            Dirstate.Entry entry = dirstate.getEntries().get(file);
            if (entry == null) {
                throw new HgValidationException("File is not tracked: " + file);
            }

            if (entry.getState() == 'a') {
                // 아직 커밋된 적 없는 파일 — 그냥 추적을 완전히 해제한다.
                dirstate.removeEntry(file);
            } else {
                // 이미 커밋된 파일 — 다음 커밋 시 제거로 기록하되 작업 사본은 그대로 둔다.
                dirstate.addEntry(file, new Dirstate.Entry('r', 0, 0, 0));
            }
            repository.writeDirstate(dirstate);
        }
    }
}
