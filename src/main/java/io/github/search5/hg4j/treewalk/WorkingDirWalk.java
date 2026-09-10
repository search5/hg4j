package io.github.search5.hg4j.treewalk;

import io.github.search5.hg4j.lib.HgRepository;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.AbstractList;
import java.util.Iterator;

/**
 * Convenience facade over {@link WorkingDirTreeIterator} that provides an {@code Entry}-based
 * API exposing each path's on-disk {@link java.io.File}, dirstate state, size, and mtime
 * together in one object.
 *
 * @apiNote Merges tracked (dirstate) and untracked (a plain directory scan via {@link
 *     io.github.search5.hg4j.lib.HgRepository#scanWorkingCopy()}) paths into a single sorted
 *     walk, so a caller sees every relevant working-copy path in one pass regardless of whether
 *     it is already tracked. Used directly by {@code io.github.search5.hg4j.api.Hg}'s
 *     convenience helpers; most porcelain commands instead use {@link WorkingDirTreeIterator}
 *     directly as one side of a {@link TreeWalk}.
 */
public class WorkingDirWalk {

    private final WorkingDirTreeIterator iterator;
    private final HgRepository repository;
    private final List<Entry> cachedEntries = new ArrayList<>();
    private int cachedIndex = -1;

    /** One working-copy path, combining its on-disk {@link File} with its dirstate state, size, and mtime. */
    public static class Entry {
        private final String path;
        private final File file;
        private final char state;
        private final boolean executable;
        private final long size;
        private final long lastModified;

        /**
         * Creates an entry combining a working-copy path with its on-disk and dirstate attributes.
         *
         * @param path repository-relative path
         * @param file the path's on-disk {@link File}
         * @param executable {@code true} if the on-disk file has the executable bit set
         * @param size on-disk file size in bytes, or {@code 0} if the file does not exist
         * @param lastModified on-disk last-modified time, in seconds since the epoch, or
         *     {@code 0} if the file does not exist
         * @param state dirstate state character ({@code 'n'}, {@code 'a'}, {@code 'r'}, {@code
         *     'm'}, or {@code '?'} for an untracked path)
         */
        public Entry(String path, File file, char state, boolean executable, long size, long lastModified) {
            this.path = path;
            this.file = file;
            this.state = state;
            this.executable = executable;
            this.size = size;
            this.lastModified = lastModified;
        }

        /**
         * Returns the repository-relative path.
         *
         * @return the repository-relative path
         */
        public String getPath() {
            return path;
        }

        /**
         * Returns the path's on-disk file.
         *
         * @return the path's on-disk file
         */
        public File getFile() {
            return file;
        }

        /**
         * Returns the dirstate state character.
         *
         * @return the dirstate state character ({@code 'n'}, {@code 'a'}, {@code 'r'}, {@code
         *     'm'}, or {@code '?'} for an untracked path)
         */
        public char getState() {
            return state;
        }

        /**
         * Returns whether the on-disk file has the executable bit set.
         *
         * @return {@code true} if the on-disk file has the executable bit set
         */
        public boolean isExecutable() {
            return executable;
        }

        /**
         * Returns the on-disk file size.
         *
         * @return the on-disk file size in bytes, or {@code 0} if the file does not exist
         */
        public long getSize() {
            return size;
        }

        /**
         * Returns the on-disk last-modified time.
         *
         * @return the on-disk last-modified time, in seconds since the epoch, or {@code 0} if the
         *     file does not exist
         */
        public long getLastModified() {
            return lastModified;
        }
    }

    /**
     * Creates a working directory walk over the given repository.
     *
     * @param repository repository whose working copy (tracked and untracked paths) is walked
     */
    public WorkingDirWalk(HgRepository repository) {
        this.repository = repository;
        this.iterator = new WorkingDirTreeIterator(repository);
    }

    /**
     * Resets the walk's cursor so a subsequent {@link #next()} starts over from the first entry
     * again (the already-loaded entry cache, if any, is reused).
     *
     * @throws IOException declared for interface symmetry with the underlying iterator; not
     *     currently thrown by this implementation
     */
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

    /**
     * Advances to the next entry, loading (and caching) the full walk on first call.
     *
     * @return {@code true} if there is a next entry (now current, retrievable via {@link
     *     #getEntry()}), {@code false} if the walk is exhausted
     * @throws IOException if the underlying tracked/untracked scan fails
     */
    public boolean next() throws IOException {
        loadAll();
        if (cachedIndex < cachedEntries.size() - 1) {
            cachedIndex++;
            return true;
        }
        return false;
    }

    /**
     * Returns the current entry, as last advanced to by {@link #next()}.
     *
     * @return the current entry
     * @throws NoSuchElementException if {@link #next()} has not been called, or the walk is
     *     already exhausted
     */
    public Entry getEntry() {
        if (cachedIndex < 0 || cachedIndex >= cachedEntries.size()) {
            throw new NoSuchElementException("No current entry");
        }
        return cachedEntries.get(cachedIndex);
    }

    /**
     * Returns a lazily-populated view of every entry in the walk, backed by {@link #lazyEntries()}.
     *
     * @return a list of every working-copy entry, computed on demand as it is accessed
     * @throws IOException declared for interface symmetry with the underlying iterator; actual
     *     I/O failures surface lazily as unchecked exceptions when the returned list is accessed
     */
    public List<Entry> getEntries() throws IOException {
        return new AbstractList<Entry>() {
            private final List<Entry> cache = new ArrayList<>();
            private final Iterator<Entry> it = lazyEntries();

            @Override
            public Entry get(int index) {
                while (cache.size() <= index && it.hasNext()) {
                    cache.add(it.next());
                }
                if (index < cache.size()) {
                    return cache.get(index);
                }
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
            }

            @Override
            public int size() {
                while (it.hasNext()) {
                    cache.add(it.next());
                }
                return cache.size();
            }

            @Override
            public Iterator<Entry> iterator() {
                return lazyEntries();
            }
        };
    }

    /**
     * Provides JGit-{@code TreeWalk}-style lazy streaming traversal.
     * Streams entries sequentially as needed rather than preloading them all (avoids heap pressure).
     *
     * @return an iterator over every working-copy entry, computed one at a time as it is consumed
     */
    public Iterator<Entry> lazyEntries() {
        return new Iterator<Entry>() {
            private final WorkingDirTreeIterator it = iterator;
            private boolean initialized = false;

            private void init() {
                if (!initialized) {
                    try {
                        it.reset();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    initialized = true;
                }
            }

            @Override
            public boolean hasNext() {
                init();
                try {
                    return it.next();
                } catch (IOException e) {
                    return false;
                }
            }

            @Override
            public Entry next() {
                init();
                String path = it.getEntryPath();
                File diskFile = new File(repository.getDirectory(), path);
                return new Entry(
                    path,
                    diskFile,
                    it.getEntryState(),
                    it.isExecutable(),
                    diskFile.exists() ? diskFile.length() : 0,
                    diskFile.exists() ? diskFile.lastModified() / 1000 : 0
                );
            }
        };
    }
}
