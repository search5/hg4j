package io.github.search5.hg4j.lib;
import io.github.search5.hg4j.storage.DefaultFileStoreEngine;
import io.github.search5.hg4j.storage.StoreEngine;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.revset.HgRevsetEngine;
import io.github.search5.hg4j.phase.PhaseRoots;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;
import io.github.search5.hg4j.dirstate.Dirstate;

import java.io.File;
import java.io.IOException;
import io.github.search5.hg4j.errors.HgLockException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import io.github.search5.hg4j.errors.HgCorruptDataException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Represents a local Mercurial repository.
 * <p><strong>Thread Safety:</strong> This class is fully thread-safe and supports parallel concurrent
 * read operations from multiple threads. Critical methods accessing shared cache maps, ignore patterns 
 * and repository state are guarded with high-fidelity internal object monitor synchronization.
 */
public class HgRepository implements Repository {
    private static final Logger LOGGER = Logger.getLogger(HgRepository.class.getName());
    private final File directory;
    private final File hgDir;
    private final File storeDir;
    private boolean defaultDirstateV2 = false;
    private boolean useZstdCompression = false;
    // 실제 requirement 문자열은 mercurial/requirements.py에서 실측 확인됨
    // (CHANGELOGV2_REQUIREMENT/REVLOGV2_REQUIREMENT/NODEMAP_REQUIREMENT).
    // .hg/requires가 아니라 .hg/store/requires에 기록된다 (share-safe 저장소 기준).
    private boolean changelogV2 = false;
    private boolean revlogV2 = false;
    private boolean persistentNodemap = false;
    private boolean fileIndexV1 = false;
    private boolean treemanifest = false;
    private boolean sidedataCopies = false;
    private boolean bookmarksInStore = false;
    private StoreEngine storeEngine = new DefaultFileStoreEngine();
    private Dirstate cachedDirstate = null;
    private final HgRcConfig config = new HgRcConfig();

    /**
     * Replaces the storage engine that opens revlogs, reads the manifest and reads and writes the dirstate.
     * Revlogs already opened are dropped from the cache (see {@link #clearRevlogCache()}).
     * @param storeEngine the new engine; {@code null} is ignored and changes nothing
     */
    public synchronized void setStoreEngine(StoreEngine storeEngine) {
        if (storeEngine != null) {
            this.storeEngine = storeEngine;
            clearRevlogCache();
        }
    }

    /**
     * Returns the configuration of this repository, read from {@code .hg/hgrc} when the repository object was created
     * (a missing or unreadable file is ignored). The returned object is the live one, never {@code null}.
     * @return the repository configuration
     */
    public HgRcConfig getConfig() {
        return this.config;
    }

    /**
     * Opens the repository whose working copy is {@code directory}. Nothing is checked about the directory (use {@link Repository#open(File)}
     * to require an existing {@code .hg}); the {@code .hg/sharedpath} file (shared repositories), the {@code requires} files and {@code .hg/hgrc}
     * are read here and read errors are ignored, leaving the defaults.
     * @param directory the working copy root, the directory that contains {@code .hg}
     */
    public HgRepository(File directory) {
        this.directory = directory;
        this.hgDir = new File(directory, ".hg");
        
        File resolvedStoreDir = null;
        File sharedpathFile = new File(hgDir, "sharedpath");
        if (sharedpathFile.exists() && sharedpathFile.isFile()) {
            try {
                String sharedPath = Files.readString(sharedpathFile.toPath(), StandardCharsets.UTF_8).trim();
                File sharedHgDir = new File(sharedPath);
                resolvedStoreDir = new File(sharedHgDir, "store");
            } catch (Exception e) {
                resolvedStoreDir = new File(hgDir, "store");
            }
        } else {
            resolvedStoreDir = new File(hgDir, "store");
        }
        
        this.storeDir = resolvedStoreDir;
        loadRequires();
        loadConfig();
    }

    private void loadConfig() {
        try {
            File hgrc = new File(hgDir, "hgrc");
            if (hgrc.exists() && hgrc.isFile()) {
                this.config.load(hgrc);
            }
        } catch (Exception ignored) {
            // non-blocking configuration load
        }
    }

    private void loadRequires() {
        readRequiresFile(new File(hgDir, "requires"));
        // share-safe(기본값) 저장소는 store 관련 requirement를 .hg/requires가 아니라
        // .hg/store/requires에 별도로 기록한다 — 실제 hg CLI(7.2)로 확인됨.
        readRequiresFile(new File(storeDir, "requires"));
    }

    private void readRequiresFile(File requiresFile) {
        if (requiresFile.exists() && requiresFile.isFile()) {
            try {
                List<String> lines = Files.readAllLines(requiresFile.toPath());
                for (String line : lines) {
                    String trimmed = line.trim();
                    if ("dirstate-v2".equals(trimmed)) {
                        this.defaultDirstateV2 = true;
                    } else if ("revlog-compression-zstd".equals(trimmed)) {
                        this.useZstdCompression = true;
                    } else if ("exp-changelog-v2".equals(trimmed)) {
                        this.changelogV2 = true;
                    } else if ("exp-revlogv2.2".equals(trimmed)) {
                        this.revlogV2 = true;
                    } else if ("persistent-nodemap".equals(trimmed)) {
                        this.persistentNodemap = true;
                    } else if ("fileindex-v1".equals(trimmed)) {
                        this.fileIndexV1 = true;
                    } else if ("treemanifest".equals(trimmed)) {
                        this.treemanifest = true;
                    } else if ("exp-copies-sidedata-changeset".equals(trimmed)) {
                        this.sidedataCopies = true;
                    } else if ("bookmarksinstore".equals(trimmed)) {
                        this.bookmarksInStore = true;
                    }
                }
            } catch (Exception ignored) {
                // Fallback to default v1
            }
        }
    }

