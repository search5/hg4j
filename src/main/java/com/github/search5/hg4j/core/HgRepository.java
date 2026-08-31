package com.github.search5.hg4j.core;
import com.github.search5.hg4j.storage.DefaultFileStoreEngine;
import com.github.search5.hg4j.storage.StoreEngine;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.revset.HgRevsetEngine;
import com.github.search5.hg4j.phase.PhaseRoots;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.util.SafeFileIO;
import com.github.search5.hg4j.dirstate.Dirstate;

import java.io.File;
import java.io.IOException;
import com.github.search5.hg4j.errors.HgLockException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

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
    private StoreEngine storeEngine = new DefaultFileStoreEngine();
    private Dirstate cachedDirstate = null;

    public synchronized void setStoreEngine(StoreEngine storeEngine) {
        if (storeEngine != null) {
            this.storeEngine = storeEngine;
            clearRevlogCache();
        }
    }

    public HgRepository(File directory) {
        this.directory = directory;
        this.hgDir = new File(directory, ".hg");
        
        File resolvedStoreDir = null;
        File sharedpathFile = new File(hgDir, "sharedpath");
        if (sharedpathFile.exists() && sharedpathFile.isFile()) {
            try {
                String sharedPath = java.nio.file.Files.readString(sharedpathFile.toPath(), java.nio.charset.StandardCharsets.UTF_8).trim();
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
    }

    private void loadRequires() {
        File requiresFile = new File(hgDir, "requires");
        if (requiresFile.exists() && requiresFile.isFile()) {
            try {
                java.util.List<String> lines = java.nio.file.Files.readAllLines(requiresFile.toPath());
                for (String line : lines) {
                    String trimmed = line.trim();
                    if ("dirstate-v2".equals(trimmed)) {
                        this.defaultDirstateV2 = true;
                    } else if ("revlog-compression=zstd".equals(trimmed)) {
                        this.useZstdCompression = true;
                    }
                }
            } catch (Exception ignored) {
                // Fallback to default v1
            }
        }
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
                throw new com.github.search5.hg4j.errors.HgCorruptDataException("Failed to read dirstate and failed to rebuild from manifest", ex);
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
        
        java.util.Map<String, String> manifestMap = getManifestAtCommit(parentNode);
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

    public synchronized java.util.Map<String, String> getManifestAtCommit(byte[] commitNodeId) throws IOException {
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

    private final java.util.Map<File, Revlog> revlogCache = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<File, Revlog> eldest) {
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

    /**
     * Loads the phase roots from the repository.
     * 
     * @return the {@link PhaseRoots} instance
     * @throws IOException if loading fails
     */
    public synchronized PhaseRoots getPhaseRoots() throws IOException {
        File phaserootsFile = new File(hgDir, "phaseroots");
        return new PhaseRoots(phaserootsFile);
    }

    private java.util.List<java.util.regex.Pattern> ignorePatterns = null;

    private synchronized void loadIgnorePatterns() {
        if (ignorePatterns != null) {
            return;
        }
        ignorePatterns = new java.util.ArrayList<>();
        File ignoreFile = new File(directory, ".hgignore");
        if (!ignoreFile.exists()) {
            return;
        }
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(ignoreFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
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
                            ignorePatterns.add(java.util.regex.Pattern.compile(regex));
                        } catch (java.util.regex.PatternSyntaxException e) {
                            // Skip invalid pattern
                        }
                    }
                } else {
                    String regex = line;
                    if (!regex.startsWith("^")) {
                        regex = "(?:" + regex + ")";
                    }
                    try {
                        ignorePatterns.add(java.util.regex.Pattern.compile(regex));
                    } catch (java.util.regex.PatternSyntaxException e) {
                        // Skip invalid pattern
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load ignore patterns from .hgignore", e);
        }
    }

    private java.util.List<String> expandBraces(String glob) {
        java.util.List<String> results = new java.util.ArrayList<>();
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
                sb.append(java.util.regex.Pattern.quote(String.valueOf(c)));
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
        for (java.util.regex.Pattern pattern : ignorePatterns) {
            if (pattern.matcher(relativePath).find()) {
                return true;
            }
        }
        return false;
    }

    public synchronized java.util.List<String> scanWorkingCopy() {
        ignorePatterns = null;
        java.util.List<String> result = new java.util.ArrayList<>();
        scanDirectory(directory, directory, result);
        return result;
    }

    private void scanDirectory(File dir, File root, java.util.List<String> result) {
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
            
            boolean isDir = child.isDirectory() && !java.nio.file.Files.isSymbolicLink(child.toPath());
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
            
            if (isDir) {
                scanDirectory(child, root, result);
            } else if (child.isFile()) {
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
                return java.nio.file.Files.readString(branchFile.toPath(), java.nio.charset.StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("Failed to read branch file", e);
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
            java.util.List<String> lines = java.nio.file.Files.readAllLines(journalFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
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
                            java.nio.file.Files.copy(backupFile.toPath(), originalFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            java.nio.file.Files.deleteIfExists(originalFile.toPath());
                        }
                    }
                } else if (line.equals("dirstate")) {
                    File dirstateBackup = new File(hgDir, "dirstate.backup");
                    File dirstateFile = new File(hgDir, "dirstate");
                    if (dirstateBackup.exists()) {
                        java.nio.file.Files.move(dirstateBackup.toPath(), dirstateFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        java.nio.file.Files.deleteIfExists(dirstateFile.toPath());
                    }
                } else if (line.equals("fncache")) {
                    File fncacheBackup = new File(storeDir, "fncache.backup");
                    File fncacheFile = new File(storeDir, "fncache");
                    if (fncacheBackup.exists()) {
                        java.nio.file.Files.move(fncacheBackup.toPath(), fncacheFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        java.nio.file.Files.deleteIfExists(fncacheFile.toPath());
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
                                java.nio.file.Files.deleteIfExists(file.toPath());
                            } else {
                                try (java.nio.channels.FileChannel outChan = java.nio.channels.FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.WRITE)) {
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
                java.nio.file.Files.deleteIfExists(journalFile.toPath());
                java.nio.file.Files.deleteIfExists(new File(hgDir, "dirstate.backup").toPath());
                java.nio.file.Files.deleteIfExists(new File(storeDir, "fncache.backup").toPath());
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
