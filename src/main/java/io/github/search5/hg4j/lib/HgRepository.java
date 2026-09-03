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
    private StoreEngine storeEngine = new DefaultFileStoreEngine();
    private Dirstate cachedDirstate = null;
    private final HgRcConfig config = new HgRcConfig();

    public synchronized void setStoreEngine(StoreEngine storeEngine) {
        if (storeEngine != null) {
            this.storeEngine = storeEngine;
            clearRevlogCache();
        }
    }

    public HgRcConfig getConfig() {
        return this.config;
    }

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
                    }
                }
            } catch (Exception ignored) {
                // Fallback to default v1
            }
        }
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

    public synchronized Map<String, String> getManifestAtCommit(byte[] commitNodeId) throws IOException {
        return storeEngine.getManifestAtCommit(this, commitNodeId);
    }

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

    public synchronized Revlog getRevlog(File idxFile, File datFile) throws IOException {
        File canonicalIdx = idxFile.getCanonicalFile();
        if (!revlogCache.containsKey(canonicalIdx)) {
            revlogCache.put(canonicalIdx, storeEngine.getRevlog(this, canonicalIdx, datFile.getCanonicalFile()));
        }
        return revlogCache.get(canonicalIdx);
    }

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
     * {@link com.github.search5.hg4j.storage.RevlogIndex} instances.
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
     * <p>This intentionally does <em>not</em> touch {@code RevlogIndex.checkAndUpdate()}'s
     * existing incremental-reload logic (its {@code addedRecords}-emptiness guard is load-bearing
     * for StripCommand/RebaseCommand/HisteditCommand reusing a handle right after a local
     * truncate -- see that method's own comment) -- it instead forces a full, fresh reconstruction
     * of every cached {@code Revlog}, which naturally starts with an empty {@code addedRecords}
     * and so isn't affected by that guard's "has this instance ever written locally" state.
     */
    public synchronized void refreshIfChangedOnDisk() {
        File clIdx = new File(storeDir, "00changelog.i");
        long size = clIdx.exists() ? clIdx.length() : -1L;
        long mtime = clIdx.exists() ? clIdx.lastModified() : -1L;
        if (size != lastObservedChangelogSize || mtime != lastObservedChangelogMtime) {
            lastObservedChangelogSize = size;
            lastObservedChangelogMtime = mtime;
            clearRevlogCache();
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

    public synchronized boolean isIgnored(String relativePath) {
        loadIgnorePatterns();
        for (Pattern pattern : ignorePatterns) {
            if (pattern.matcher(relativePath).find()) {
                return true;
            }
        }
        return false;
    }

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
     * repository's own tracked manifest -- verified live against real hg 7.2, where {@code hg
     * status}/{@code hg add} at the parent level never see inside a subrepo path at all.
     * Best-effort: any parse failure yields an empty set rather than failing the whole scan.
     */
    private java.util.Set<String> loadSubrepoPaths() {
        File hgsubFile = new File(directory, ".hgsub");
        if (!hgsubFile.exists()) {
            return java.util.Collections.emptySet();
        }
        java.util.Set<String> paths = new java.util.HashSet<>();
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

    private void scanDirectory(File dir, File root, List<String> result, java.util.Set<String> subrepoPaths) {
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
     * Locks the working directory (updates to dirstate or working copy).
     *
     * @return the {@link HgLock} instance
     * @throws HgLockException if acquiring the lock fails
     */
    public synchronized HgLock lockWorkingCopy() throws HgLockException {
        return new HgLock(new File(hgDir, "wlock"), 0, true);
    }

    /**
     * Locks the store repository database (commits, metadata, index updates).
     *
     * @return the {@link HgLock} instance
     * @throws HgLockException if acquiring the lock fails
     */
    public synchronized HgLock lockStore() throws HgLockException {
        HgLock lock = new HgLock(new File(storeDir, "lock"), 0, true);
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
