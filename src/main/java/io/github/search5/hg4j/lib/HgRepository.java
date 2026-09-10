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
 *
 * @apiNote The sole implementation of {@link Repository} and the object nearly every porcelain
 *     command is constructed with — obtain one via {@link Repository#open} or {@code
 *     io.github.search5.hg4j.api.Hg}, not by calling {@link #HgRepository(File)} directly unless
 *     you specifically need this concrete type (e.g. for {@link #setStoreEngine}). It caches
 *     opened {@link Revlog} instances ({@link #getRevlog}) across calls for the lifetime of the
 *     instance, so long-lived handles (e.g. {@code HgHttpWireServer}/{@code HgSshWireServer})
 *     should call {@link #refreshIfChangedOnDisk()} before trusting cached data if the
 *     repository may have been mutated by another process.
 */
public class HgRepository implements Repository {
    private static final Logger LOGGER = Logger.getLogger(HgRepository.class.getName());
    private final File directory;
    private final File hgDir;
    private final File storeDir;
    private boolean defaultDirstateV2 = false;
    private boolean useZstdCompression = false;
    // The actual requirement strings were confirmed against mercurial/requirements.py
    // (CHANGELOGV2_REQUIREMENT/REVLOGV2_REQUIREMENT/NODEMAP_REQUIREMENT).
    // They are recorded in .hg/store/requires, not .hg/requires (for a share-safe repository).
    private boolean changelogV2 = false;
    private boolean revlogV2 = false;
    private boolean persistentNodemap = false;
    private boolean fileIndexV1 = false;
    private boolean treemanifest = false;
    private boolean sidedataCopies = false;
    private StoreEngine storeEngine = new DefaultFileStoreEngine();
    private Dirstate cachedDirstate = null;
    private final HgRcConfig config = new HgRcConfig();

    /**
     * Replaces the {@link StoreEngine} used to read/write this repository's store, clearing any
     * cached {@link Revlog}s opened under the previous engine.
     *
     * @apiNote An extension point for swapping in a custom {@link StoreEngine} (e.g. in tests,
     *     or a caller providing its own storage backend) in place of the default {@link
     *     DefaultFileStoreEngine} set in the field initializer; a {@code null} argument is
     *     ignored.
     */
    public synchronized void setStoreEngine(StoreEngine storeEngine) {
        if (storeEngine != null) {
            this.storeEngine = storeEngine;
            clearRevlogCache();
        }
    }

    /** Returns this repository's parsed {@code hgrc} configuration (see {@link HgRcConfig}). */
    public HgRcConfig getConfig() {
        return this.config;
    }

    /**
     * Opens the repository rooted at {@code directory}, resolving its store directory (following
     * {@code .hg/sharedpath} for a share-safe repository) and reading its {@code requires} and
     * {@code hgrc} files.
     *
     * @apiNote Prefer {@link Repository#open(File)} or {@code
     *     io.github.search5.hg4j.api.Hg} unless the concrete {@link HgRepository} type is
     *     specifically needed. Does not validate that {@code directory} actually contains a
     *     {@code .hg} directory — {@link Repository#open} performs that check before delegating
     *     here.
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
        // A share-safe (default) repository records store-related requirements separately in
        // .hg/store/requires rather than .hg/requires -- confirmed against real hg CLI (7.2).
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
                    }
                }
            } catch (Exception ignored) {
                // Fallback to default v1
            }
        }
    }

    /** {@code exp-changelog-v2} requirement — the changelog is in revlog v2 (docket-based) format. */
    public boolean isChangelogV2() {
        return changelogV2;
    }

    /**
     * {@code exp-revlogv2.2} requirement — manifests and filelogs are in plain revlog v2 format.
     * Both reading and writing are supported (see {@link
     * io.github.search5.hg4j.storage.RevlogIndex}, {@link io.github.search5.hg4j.storage.Revlog}),
     * verified against fixtures produced by a real Mercurial 7.2.4 build with the Rust extension
     * enabled (docker/hg-rust-7.2.4).
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
     * {@code fileindex-v1} requirement — the radix-trie file path index ({@code
     * .hg/store/fileindex}) an {@code exp-revlogv2.2} repository uses in place of {@code
     * fncache}. Both reading and writing are supported (see {@link
     * io.github.search5.hg4j.storage.FileIndex}).
     */
    public boolean isFileIndexV1() {
        return fileIndexV1;
    }

    /**
     * {@code revlog-compression-zstd} requirement — new revlog deltas/fulltexts should be
     * compressed with zstd rather than real hg's default zlib.
     *
     * @apiNote Read by {@link io.github.search5.hg4j.storage.DefaultFileStoreEngine} when
     *     writing revlog data so hg4j-created repositories stay compression-compatible with
     *     however the repository was originally created.
     */
    public boolean isUseZstdCompression() {
        return useZstdCompression;
    }

    public File getDirectory() {
        return directory;
    }

    public File getHgDir() {
        return hgDir;
    }

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
     * Resolves the flat path-to-file-node manifest for the given changeset.
     *
     * @apiNote Delegates to the current {@link StoreEngine}; used internally by {@link
     *     #rebuildDirstateFromManifest} and by porcelain commands that need a changeset's
     *     tracked-file listing (e.g. {@code ManifestCommand}, {@code UpdateCommand}, {@code
     *     DiffCommand}).
     */
    public synchronized Map<String, String> getManifestAtCommit(byte[] commitNodeId) throws IOException {
        return storeEngine.getManifestAtCommit(this, commitNodeId);
    }

    /**
     * Opens (or returns the cached) manifest revlog ({@code 00manifest.i}/{@code .d}) for this
     * repository.
     *
     * @apiNote Used by commands and wire protocol handlers that need to read or append manifest
     *     revisions directly (e.g. {@code CommitCommand}, {@code MergeCommand}, {@code
     *     RebaseCommand}, {@code PushCommand}, {@code CloneCommand}, {@code Wire2Commands}).
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
     * Opens (or returns the cached instance for) the revlog identified by its index/data file
     * pair, e.g. {@code 00changelog.i}/{@code .d} or a per-file {@code data/<path>.i}/{@code .d}.
     *
     * @apiNote The shared entry point nearly every command and wire protocol handler uses to
     *     access changelog, manifest, or per-file revlogs — cached for the lifetime of this
     *     {@code HgRepository} instance (evicted LRU-style past 100 entries, or all at once by
     *     {@link #clearRevlogCache()}/{@link #refreshIfChangedOnDisk()}).
     */
    public synchronized Revlog getRevlog(File idxFile, File datFile) throws IOException {
        File canonicalIdx = idxFile.getCanonicalFile();
        if (!revlogCache.containsKey(canonicalIdx)) {
            revlogCache.put(canonicalIdx, storeEngine.getRevlog(this, canonicalIdx, datFile.getCanonicalFile()));
        }
        return revlogCache.get(canonicalIdx);
    }

    /**
     * Evicts every cached {@link Revlog} (see {@link #getRevlog}), forcing the next access to
     * each to re-read from disk.
     *
     * @apiNote Called by {@link #setStoreEngine} and {@link #refreshIfChangedOnDisk()}; most
     *     callers should prefer {@link #refreshIfChangedOnDisk()}, which only clears the cache
     *     when the on-disk changelog actually changed.
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
     * written locally in this process: {@code clearRevlogCache()} replaces every cached {@code
     * Revlog}/{@code RevlogIndex} with a brand new instance whose {@code addedRecords} starts
     * empty, which would discard exactly the "this instance already knows its own local write
     * history" trust that {@code RevlogIndex.checkAndUpdate()}'s own {@code
     * addedRecords}-emptiness guard exists to protect (see that method's javadoc) -- a
     * changelog.i size/mtime change right after this process's own local commit is
     * indistinguishable from an external one by size/mtime alone, so a naive unconditional
     * reload here would break the common "commit, then immediately strip/rebase/histedit the
     * same revision with the same handle" pattern: the stripped node's {@code findRevision} on a
     * freshly-reloaded instance would find no local-write history to trust, reload from the
     * already-truncated file, and silently lose track of a revision the caller (e.g. {@code
     * StripCommand}'s bookmark-relocation loop) still needs to resolve. So: only clear the cache
     * when the cached changelog {@code Revlog} either doesn't exist yet or has never itself
     * added a record locally -- a genuinely external change is unaffected by this (this process
     * never wrote to it, so {@code hasLocallyAddedRecords()} is trivially false), while a local
     * commit-then-mutate sequence on the same handle is protected exactly like {@code
     * RevlogIndex.checkAndUpdate()} already protects it.
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
        // Real hg stores phaseroots in .hg/store/phaseroots, not .hg/phaseroots (for a
        // share-safe repository) -- writing to .hg/phaseroots instead would mean real hg never
        // reads the phase information at all
        // (it would always be treated as public), breaking every phase-related interop (push/pull
        // phase sync, hg phase, hg summary, etc.).
        File phaserootsFile = new File(storeDir, "phaseroots");
        return new PhaseRoots(phaserootsFile);
    }

    private List<Pattern> ignorePatterns = null;

    private synchronized void loadIgnorePatterns() {
        if (ignorePatterns != null) {
            return;
        }
        ignorePatterns = new ArrayList<>();
        File ignoreFile = new File(directory, ".hgignore");
        if (!ignoreFile.exists()) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(ignoreFile.toPath(), StandardCharsets.UTF_8);
            String syntax = "regexp";
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("syntax:")) {
                    syntax = line.substring("syntax:".length()).trim();
                    continue;
                }

                if ("glob".equalsIgnoreCase(syntax)) {
                    for (String expanded : expandBraces(line)) {
                        String regex = globToRegex(expanded);
                        try {
                            ignorePatterns.add(Pattern.compile(regex));
                        } catch (PatternSyntaxException e) {
                            // Skip invalid pattern
                        }
                    }
                } else {
                    String regex = line;
                    if (!regex.startsWith("^")) {
                        regex = "(?:" + regex + ")";
                    }
                    try {
                        ignorePatterns.add(Pattern.compile(regex));
                    } catch (PatternSyntaxException e) {
                        // Skip invalid pattern
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load ignore patterns from .hgignore", e);
        }
    }

    private List<String> expandBraces(String glob) {
        List<String> results = new ArrayList<>();
        int open = glob.indexOf('{');
        int close = open != -1 ? glob.indexOf('}', open) : -1;
        if (open == -1 || close == -1) {
            results.add(glob);
            return results;
        }
        String prefix = glob.substring(0, open);
        String suffix = glob.substring(close + 1);
        String[] choices = glob.substring(open + 1, close).split(",", -1);
        for (String choice : choices) {
            for (String expanded : expandBraces(prefix + choice + suffix)) {
                results.add(expanded);
            }
        }
        return results;
    }

    private String globToRegex(String glob) {
        boolean hasSlash = glob.indexOf('/') != -1;
        StringBuilder sb = new StringBuilder();
        if (hasSlash) {
            sb.append("^");
        } else {
            sb.append("^(?:.*/)?");
        }
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    sb.append(".*");
                    i++;
                } else {
                    sb.append("[^/]*");
                }
            } else if (c == '?') {
                sb.append("[^/]");
            } else if (c == '{' || c == '}') {
                sb.append(Pattern.quote(String.valueOf(c)));
            } else if (".\\$^+|()[]".indexOf(c) != -1) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        sb.append("$");
        return sb.toString();
    }

    /**
     * Checks whether a repository-relative path matches an {@code .hgignore} pattern.
     *
     * @apiNote Used by {@link #scanDirectory} during a working-copy walk, and directly by
     *     {@code PurgeCommand} when deciding which untracked files are eligible for
     *     {@code --all}-style removal.
     * @param relativePath a path relative to the repository root, using {@code /} separators
     */
    public synchronized boolean isIgnored(String relativePath) {
        loadIgnorePatterns();
        for (Pattern pattern : ignorePatterns) {
            if (pattern.matcher(relativePath).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recursively walks the working directory (skipping {@code .hg}, {@code .hgignore}d paths,
     * and declared subrepo boundaries — see {@link #loadSubrepoPaths()}) and returns every file
     * and symlink found, as repository-relative paths.
     *
     * @apiNote The shared working-copy enumeration used by {@code StatusCommand}, {@code
     *     AddCommand}, {@code AddremoveCommand}, and {@code PurgeCommand} to discover untracked
     *     files; see {@link io.github.search5.hg4j.treewalk.WorkingDirTreeIterator} for the
     *     tracked-file counterpart.
     */
    public synchronized List<String> scanWorkingCopy() {
        ignorePatterns = null;
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
     * repository's own tracked manifest -- {@code hg status}/{@code hg add} at the parent level
     * never see inside a subrepo path at all. Best-effort: any parse failure yields an empty set
     * rather than failing the whole scan.
     *
     * <p>Public so other working-copy-walking commands ({@link
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
                // exists, is a file, or is a directory (real hg `add` accepts a dangling
                // symlink) -- child.isFile() alone follows the link and misses all three of
                // those cases.
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
     * Locks the working directory (updates to dirstate or working copy), failing immediately
     * (fail-fast, no wait) if it is already held -- see {@link #lockWorkingCopy(int)} for a
     * variant that waits like real hg's own default {@code wlock()}.
     *
     * @return the {@link HgLock} instance
     * @throws HgLockException if acquiring the lock fails
     */
    public HgLock lockWorkingCopy() throws HgLockException {
        return lockWorkingCopy(0);
    }

    /**
     * Locks the working directory, waiting up to {@code timeoutMs} if it is already held before
     * giving up -- matches real hg's {@code localrepo.py} {@code wlock(wait=True)} default (backed
     * by {@code mercurial/lock.py}'s {@code trylock()}/{@code lock()} loop). Only the push/unbundle
     * apply path opts into this today; every other caller keeps using the fail-fast {@link
     * #lockWorkingCopy()} overload.
     *
     * <p>Deliberately NOT {@code synchronized} on this repository instance (unlike most other
     * mutating methods here): the underlying {@link HgLock} constructor can now genuinely block
     * for up to {@code timeoutMs} while contended. Holding this object's own monitor for that
     * whole wait would block every OTHER {@code synchronized} method on the same {@link
     * HgRepository} instance -- including ones a concurrently-racing thread needs to finish its
     * own, unrelated work -- turning a bounded per-lock wait into an effectively unbounded
     * self-deadlock: with this method marked {@code synchronized}, two genuinely concurrent
     * real-hg pushes against the same shared server repository would deadlock this way -- the
     * winner's own request thread stuck for the loser's ENTIRE wait duration on an unrelated
     * {@code synchronized} repository call, even though the winner itself was never contending
     * on the file lock at all. Mutual exclusion for the lock itself is already fully guaranteed
     * without this object's monitor, by {@link
     * HgLock}'s own static, path-keyed tracking plus the atomic filesystem symlink/file creation
     * it uses.
     *
     * @param timeoutMs how long to wait for contention to clear, in milliseconds; {@code 0} means
     *                   fail immediately, matching real hg's own {@code ui.timeout=0} semantics.
     * @return the {@link HgLock} instance
     * @throws HgLockException if the lock could not be acquired within {@code timeoutMs}
     */
    public HgLock lockWorkingCopy(int timeoutMs) throws HgLockException {
        return new HgLock(new File(hgDir, "wlock"), timeoutMs, true);
    }

    /**
     * Locks the store repository database (commits, metadata, index updates), failing immediately
     * (fail-fast, no wait) if it is already held -- see {@link #lockStore(int)} for a variant that
     * waits like real hg's own default {@code lock()}.
     *
     * @return the {@link HgLock} instance
     * @throws HgLockException if acquiring the lock fails
     */
    public HgLock lockStore() throws HgLockException {
        return lockStore(0);
    }

    /**
     * Locks the store repository database, waiting up to {@code timeoutMs} if it is already held
     * before giving up -- matches real hg's {@code localrepo.py} {@code lock(wait=True)} default
     * (backed by {@code mercurial/lock.py}'s {@code trylock()}/{@code lock()} loop, which itself
     * waits up to {@code ui.timeout} -- default 600 seconds -- before raising {@code
     * error.LockHeld}). Only the push/unbundle apply path opts into this today (see {@link
     * #resolvePushLockTimeoutMs()}); every other caller (commit, update, rebase, ...) keeps
     * using the fail-fast {@link #lockStore()} overload -- widening the wait behavior to every
     * command that locks the store would be a separate, much larger change.
     *
     * <p>Deliberately NOT {@code synchronized} -- see {@link #lockWorkingCopy(int)}'s doc for why
     * (the exact same self-deadlock hazard applies here: this is the lock the push/unbundle apply
     * path actually contends on). {@link #checkAndPerformAutoRollback()} keeps its own independent
     * {@code synchronized}
     * modifier, so it is still safely serialized against itself regardless.
     *
     * @param timeoutMs how long to wait for contention to clear, in milliseconds; {@code 0} means
     *                   fail immediately, matching real hg's own {@code ui.timeout=0} semantics.
     * @return the {@link HgLock} instance
     * @throws HgLockException if the lock could not be acquired within {@code timeoutMs}
     */
    public HgLock lockStore(int timeoutMs) throws HgLockException {
        HgLock lock = new HgLock(new File(storeDir, "lock"), timeoutMs, true);
        try {
            checkAndPerformAutoRollback();
        } catch (Throwable t) {
            try {
                lock.close();
            } catch (Exception ignored) {}
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
            throw new HgLockException("lock", "Failed to perform auto-rollback after lock acquisition", t);
        }
        return lock;
    }

    /**
     * Resolves the store-lock wait timeout (in milliseconds) the push/unbundle apply path should
     * use for this repository -- mirrors real hg's own {@code ui.timeout} config (default {@code
     * "600"} seconds; {@code mercurial/localrepo.py}'s {@code _lock()} reads it whenever a caller
     * asks to wait, and {@code mercurial/lock.py}'s {@code lock()} loop treats {@code timeout == 0}
     * as "fail immediately" rather than "wait forever").
     */
    public int resolvePushLockTimeoutMs() {
        String raw = getConfig().get("ui", "timeout", "600");
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
     * If a leftover {@code .hg/store/journal} exists (from a process that crashed or was killed
     * mid-transaction), restores every backed-up file it references and deletes the journal —
     * matching real hg's own crash-recovery behavior.
     *
     * @apiNote Called automatically by every {@link #lockStore()}/{@link #lockStore(int)}, so
     *     acquiring the store lock before a mutation is itself enough to recover from a prior
     *     crash. {@code RecoverCommand} (hg's {@code recover}) also calls this directly so a
     *     user can trigger recovery explicitly without otherwise touching the repository. A
     *     no-op when no journal file exists.
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
                    File dirstateBackup = new File(hgDir, "dirstate.backup");
                    File dirstateFile = new File(hgDir, "dirstate");
                    if (dirstateBackup.exists()) {
                        Files.move(dirstateBackup.toPath(), dirstateFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        // dirstate-v2's own companion data file: the docket bytes just restored
                        // above may reference a "<uid>" whose ".hg/dirstate.<uid>" data file
                        // Dirstate.write()'s own "W-LEAK" cleanup already deleted (it removes the
                        // *previous* uid's data file the instant the crashed transaction durably
                        // wrote its own new docket) -- restore it from the durable backup
                        // CommitCommand leaves alongside "dirstate.backup" (see its own
                        // recordRevlogRollbackState-adjacent comment) if it is indeed missing.
                        // Without this, real hg's own dirstate-v2 reader treats the dangling
                        // reference as an unrecoverable "dirstate read race" and aborts outright.
                        String uid = readDirstateV2Uid(dirstateFile);
                        if (uid != null) {
                            File dataFile = new File(hgDir, "dirstate." + uid);
                            File dataBackup = new File(hgDir, "dirstateV2.backup.data");
                            if (!dataFile.exists() && dataBackup.exists()) {
                                Files.copy(dataBackup.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                    } else {
                        Files.deleteIfExists(dirstateFile.toPath());
                    }
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
                    // docket references them (a fresh v2 revlog's sidedata companion is
                    // legitimately 0 bytes from the moment the docket is created; deleting it on
                    // rollback instead of truncating would make real hg abort with "No such file
                    // or directory" reading the docket afterward). See
                    // CommitCommand#recordRevlogRollbackState's javadoc.
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

    /**
     * Parses a dirstate-v2 docket file's own {@code uid} field (the identifier of its companion
     * {@code .hg/dirstate.<uid>} data file), or {@code null} if {@code dirstateFile} does not
     * exist, is empty, or is not dirstate-v2 (a plain v1 dirstate has no such structure). See
     * {@code CommitCommand#captureDirstateV2DataBackup}'s javadoc for the exact on-disk layout
     * being read (magic {@code "dirstate-v2\n"}, uid length byte at offset 124, uid bytes
     * immediately after).
     */
    private static String readDirstateV2Uid(File dirstateFile) {
        try {
            byte[] bytes = Files.readAllBytes(dirstateFile.toPath());
            if (bytes.length < 125) {
                return null;
            }
            if (!new String(bytes, 0, 12, StandardCharsets.US_ASCII).equals("dirstate-v2\n")) {
                return null;
            }
            int uidSize = bytes[124] & 0xFF;
            if (bytes.length < 125 + uidSize) {
                return null;
            }
            return new String(bytes, 125, uidSize, StandardCharsets.US_ASCII);
        } catch (Exception e) {
            return null;
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