    /**
     * Every requirement of this repository, as listed in {@code .hg/requires} and (share-safe repositories)
     * {@code .hg/store/requires}. The flags above only track the few requirements hg4j acts on; a server that
     * advertises {@code streamreqs} needs the complete set.
     */
    public java.util.Set<String> getRequirements() {
        java.util.Set<String> all = new java.util.TreeSet<>();
        for (File f : new File[]{new File(hgDir, "requires"), new File(storeDir, "requires")}) {
            if (f.isFile()) {
                try {
                    for (String line : Files.readAllLines(f.toPath())) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty()) {
                            all.add(trimmed);
                        }
                    }
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException("cannot read " + f, e);
                }
            }
        }
        return all;
    }

    /** {@code exp-changelog-v2} requirement — changelog가 revlog v2(docket 기반) 포맷임. */
    public boolean isChangelogV2() {
        return changelogV2;
    }

    /**
     * {@code exp-revlogv2.2} requirement — 매니페스트/파일로그가 일반 revlog v2 포맷임.
     * 읽기/쓰기 모두 지원한다({@link io.github.search5.hg4j.storage.RevlogIndex},
     * {@link io.github.search5.hg4j.storage.Revlog} 참고, Rust 확장이 활성화된 실제
     * Mercurial 7.2.4 빌드(docker/hg-rust-7.2.4)로 만든 픽스처로 검증됨).
     */
    public boolean isRevlogV2() {
        return revlogV2;
    }

    /**
     * {@code persistent-nodemap} requirement. When true, {@link io.github.search5.hg4j.storage.DefaultFileStoreEngine}
     * attempts to load each non-inline revlog's {@code <radix>.n} trie
     * ({@link io.github.search5.hg4j.storage.NodeMapFile}) for accelerated node hash to revision
     * lookups ({@code RevlogIndex.findRevision}) — real hg only ever writes this file for
     * non-inline revlogs (typically just {@code 00changelog.i} in modest-sized repos), and only a
     * present, non-stale ({@code .n}'s recorded tip matches the revlog's actual current tip) trie
     * is used; anything else falls back to the ordinary full-scan lookup. {@link
     * io.github.search5.hg4j.storage.Revlog} also maintains the trie on write (after each
     * appended revision, for non-inline revlogs), via {@link
     * io.github.search5.hg4j.storage.NodeMapFile#persist} — matches real hg's own incremental
     * (with periodic full-rebuild fallback) strategy, verified against a real Rust-enabled hg
     * (docker/hg-rust-7.2.4).
     */
    public boolean isPersistentNodemap() {
        return persistentNodemap;
    }

    /**
     * {@code treemanifest} requirement (real hg's {@code experimental.treemanifest=1}) —
     * manifests are split recursively per-directory ({@code meta/<dir>/00manifest.i}) instead of
     * one flat listing, with {@code t}-flagged entries in a parent directory's manifest text
     * pointing at its immediate children's submanifest revisions. Read support (recursive
     * expansion back into a flat file list) lives in {@link
     * io.github.search5.hg4j.treewalk.ManifestTreeIterator}; write support (splitting a new flat
     * manifest into the recursive per-directory revisions on commit) lives in {@link
     * io.github.search5.hg4j.api.CommitCommand}.
     */
    public boolean isTreemanifest() {
        return treemanifest;
    }

    /**
     * {@code exp-copies-sidedata-changeset} requirement (implies {@code exp-changelog-v2}) —
     * commits should carry a {@code SD_FILES} sidedata record (added/removed/merged/salvaged/
     * touched paths + per-destination copy source) on the changelog revision itself. Write
     * support lives in {@link io.github.search5.hg4j.api.CommitCommand} (via {@link
     * io.github.search5.hg4j.api.ChangingFiles#encode}/{@link
     * io.github.search5.hg4j.storage.SidedataCodec#serialize}); read support in {@link
     * io.github.search5.hg4j.api.SidedataChangedFilesCommand}.
     */
    public boolean isSidedataCopies() {
        return sidedataCopies;
    }

    /**
     * {@code fileindex-v1} requirement — {@code exp-revlogv2.2} 저장소가 fncache 대신 쓰는
     * 파일 경로 인덱스({@code .hg/store/fileindex}, 방사 트라이). 읽기/쓰기 모두 지원한다
     * ({@link io.github.search5.hg4j.storage.FileIndex} 참고).
     */
    public boolean isFileIndexV1() {
        return fileIndexV1;
    }

    /**
     * {@code bookmarksinstore} requirement (listed in {@code .hg/store/requires} of a share-safe repository; real hg 7.2 writes it for
     * {@code format.bookmarks-in-store=true}). The bookmarks file then lives in the store, so the store lock alone protects it,
     * while the active bookmark ({@code bookmarks.current}) stays in {@code .hg} under the working copy lock. Mirrors
     * {@code mercurial/bookmarks.py}'s {@code bookmarksinstore}.
     *
     * @return {@code true} if the bookmarks file is {@code .hg/store/bookmarks} instead of {@code .hg/bookmarks}
     */
    public boolean isBookmarksInStore() {
        return bookmarksInStore;
    }

    /**
     * The file that holds the bookmarks: {@code .hg/store/bookmarks} when {@link #isBookmarksInStore()}, else {@code .hg/bookmarks}
     * (real hg's {@code bookmarksvfs(repo)}). The active bookmark file {@code bookmarks.current} is not affected: it is always in {@code .hg}.
     *
     * @return the bookmarks file; it may not exist yet
     */
    public File getBookmarksFile() {
        return new File(bookmarksInStore ? storeDir : hgDir, "bookmarks");
    }

    /**
     * hg4j's own {@code rollback} copy of the bookmarks file (not real hg's {@code undo.backup.bookmarks.bck}: the names of hg4j's undo files
     * differ from real hg's in general). It lives next to the bookmarks file, so for a {@code bookmarksinstore} repository the store lock
     * alone covers writing and restoring it, as it does for the bookmarks file itself.
     *
     * @return the backup file; it may not exist
     */
    public File getBookmarksUndoBackupFile() {
        return new File(bookmarksInStore ? storeDir : hgDir, "undo.backup.bookmarks");
    }

    /**
     * Re-reads the {@code bookmarksinstore} requirement from {@code requires} and {@code store/requires}. Needed after a step of this process
     * rewrote them under an already open object (a stream clone or a clone bundle writes the requirements of the remote, a local clone copies
     * the store's {@code requires}): until then {@link #getBookmarksFile()} would still point at the old place and the bookmarks that the same
     * clone fetches next would be written there.
     */
    public void reloadBookmarksInStoreRequirement() {
        this.bookmarksInStore = getRequirements().contains("bookmarksinstore");
    }

    /**
     * Tells whether the repository requires zstd compression of revlog data ({@code revlog-compression-zstd}).
     * @return {@code true} if new revlog data is compressed with zstd instead of zlib
     */
    public boolean isUseZstdCompression() {
        return useZstdCompression;
    }

    /**
     * Returns the working copy root given to the constructor.
     * @return the working copy directory
     */
    public File getDirectory() {
        return directory;
    }

    /**
     * Returns the {@code .hg} directory inside the working copy root.
     * @return the {@code .hg} directory
     */
    public File getHgDir() {
        return hgDir;
    }

    /**
     * Returns the store directory: {@code .hg/store}, or {@code store} inside the directory named by {@code .hg/sharedpath} for a shared repository.
     * @return the store directory
     */
    public File getStoreDir() {
        return storeDir;
    }

    /**
     * Loads the dirstate from the repository.
     * 
     * @return the {@link Dirstate} instance
     * @throws IOException if loading fails
     */
    public synchronized Dirstate getDirstate() throws IOException {
        DirstateContext open = dirstateContext.get();
        if (open != null && open.current != null) {
            return open.current;
        }
        Dirstate loaded = readDirstate();
        if (open != null) {
            open.current = loaded;
        }
        return loaded;
    }

    private Dirstate readDirstate() throws IOException {
        Dirstate oldDirstate = this.cachedDirstate;
        Dirstate dirstate = null;
        try {
            dirstate = storeEngine.getDirstate(this);
            this.cachedDirstate = dirstate;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to read dirstate file, attempting rebuild", e);
            Dirstate rebuilt = new Dirstate();
            try {
                rebuildDirstateFromManifest(rebuilt, oldDirstate);
                this.cachedDirstate = rebuilt;
                dirstate = rebuilt;
            } catch (IOException ex) {
                LOGGER.log(Level.SEVERE, "Failed to dynamically rebuild dirstate from manifest", ex);
                throw new HgCorruptDataException("Failed to read dirstate and failed to rebuild from manifest", ex);
            }
        }
        return dirstate;
    }

    private void rebuildDirstateFromManifest(Dirstate dirstate, Dirstate sourceDirstate) throws IOException {
        File clIdx = new File(storeDir, "00changelog.i");
        File clDat = new File(storeDir, "00changelog.d");
        
        if (!clIdx.exists()) {
            dirstate.setParents(new byte[20], new byte[20]);
            return;
        }
        
        Revlog changelog = getRevlog(clIdx, clDat);
        int lastRev = changelog.getRevisionCount() - 1;
        if (lastRev < 0) {
            dirstate.setParents(new byte[20], new byte[20]);
            return;
        }
        
        byte[] parentNode = changelog.getIndexRecord(lastRev).getNodeId();
        dirstate.setParents(parentNode, new byte[20]);
        
        // BUG-07: Extract existing copyMap and non-normal state info in advance (GC protection)
        Map<String, String> originalCopyMap = new HashMap<>();
        Map<String, Character> originalStates = new HashMap<>();
        if (sourceDirstate != null) {
            originalCopyMap.putAll(sourceDirstate.getCopyMap());
            for (Map.Entry<String, Dirstate.Entry> ent : sourceDirstate.getEntries().entrySet()) {
                if (ent.getValue().getState() != 'n') {
                    originalStates.put(ent.getKey(), ent.getValue().getState());
                }
            }
        }
        
        Map<String, String> manifestMap = getManifestAtCommit(parentNode);
        for (String path : manifestMap.keySet()) {
            File diskFile = new File(directory, path);
            if (diskFile.exists() && diskFile.isFile()) {
                // Follow POSIX standard octal notation for readability
                int mode = diskFile.canExecute() ? 0100755 : 0100644;
                int size = (int) diskFile.length();
                long time = diskFile.lastModified() / 1000;
                
                // If the previous state was not normal ('n') but Added ('a'), Removed ('r'), or Merged ('m'),
                // inherit and restore the state to prevent it from being lost during reconstruction.
                char state = originalStates.getOrDefault(path, 'n');
                dirstate.addEntry(path, new Dirstate.Entry(state, mode, size, time));
            }
        }
        
        // Restore original copyMap information
        dirstate.getCopyMap().putAll(originalCopyMap);
    }

    /**
     * Lists the files of a commit with their file nodes, through the storage engine.
     * @param commitNodeId the 20-byte node id of the changeset
     * @return a map from repository-relative path to the 40-digit hex file node, followed by {@code x} for an executable file or {@code l} for a symlink
     * @throws IOException if the changeset is not found or the manifest cannot be read
     */
    public synchronized Map<String, String> getManifestAtCommit(byte[] commitNodeId) throws IOException {
        return storeEngine.getManifestAtCommit(this, commitNodeId);
    }

    /**
     * Returns the root manifest revlog ({@code 00manifest.i} in the store), opened through {@link #getRevlog(File, File)} and so cached.
     * @return the manifest revlog
     * @throws IOException if it cannot be opened
     */
    public synchronized Revlog getManifestRevlog() throws IOException {
        return storeEngine.getManifestRevlog(this);
    }

    /**
     * Saves the dirstate to the repository.
     * 
     * @param dirstate the dirstate to save
     * @throws IOException if writing fails
     */
    public synchronized void writeDirstate(Dirstate dirstate) throws IOException {
        if (dirstate == null) {
            throw new IllegalArgumentException("Dirstate cannot be null");
        }
        dirstate.setV2(defaultDirstateV2);
        DirstateContext open = dirstateContext.get();
        if (open != null) {
            // inside a changing context nothing is written until the outermost one ends well
            open.current = dirstate;
            open.dirty = true;
            return;
        }
        storeEngine.writeDirstate(this, dirstate);
        this.cachedDirstate = dirstate;
    }

    private final Map<File, Revlog> revlogCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<File, Revlog> eldest) {
            if (size() > 100) {
                eldest.getValue().clearCache();
                return true;
            }
            return false;
        }
    };

    /**
     * Returns the revlog for an index file, opening it through the storage engine the first time. Revlogs are cached by the canonical path of the
     * index file (up to 100 are kept; the least recently used one is dropped and its caches cleared), so repeated calls return the same object.
     * @param idxFile the index ({@code .i}) file
     * @param datFile the data ({@code .d}) file; ignored for an inline or version 2 revlog
     * @return the revlog
     * @throws IOException if the file cannot be resolved or the revlog cannot be opened
     */
    public synchronized Revlog getRevlog(File idxFile, File datFile) throws IOException {
        File canonicalIdx = idxFile.getCanonicalFile();
        if (!revlogCache.containsKey(canonicalIdx)) {
            revlogCache.put(canonicalIdx, storeEngine.getRevlog(this, canonicalIdx, datFile.getCanonicalFile()));
        }
        return revlogCache.get(canonicalIdx);
    }

    /**
     * Clears the caches of every cached revlog and forgets them, so the next {@link #getRevlog(File, File)} reopens from the disk.
     * Call it after the store was changed behind this object's back.
     */
    public synchronized void clearRevlogCache() {
        for (Revlog r : revlogCache.values()) {
            r.clearCache();
        }
        revlogCache.clear();
    }

    private volatile long lastObservedChangelogSize = -1;
    private volatile long lastObservedChangelogMtime = -1;

    /**
     * Detects whether the changelog has been externally modified since the last check and, if
     * so, clears the whole revlog cache so the next read rebuilds fresh {@link Revlog}/
     * {@link io.github.search5.hg4j.storage.RevlogIndex} instances.
     *
     * <p>This exists for long-lived {@code HgRepository} handles -- specifically the ones
     * {@code HgHttpWireServer}/{@code HgSshWireServer} keep open for the lifetime of the server
     * process -- whose cached {@code Revlog}s would otherwise never notice a repository mutated
     * out-of-band (a bare {@code hg} CLI call, a backup/restore tool, another process). It is a
     * deliberately blunt, cheap, server-facing check: two {@code stat()}-equivalent calls
     * ({@code length()}/{@code lastModified()}) on {@code 00changelog.i}, comparing both size
     * <em>and</em> mtime -- size alone is not enough for a v2/changelog-v2 store, where the
     * docket file's own length stays fixed while its {@code index_end}/{@code data_end} fields
     * are rewritten in place as more revisions are appended.
     *
     * <p>This must not blindly discard a cached changelog {@link Revlog} that has itself already
     * written locally in this process (backlog #39, 2026-09-05, found while investigating a
     * regression this method's own connection into 7 read commands introduced): {@code
     * clearRevlogCache()} replaces every cached {@code Revlog}/{@code RevlogIndex} with a brand
     * new instance whose {@code addedRecords} starts empty, which silently discards exactly the
     * "this instance already knows its own local write history" trust that {@code
     * RevlogIndex.checkAndUpdate()}'s own {@code addedRecords}-emptiness guard exists to protect
     * (see that method's javadoc) -- a changelog.i size/mtime change right after this process's
     * own local commit is indistinguishable from an external one by size/mtime alone, so a naive
     * unconditional reload here breaks the common "commit, then immediately strip/rebase/histedit
     * the same revision with the same handle" pattern: the stripped node's {@code findRevision}
     * on the fresh instance finds no local-write history to trust, reloads from the
     * already-truncated file, and silently loses track of a revision the caller (e.g.
     * {@code StripCommand}'s bookmark-relocation loop) still needs to resolve (verified live:
     * this exact regression reproduced in {@code StripCommandCoverageTest
     * #stripMovesBookmarkPointingAtStrippedRevisionToNewTip}). So: only clear the cache when the
     * cached changelog {@code Revlog} either doesn't exist yet or has never itself added a record
     * locally -- a genuinely external change is unaffected by this (this process never wrote to
     * it, so {@code hasLocallyAddedRecords()} is trivially false), while a local
     * commit-then-mutate sequence on the same handle is now protected exactly like
     * {@code RevlogIndex.checkAndUpdate()} already protects it.
     */
    public synchronized void refreshIfChangedOnDisk() {
        File clIdx = new File(storeDir, "00changelog.i");
        long size = clIdx.exists() ? clIdx.length() : -1L;
        long mtime = clIdx.exists() ? clIdx.lastModified() : -1L;
        if (size != lastObservedChangelogSize || mtime != lastObservedChangelogMtime) {
            lastObservedChangelogSize = size;
            lastObservedChangelogMtime = mtime;
            Revlog cachedChangelog = null;
            try {
                cachedChangelog = revlogCache.get(clIdx.getCanonicalFile());
            } catch (IOException ignored) {
            }
            if (cachedChangelog == null || !cachedChangelog.hasLocallyAddedRecords()) {
                clearRevlogCache();
            }
        }
    }

    /**
     * Loads the phase roots from the repository.
     * 
     * @return the {@link PhaseRoots} instance
     * @throws IOException if loading fails
     */
    public synchronized PhaseRoots getPhaseRoots() throws IOException {
        // 실제 hg는 phaseroots를 .hg/phaseroots가 아니라 .hg/store/phaseroots에 저장한다
        // (share-safe 저장소 기준 real hg CLI 7.2로 직접 확인, 2026-09-01) — .hg/phaseroots를
        // 쓰면 실제 hg가 phase 정보를 전혀 읽지 못해(항상 public으로 간주) 모든 phase 관련
        // 상호운용(push/pull phase 동기화, hg phase, hg summary 등)이 깨진다.
        File phaserootsFile = new File(storeDir, "phaseroots");
        return new PhaseRoots(phaserootsFile);
    }

    private io.github.search5.hg4j.match.Matcher ignoreMatcher = null;

    /**
     * The matcher of the active ignore patterns: {@code .hgignore} and the files named by {@code ui.ignore} / {@code ui.ignore.*}
     * (the {@code dirstate._ignore} of Mercurial), with its {@code syntax:} lines, comments, {@code include:} and
     * {@code subinclude:}.
     */
    private synchronized io.github.search5.hg4j.match.Matcher ignoreMatcher() {
        if (ignoreMatcher == null) {
            try {
                ignoreMatcher = new io.github.search5.hg4j.fileset.RepositoryFilesetContext(this, null).ignoreMatcher();
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to load ignore patterns from .hgignore", e);
                ignoreMatcher = io.github.search5.hg4j.match.Matchers.never();
            }
        }
        return ignoreMatcher;
    }

    /** Whether the path (a directory may end with a slash) is ignored. */
    public synchronized boolean isIgnored(String relativePath) {
        String path = relativePath.endsWith("/") ? relativePath.substring(0, relativePath.length() - 1) : relativePath;
        return ignoreMatcher().matches(path);
    }

    /**
     * Lists the files of the working copy, whatever the dirstate says. The ignore rules are reloaded first. Ignored files and directories,
     * the {@code .hg} directory and the contents of subrepositories declared in {@code .hgsub} are skipped; symlinks are listed as files and not followed.
     * @return repository-relative, slash separated paths of files and symlinks, in no particular order
     */
    public synchronized List<String> scanWorkingCopy() {
        ignoreMatcher = null;
        List<String> result = new ArrayList<>();
        scanDirectory(directory, directory, result, loadSubrepoPaths());
        return result;
    }

    /**
     * Reads {@code .hgsub}'s declared subrepo paths so {@link #scanDirectory} can treat them as
     * an opaque boundary -- real hg never walks into a declared subrepo directory when scanning
     * the parent's own working copy (that subtree belongs to the subrepo's own dirstate, not the
     * parent's). Without this, a plain {@code hg add}/commit-time working-copy scan would slurp
     * every file physically sitting under a checked-out subrepo directory into the *parent*
     * repository's own tracked manifest -- verified live against real hg 7.2, where {@code hg
     * status}/{@code hg add} at the parent level never see inside a subrepo path at all.
     * Best-effort: any parse failure yields an empty set rather than failing the whole scan.
     *
     * <p>Public (backlog #39) so other working-copy-walking commands ({@link
     * io.github.search5.hg4j.api.PurgeCommand}) can apply the exact same subrepo boundary without
     * re-parsing {@code .hgsub} themselves.
     */
    public Set<String> loadSubrepoPaths() {
        File hgsubFile = new File(directory, ".hgsub");
        if (!hgsubFile.exists()) {
            return Collections.emptySet();
        }
        Set<String> paths = new HashSet<>();
        try {
            for (String line : Files.readAllLines(hgsubFile.toPath(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq == -1) {
                    continue;
                }
                String path = trimmed.substring(0, eq).trim();
                if (!path.isEmpty()) {
                    paths.add(path);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to read .hgsub while scanning working copy", e);
        }
        return paths;
    }

    private void scanDirectory(File dir, File root, List<String> result, Set<String> subrepoPaths) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.getName().equals(".hg")) {
                continue;
            }
            String rel = root.toURI().relativize(child.toURI()).getPath();
            rel = rel.replace('\\', '/');

            boolean isDir = child.isDirectory() && !Files.isSymbolicLink(child.toPath());
            if (isDir) {
                if (!rel.endsWith("/")) {
                    rel = rel + "/";
                }
            } else {
                if (rel.endsWith("/")) {
                    rel = rel.substring(0, rel.length() - 1);
                }
            }

            if (isIgnored(rel)) {
                continue;
            }

            if (isDir && !subrepoPaths.isEmpty()) {
                String dirPathNoSlash = rel.endsWith("/") ? rel.substring(0, rel.length() - 1) : rel;
                if (subrepoPaths.contains(dirPathNoSlash)) {
                    // Declared subrepo boundary -- do not walk into it; its contents belong to
                    // the subrepo's own dirstate, never to the parent's.
                    continue;
                }
            }

            if (isDir) {
                scanDirectory(child, root, result, subrepoPaths);
            } else if (child.isFile() || Files.isSymbolicLink(child.toPath())) {
                // A symlink is never recursed into (isDir above already excludes it), but
                // real hg tracks it as a plain file entry regardless of whether its target
                // exists, is a file, or is a directory (verified live: real hg `add` accepts
                // a dangling symlink) — child.isFile() alone follows the link and misses all
                // three of those cases.
                result.add(rel);
            }
        }
    }

    /**
     * Gets the active branch name. Defaults to "default" if the branch file does not exist.
     *
     * @return the active branch name
     */
    public synchronized String getBranch() {
        File branchFile = new File(hgDir, "branch");
        if (branchFile.exists()) {
            try {
                return Files.readString(branchFile.toPath(), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read branch file", e);
            }
        }
        return "default";
    }

    /**
     * Sets the active branch name by writing it to .hg/branch.
     *
     * @param branch the branch name to set
     * @throws IOException if writing fails
     */
    public synchronized void setBranch(String branch) throws IOException {
        if (branch == null || branch.isEmpty()) {
            throw new IllegalArgumentException("Branch name cannot be null or empty");
        }
        File branchFile = new File(hgDir, "branch");
        SafeFileIO.writeStringAtomic(branchFile, branch + "\n");
    }

    /**
     * Locks the working directory (updates to dirstate or working copy). Like real hg's {@code repo.wlock()} (whose {@code wait}
     * defaults to true) it waits for {@code ui.timeout} seconds (600 unless configured) when another process holds the lock;
     * {@code ui.timeout = 0} fails at once. See {@link #resolvePushLockTimeoutMs()}.
     *
     * @return the {@link HgLock} instance
     * @throws HgLockException if the lock could not be acquired within the timeout
     */
    public HgLock lockWorkingCopy() throws HgLockException {
        return lockWorkingCopy(resolvePushLockTimeoutMs());
    }

    /**
     * Locks the working directory, waiting up to {@code timeoutMs} if it is already held before
     * giving up -- matches real hg's {@code localrepo.py} {@code wlock(wait=True)} default (backed
     * by {@code mercurial/lock.py}'s {@code trylock()}/{@code lock()} loop). The no-argument
     * {@link #lockWorkingCopy()} waits for the configured {@code [ui] timeout} (600 seconds by default), as every command does.
     *
     * <p>Deliberately NOT {@code synchronized} on this repository instance (unlike most other
     * mutating methods here): the underlying {@link HgLock} constructor can now genuinely block
     * for up to {@code timeoutMs} while contended. Holding this object's own monitor for that
     * whole wait would block every OTHER {@code synchronized} method on the same {@link
     * HgRepository} instance -- including ones a concurrently-racing thread needs to finish its
     * own, unrelated work -- turning a bounded per-lock wait into an effectively unbounded
     * self-deadlock. Confirmed live (2026-09-04, backlog item 38): with this method still marked
     * {@code synchronized}, two genuinely concurrent real-hg pushes against the same shared server
     * repository reliably deadlocked this way -- the winner's own request thread got stuck for
     * the loser's ENTIRE wait duration on an unrelated {@code synchronized} repository call, even
     * though the winner itself was never contending on the file lock at all. Mutual exclusion for
     * the lock itself is already fully guaranteed without this object's monitor, by {@link
     * HgLock}'s own static, path-keyed tracking plus the atomic filesystem symlink/file creation
     * it uses.
     *
     * @param timeoutMs how long to wait for contention to clear, in milliseconds; {@code 0} means
     *                   fail immediately, matching real hg's own {@code ui.timeout=0} semantics.
     * @return the {@link HgLock} instance
     * @throws HgLockException if the lock could not be acquired within {@code timeoutMs}
     */
    public HgLock lockWorkingCopy(int timeoutMs) throws HgLockException {
        // Like real hg's repo.wlock(): nothing is recovered when the lock is taken. The dirstate is not journaled either; a command that
        // changes it in memory writes it once at the end and puts the old bytes back when it fails.
        HgLock lock = new HgLock(new File(hgDir, "wlock"), timeoutMs, true);
        // keyed by the lock file, not by this object: the same working copy is often opened more than once in a command
        String key = wlockKey();
        WLOCKS_HELD.get().merge(key, 1, Integer::sum);
        return track(lock, () -> WLOCKS_HELD.get().computeIfPresent(key, (k, n) -> n > 1 ? n - 1 : null));
    }

    private static final ThreadLocal<Map<String, Integer>> WLOCKS_HELD = ThreadLocal.withInitial(HashMap::new);

    private String wlockKey() {
        try {
            return new File(hgDir, "wlock").getCanonicalPath();
        } catch (IOException e) {
            return new File(hgDir, "wlock").getAbsolutePath();
        }
    }

    /** Whether this thread holds the working copy lock ({@code repo.currentwlock()} is not {@code None}). */
    public boolean holdsWorkingCopyLock() {
        return WLOCKS_HELD.get().containsKey(wlockKey());
    }

    /** The work a dirstate-changing context runs. */
    @FunctionalInterface
    public interface DirstateBody<T> {
        T run() throws IOException, io.github.search5.hg4j.errors.HgLockException;
    }

    /** {@code dirstate.changing_files}/{@code changing_parents}/{@code running_status}: what the open context holds. */
    private static final class DirstateContext {
        final String kind;
        int level;
        Dirstate current;
        boolean dirty;
        boolean invalid;

        DirstateContext(String kind) {
            this.kind = kind;
        }
    }

    private final ThreadLocal<DirstateContext> dirstateContext = new ThreadLocal<>();

    /**
     * {@code dirstate.changing_files}: the set of tracked files changes, the parents do not. Like the original it requires the working
     * copy lock, excludes {@link #changingParents}, may be nested, writes the dirstate once when the outermost one ends normally and
     * throws every change away when any of them ends with an exception (the next read goes back to the disk).
     */
    public <T> T changingFiles(DirstateBody<T> body) throws IOException, io.github.search5.hg4j.errors.HgLockException {
        return inDirstateContext("changing_files", body);
    }

    /** {@code dirstate.changing_parents}: the working copy parents change (update, merge, commit...). Same rules as {@link #changingFiles}. */
    public <T> T changingParents(DirstateBody<T> body) throws IOException, io.github.search5.hg4j.errors.HgLockException {
        return inDirstateContext("changing_parents", body);
    }

    private <T> T inDirstateContext(String kind, DirstateBody<T> body) throws IOException, io.github.search5.hg4j.errors.HgLockException {
        DirstateChange change = openDirstateContext(kind);
        try {
            T result = body.run();
            change.close();
            return result;
        } catch (IOException | io.github.search5.hg4j.errors.HgLockException | RuntimeException failure) {
            change.failed();
            try {
                change.close();
            } catch (IOException suppressed) {
                failure.addSuppressed(suppressed);
            }
            throw failure;
        }
    }

    /**
     * An open dirstate-changing context for a caller whose body cannot be a lambda: close it (a try-with-resources does) and the dirstate
     * is written if this was the outermost context and {@link #failed()} was not called; after {@code failed()} every change is dropped.
     */
    public final class DirstateChange implements AutoCloseable {
        private final DirstateContext context;
        private final boolean outermost;
        private boolean failed;
        private boolean closed;

        private DirstateChange(DirstateContext context, boolean outermost) {
            this.context = context;
            this.outermost = outermost;
        }

        /** Marks the context as ended by an exception: the next {@link #close()} throws every change away. */
        public void failed() {
            failed = true;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            context.level--;
            if (failed) {
                // an exception anywhere invalidates the whole context, whatever the outer ones do afterwards
                context.current = null;
                context.dirty = false;
                context.invalid = true;
                synchronized (HgRepository.this) {
                    cachedDirstate = null;
                }
            }
            if (outermost) {
                dirstateContext.remove();
                if (!context.invalid && context.dirty && context.current != null) {
                    writeDirstate(context.current);
                }
            }
        }
    }

    /** Opens a {@code changing_parents} context; see {@link DirstateChange}. */
    public DirstateChange openChangingParents() {
        return openDirstateContext("changing_parents");
    }

    /** Opens a {@code changing_files} context; see {@link DirstateChange}. */
    public DirstateChange openChangingFiles() {
        return openDirstateContext("changing_files");
    }

    private DirstateChange openDirstateContext(String kind) {
        if (!holdsWorkingCopyLock()) {
            throw new IllegalStateException("trying to change the dirstate without holding the wlock");
        }
        DirstateContext open = dirstateContext.get();
        if (open != null && !open.kind.equals(kind)) {
            throw new IllegalStateException("trying to open \"" + kind + "\" dirstate-changing context while a \"" + open.kind
                    + "\" is already open");
        }
        boolean outermost = open == null;
        if (outermost) {
            open = new DirstateContext(kind);
            dirstateContext.set(open);
        }
        open.level++;
        return new DirstateChange(open, outermost);
    }

    /**
     * Locks the store repository database (commits, metadata, index updates). Like real hg's {@code repo.lock()} (whose {@code wait}
     * defaults to true) it waits for {@code ui.timeout} seconds (600 unless configured) when another process holds the lock;
     * {@code ui.timeout = 0} fails at once. See {@link #resolvePushLockTimeoutMs()}.
     *
     * @return the {@link HgLock} instance
     * @throws HgLockException if the lock could not be acquired within the timeout
     */
    public HgLock lockStore() throws HgLockException {
        return lockStore(resolvePushLockTimeoutMs());
    }

    /**
     * The store lock without the check for an interrupted transaction that {@link #lockStore(int)} makes when it takes the lock
     * anew ({@code lockStore} refuses with {@link io.github.search5.hg4j.errors.HgAbandonedTransactionException} when a journal is
     * left; neither method recovers anything, as real hg's {@code repo.lock()} does not). {@code recover} and {@code verify} take the
     * lock this way because they need to look at the journal while they hold it.
     */
    public HgLock lockStoreWithoutRecovery(int timeoutMs) throws HgLockException {
        return track(new HgLock(new File(storeDir, "lock"), timeoutMs, true));
    }

    /** Work postponed until the locks are released, like real hg's {@code repo._afterlock(callback)}. */
    @FunctionalInterface
    public interface AfterLockAction {
        void run() throws Exception;
    }

    private final ThreadLocal<int[]> heldLocks = ThreadLocal.withInitial(() -> new int[1]);
    private final ThreadLocal<List<AfterLockAction>> afterLockActions = ThreadLocal.withInitial(ArrayList::new);

    private HgLock track(HgLock lock) {
        return track(lock, () -> { });
    }

    private HgLock track(HgLock lock, Runnable onRelease) {
        heldLocks.get()[0]++;
        lock.onClosed(() -> {
            onRelease.run();
            lockReleased();
        });
        return lock;
    }

    private void lockReleased() {
        int[] held = heldLocks.get();
        if (--held[0] > 0) {
            return;
        }
        held[0] = 0;
        List<AfterLockAction> pending = new ArrayList<>(afterLockActions.get());
        afterLockActions.get().clear();
        for (AfterLockAction action : pending) {
            runAfterLock(action);
        }
    }

    private void runAfterLock(AfterLockAction action) {
        try {
            action.run();
        } catch (Exception e) {
            // like real hg's non-throwing post hooks (changegroup, incoming, commit, pushkey): they cannot fail the operation
            LOGGER.log(Level.WARNING, "Work postponed until the locks were released failed", e);
        }
    }

    /**
     * Runs {@code action} once the last lock this thread holds on this repository is released, or at once when it holds none
     * (real hg's {@code repo._afterlock}). Post hooks use it: {@code commit}, {@code changegroup}, {@code incoming}, {@code pushkey}.
     */
    public void afterLock(AfterLockAction action) {
        if (heldLocks.get()[0] == 0) {
            runAfterLock(action);
        } else {
            afterLockActions.get().add(action);
        }
    }

    /**
     * {@code localrepo.store_shapes_lock}: the lock a {@code server-shapes} update holds so that a concurrent update cannot be silently
     * thrown away. A file of its own in the store ({@code store-shapes-lock}), independent of the store lock; it is taken before it
     * ({@code admin::narrow-server --shape-update}). Re-entrant for the thread that holds it.
     *
     * @param timeoutMs how long to wait; hg's callers pass {@code wait=False}, that is {@code 0}
     */
    public HgLock lockStoreShapes(int timeoutMs) throws HgLockException {
        return new HgLock(new File(storeDir, "store-shapes-lock"), timeoutMs, true);
    }

    /** Who holds the {@code store-shapes-lock} ({@code host:pid}), or {@code null} when nobody does ({@code svfs.readlock}). */
    public String readStoreShapesLockHolder() {
        File lockFile = new File(storeDir, "store-shapes-lock");
        try {
            return java.nio.file.Files.readSymbolicLink(lockFile.toPath()).toString().trim();
        } catch (java.nio.file.NotLinkException regularFile) {
            try {
                return java.nio.file.Files.readString(lockFile.toPath()).trim();
            } catch (IOException e) {
                return null;
            }
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
    }

    /**
     * Locks the store ({@code .hg/store/lock}), waiting up to {@code timeoutMs} milliseconds if another process holds it.
     * The lock may be taken again by the thread that already holds it. This method is not {@code synchronized}, so waiting does not block other
     * methods of this object. A leftover transaction journal is not recovered here.
     * @param timeoutMs how long to wait, in milliseconds; 0 fails at once
     * @return the held lock; close it to release it (see {@link #afterLock} for work that runs when the last lock is released)
     * @throws HgLockException if the lock was not obtained within the timeout
     * @throws io.github.search5.hg4j.errors.HgAbandonedTransactionException if {@code store/journal} exists and this thread did not already hold
     * the lock; the lock is released again before this is thrown
     */
    public HgLock lockStore(int timeoutMs) throws HgLockException {
        HgLock lock = track(new HgLock(new File(storeDir, "lock"), timeoutMs, true));
        // Nothing is recovered here: real hg's repo.lock() never does. A command that opens a transaction meets an interrupted one
        // (store/journal) with "abandoned transaction found", and `recover` is what cleans it up. The journal found while this thread
        // already holds the lock belongs to the transaction it is running itself.
        if (!lock.isReentrant() && new File(storeDir, "journal").exists()) {
            try {
                lock.close();
            } catch (Exception ignored) {
                // the refusal below is what matters
            }
            throw new io.github.search5.hg4j.errors.HgAbandonedTransactionException();
        }
        return lock;
    }

    /**
     * Resolves the store-lock wait timeout (in milliseconds) the push/unbundle apply path should
     * use for this repository -- mirrors real hg's own {@code ui.timeout} config (default {@code
     * "600"} seconds; {@code mercurial/localrepo.py}'s {@code _lock()} reads it whenever a caller
     * asks to wait, and {@code mercurial/lock.py}'s {@code lock()} loop treats {@code timeout == 0}
     * as "fail immediately" rather than "wait forever"). Confirmed against real hg 7.2 directly
     * (2026-09-04, backlog item 38): a real {@code hg push} against a repository whose store lock
     * is held waits for exactly the configured {@code ui.timeout} before the server-side push
     * fails.
     */
    public int resolvePushLockTimeoutMs() {
        // hg's default is 600 seconds. `hg4j.ui.timeout` only changes that default (the test runs set it to 0 so that a test which holds
        // a lock on purpose does not wait ten minutes); an explicit `[ui] timeout` in a repository always wins.
        String raw = getConfig().get("ui", "timeout", System.getProperty("hg4j.ui.timeout", "600"));
        try {
            long seconds = Long.parseLong(raw.trim());
            if (seconds <= 0) {
                return 0;
            }
            long ms = seconds * 1000L;
            return ms > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ms;
        } catch (NumberFormatException e) {
            return 600_000;
        }
    }

    /**
     * Rolls back an interrupted transaction if {@code store/journal} exists; does nothing otherwise. The journal lines are replayed: backed up files are restored,
     * {@code fncache} is restored from its backup, and files are truncated to their recorded size (deleted when the recorded size is 0). When all
     * lines were applied, the journal and the backup files are deleted. A failure is logged and the journal is kept for a later retry; nothing is thrown.
     * This method does not acquire any lock; the caller should hold the store lock.
     */
    public synchronized void checkAndPerformAutoRollback() {
        File journalFile = new File(storeDir, "journal");
        if (!journalFile.exists()) {
            return;
        }
        boolean rollbackSuccess = false;
        try {
            List<String> lines = Files.readAllLines(journalFile.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("backup ")) {
                    String[] parts;
                    String content = line.substring(7).trim();
                    if (content.contains("\t")) {
                        parts = content.split("\t", 2);
                    } else {
                        parts = content.split(" ", 2);
                    }
                    if (parts.length == 2) {
                        String origRel = parts[0];
                        String backupRel = parts[1];
                        File originalFile = new File(hgDir, origRel);
                        File backupFile = new File(hgDir, backupRel);
                        if (backupFile.exists()) {
                            originalFile.getParentFile().mkdirs();
                            Files.copy(backupFile.toPath(), originalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            Files.deleteIfExists(originalFile.toPath());
                        }
                    }
                } else if (line.equals("dirstate")) {
                    // written by older versions: recovering a transaction never touches the dirstate (it is written after the transaction)
                } else if (line.equals("fncache")) {
                    File fncacheBackup = new File(storeDir, "fncache.backup");
                    File fncacheFile = new File(storeDir, "fncache");
                    if (fncacheBackup.exists()) {
                        Files.move(fncacheBackup.toPath(), fncacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.deleteIfExists(fncacheFile.toPath());
                    }
                } else if (line.startsWith("trunc ")) {
                    // Truncate-only restore, never delete-on-zero -- unlike the generic
                    // "<path>\t<size>" entry below (where size 0 legitimately means "this file
                    // did not exist before, delete it"), this is for companion files of a v2/
                    // docket revlog (a filelog/manifest/changelog's resolved .idx/.dat/.sda) that
                    // real hg always expects to physically exist -- even empty -- as long as the
                    // docket references them (backlog #39, found live 2026-09-05: a fresh v2
                    // revlog's sidedata companion is legitimately 0 bytes from the moment the
                    // docket is created, and deleting it on rollback instead of truncating made
                    // real hg abort with "No such file or directory" reading the docket
                    // afterward). See CommitCommand#recordRevlogRollbackState's javadoc.
                    String content = line.substring("trunc ".length()).trim();
                    int splitIdx = content.lastIndexOf('\t');
                    if (splitIdx != -1) {
                        String filePath = content.substring(0, splitIdx);
                        long origSize = Long.parseLong(content.substring(splitIdx + 1).trim());
                        File file = new File(hgDir, filePath);
                        file.getParentFile().mkdirs();
                        try (FileChannel outChan = FileChannel.open(file.toPath(),
                                StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
                            outChan.truncate(origSize);
                            outChan.force(true);
                        }
                    }
                } else {
                    int splitIdx = line.lastIndexOf('\t');
                    if (splitIdx == -1) {
                        splitIdx = line.lastIndexOf(' ');
                    }
                    if (splitIdx != -1) {
                        String filePath = line.substring(0, splitIdx);
                        long origSize = Long.parseLong(line.substring(splitIdx + 1));
                        File file = new File(hgDir, filePath);
                        if (file.exists()) {
                            if (origSize == 0) {
                                Files.deleteIfExists(file.toPath());
                            } else {
                                try (FileChannel outChan = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
                                    outChan.truncate(origSize);
                                    outChan.force(true);
                                }
                            }
                        }
                    }
                }
            }
            rollbackSuccess = true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Rollback failed, retaining journal for retry", e);
        }
        if (rollbackSuccess) {
            try {
                Files.deleteIfExists(journalFile.toPath());
                Files.deleteIfExists(new File(hgDir, "dirstate.backup").toPath());
                Files.deleteIfExists(new File(hgDir, "dirstateV2.backup.data").toPath());
                Files.deleteIfExists(new File(storeDir, "fncache.backup").toPath());
                deleteDirRecursively(new File(storeDir, "rebase-backup"));
            } catch (Exception ignored) {
                LOGGER.log(Level.WARNING, "Failed to delete rollback backups", ignored);
            }
        }
    }


    private void deleteDirRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirRecursively(child);
                }
            }
        }
        file.delete();
    }

    @Override
    public synchronized void close() {
        clearRevlogCache();
    }
}
