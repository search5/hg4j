package io.github.search5.hg4j.treewalk;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgRepository;
import java.io.File;
import java.io.IOException;
import java.util.*;
import io.github.search5.hg4j.util.NodeIdUtil;

/**
 * TreeIterator implementation that traverses the physical working copy and dirstate information.
 *
 * @apiNote Used as one side of a {@link TreeWalk} by {@code StatusCommand}, {@code
 *     CommitCommand}, and {@code GraftCommand} to compare the working directory against a
 *     manifest. Merges physically-present files ({@link
 *     io.github.search5.hg4j.lib.HgRepository#scanWorkingCopy()}) with dirstate-tracked paths, so
 *     an untracked file still appears (with state {@code '?'}).
 */
public class WorkingDirTreeIterator implements TreeIterator {

    private final HgRepository repository;
    private final List<Entry> entries = new ArrayList<>();
    private int index = -1;

    private static class Entry {
        final String path;
        final boolean executable;
        final char state;

        Entry(String path, boolean executable, char state) {
            this.path = path;
            this.executable = executable;
            this.state = state;
        }
    }

    /**
     * Creates an iterator over the given repository's working directory and dirstate.
     *
     * @param repository repository whose working copy and dirstate are traversed
     */
    public WorkingDirTreeIterator(HgRepository repository) {
        this.repository = repository;
    }

    @Override
    public void reset() throws IOException {
        entries.clear();
        index = -1;
        loadEntries();
    }

    private void loadEntries() throws IOException {
        Dirstate dirstate = repository.getDirstate();
        Map<String, Dirstate.Entry> tracked = dirstate.getEntries();

        List<String> physicalFiles = repository.scanWorkingCopy();
        Set<String> allPaths = new HashSet<>(physicalFiles);
        allPaths.addAll(tracked.keySet());

        for (String relPath : allPaths) {

            File diskFile = new File(repository.getDirectory(), relPath);
            Dirstate.Entry dEntry = tracked.get(relPath);

            char state;
            boolean executable = false;

            if (dEntry != null) {
                state = dEntry.getState();
                if (diskFile.exists() && diskFile.isFile()) {
                    executable = diskFile.canExecute();
                }
            } else {
                state = '?'; // Untracked
                if (diskFile.exists() && diskFile.isFile()) {
                    executable = diskFile.canExecute();
                }
            }

            entries.add(new Entry(relPath, executable, state));
        }

        entries.sort((e1, e2) -> NodeIdUtil.UTF8_STRING_COMPARATOR.compare(e1.path, e2.path));
    }

    @Override
    public String getEntryPath() {
        if (index >= 0 && index < entries.size()) {
            return entries.get(index).path;
        }
        return null;
    }

    @Override
    public byte[] getEntryNodeId() {
        // Working directory does not have commit node IDs directly associated on-the-fly
        return null;
    }

    @Override
    public boolean isExecutable() {
        if (index >= 0 && index < entries.size()) {
            return entries.get(index).executable;
        }
        return false;
    }

    @Override
    public char getEntryState() {
        if (index >= 0 && index < entries.size()) {
            return entries.get(index).state;
        }
        return '?';
    }

    @Override
    public boolean next() throws IOException {
        if (index < entries.size() - 1) {
            index++;
            return true;
        }
        index = entries.size(); // mark as finished
        return false;
    }
}
