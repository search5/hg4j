package io.github.search5.hg4j.treewalk;

import io.github.search5.hg4j.lib.HgRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import io.github.search5.hg4j.util.NodeIdUtil;
import java.util.AbstractList;
import java.util.Iterator;

/**
 * Convenience facade over {@link ManifestTreeIterator} that provides an {@code Entry}-based,
 * JGit-{@code TreeWalk}-style API (cursor-based {@link #next()}/{@link #getEntry()}, an eager
 * {@link #getEntries()} list, and a {@link #lazyEntries()} lazy iterator).
 *
 * @apiNote Used by {@link io.github.search5.hg4j.storage.DefaultFileStoreEngine#getManifestAtCommit}
 *     and directly by {@code StatusCommand}, {@code ShelveCommand}, {@code RebaseCommand}, {@code
 *     BisectCommand}, and {@code io.github.search5.hg4j.api.Hg} whenever a revision's
 *     tracked-file listing is needed without dealing with {@link TreeWalk}'s multi-tree API.
 */
public class ManifestWalk {

    private final ManifestTreeIterator iterator;
    private final List<Entry> cachedEntries = new ArrayList<>();
    private int cachedIndex = -1;

    /** A single tracked-file record from a manifest: path, node ID, and file mode flags. */
    public static class Entry {
        private final String path;
        private final byte[] nodeId;
        private final boolean executable;
        private final boolean symlink;

        /**
         * Creates an entry with the given path and mode.
         *
         * @param path repository-relative file path
         * @param nodeId filelog node ID of this file's content at this revision
         * @param executable whether the file has the executable bit set
         * @param symlink whether the file is a symlink
         */
        public Entry(String path, byte[] nodeId, boolean executable, boolean symlink) {
            this.path = path;
            this.nodeId = nodeId;
            this.executable = executable;
            this.symlink = symlink;
        }

        /**
         * Returns the file path.
         *
         * @return repository-relative file path
         */
        public String getPath() {
            return path;
        }

        /**
         * Returns the filelog node ID.
         *
         * @return filelog node ID of this file's content at this revision
         */
        public byte[] getNodeId() {
            return nodeId;
        }

        /**
         * Returns the filelog node ID as a hex string.
         *
         * @return hex-encoded filelog node ID
         */
        public String getNodeIdHex() {
            return NodeIdUtil.toHex(nodeId);
        }

        /**
         * Returns whether the file is executable.
         *
         * @return {@code true} if the executable bit is set
         */
        public boolean isExecutable() {
            return executable;
        }

        /**
         * Returns whether the file is a symlink.
         *
         * @return {@code true} if the file is a symlink
         */
        public boolean isSymlink() {
            return symlink;
        }
    }

    /**
     * Creates an instance walking the manifest of the given revision.
     *
     * @param repository repository the manifest is read from
     * @param revision revision identifier whose manifest is walked
     */
    public ManifestWalk(HgRepository repository, String revision) {
        this.iterator = new ManifestTreeIterator(repository, revision);
    }

    /**
     * Creates an instance walking a manifest identified directly by its node ID.
     *
     * @param repository repository the manifest is read from
     * @param manifestNode node ID of the manifest revision to walk
     */
    public ManifestWalk(HgRepository repository, byte[] manifestNode) {
        this.iterator = new ManifestTreeIterator(repository, manifestNode);
    }

    /**
     * Rewinds the cursor so the next {@link #next()} call returns the first entry again.
     *
     * @throws IOException if the underlying iterator cannot be reset
     */
    public void reset() throws IOException {
        cachedIndex = -1;
    }

    private void loadAll() throws IOException {
        if (cachedEntries.isEmpty()) {
            iterator.reset();
            while (iterator.next()) {
                cachedEntries.add(new Entry(
                    iterator.getEntryPath(),
                    iterator.getEntryNodeId(),
                    iterator.isExecutable(),
                    iterator.isSymlink()
                ));
            }
        }
    }

    /**
     * Advances the cursor to the next manifest entry, loading and caching every entry on first
     * call.
     *
     * @return {@code true} if a next entry is available (retrievable via {@link #getEntry()}),
     *     {@code false} once the manifest is exhausted
     * @throws IOException if the manifest cannot be read
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
     * Returns the entry at the current cursor position.
     *
     * @return the current entry
     * @throws NoSuchElementException if {@link #next()} has not yet been called successfully
     */
    public Entry getEntry() {
        if (cachedIndex < 0) {
            throw new NoSuchElementException("No current entry");
        }
        return cachedEntries.get(cachedIndex);
    }

    /**
     * Returns every entry in the manifest as a lazily-populated list backed by {@link
     * #lazyEntries()}.
     *
     * @return list view of every manifest entry
     * @throws IOException if the manifest cannot be read
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
     * @return an iterator that reads manifest entries on demand
     */
    public Iterator<Entry> lazyEntries() {
        return new Iterator<Entry>() {
            private final ManifestTreeIterator it = iterator;
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
                return new Entry(
                    it.getEntryPath(),
                    it.getEntryNodeId(),
                    it.isExecutable(),
                    it.isSymlink()
                );
            }
        };
    }
}
