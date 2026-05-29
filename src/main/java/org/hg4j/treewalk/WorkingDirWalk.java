package org.hg4j.treewalk;

import org.hg4j.core.HgRepository;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WorkingDirWalk {

    private final WorkingDirTreeIterator iterator;
    private final HgRepository repository;
    private final List<Entry> cachedEntries = new ArrayList<>();
    private int cachedIndex = -1;

    public static class Entry {
        private final String path;
        private final File file;
        private final char state;
        private final boolean executable;
        private final long size;
        private final long lastModified;

        public Entry(String path, File file, char state, boolean executable, long size, long lastModified) {
            this.path = path;
            this.file = file;
            this.state = state;
            this.executable = executable;
            this.size = size;
            this.lastModified = lastModified;
        }

        public String getPath() {
            return path;
        }

        public File getFile() {
            return file;
        }

        public char getState() {
            return state;
        }

        public boolean isExecutable() {
            return executable;
        }

        public long getSize() {
            return size;
        }

        public long getLastModified() {
            return lastModified;
        }
    }

    public WorkingDirWalk(HgRepository repository) {
        this.repository = repository;
        this.iterator = new WorkingDirTreeIterator(repository);
    }

    public void reset() throws IOException {
        cachedIndex = -1;
    }

    private void loadAll() throws IOException {
        if (cachedEntries.isEmpty()) {
            iterator.reset();
            while (iterator.next()) {
                String path = iterator.getEntryPath();
                File diskFile = new File(repository.getDirectory(), path);
                cachedEntries.add(new Entry(
                    path,
                    diskFile,
                    iterator.getEntryState(),
                    iterator.isExecutable(),
                    diskFile.exists() ? diskFile.length() : 0,
                    diskFile.exists() ? diskFile.lastModified() / 1000 : 0
                ));
            }
        }
    }

    public boolean next() throws IOException {
        loadAll();
        if (cachedIndex < cachedEntries.size() - 1) {
            cachedIndex++;
            return true;
        }
        return false;
    }

    public Entry getEntry() {
        if (cachedIndex < 0 || cachedIndex >= cachedEntries.size()) {
            throw new java.util.NoSuchElementException("No current entry");
        }
        return cachedEntries.get(cachedIndex);
    }

    public List<Entry> getEntries() throws IOException {
        loadAll();
        return Collections.unmodifiableList(cachedEntries);
    }
}
