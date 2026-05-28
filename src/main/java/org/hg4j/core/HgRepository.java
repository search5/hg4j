package org.hg4j.core;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents a local Mercurial repository.
 */
public class HgRepository implements Repository {
    private static final Logger LOGGER = Logger.getLogger(HgRepository.class.getName());
    private final File directory;
    private final File hgDir;
    private final File storeDir;

    public HgRepository(File directory) {
        this.directory = directory;
        this.hgDir = new File(directory, ".hg");
        this.storeDir = new File(hgDir, "store");
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
    public Dirstate getDirstate() throws IOException {
        File dirstateFile = new File(hgDir, "dirstate");
        Dirstate dirstate = new Dirstate();
        if (dirstateFile.exists()) {
            dirstate.read(dirstateFile);
            if (dirstate.isV2()) {
                rebuildDirstateFromManifest(dirstate);
            }
        }
        return dirstate;
    }

    private void rebuildDirstateFromManifest(Dirstate dirstate) {
        try {
            File clIdx = new File(storeDir, "00changelog.i");
            File clDat = new File(storeDir, "00changelog.d");
            File mfIdx = new File(storeDir, "00manifest.i");
            File mfDat = new File(storeDir, "00manifest.d");
            
            if (!clIdx.exists()) {
                dirstate.setParents(new byte[20], new byte[20]);
                return;
            }
            
            Revlog changelog = new Revlog(clIdx, clDat);
            int lastRev = changelog.getRevisionCount() - 1;
            if (lastRev < 0) {
                dirstate.setParents(new byte[20], new byte[20]);
                return;
            }
            
            byte[] parentNode = changelog.getIndexRecord(lastRev).getNodeId();
            dirstate.setParents(parentNode, new byte[20]);
            
            Revlog manifestRevlog = new Revlog(mfIdx, mfDat);
            byte[] clContent = changelog.getRevisionContent(lastRev);
            String clText = new String(clContent, java.nio.charset.StandardCharsets.UTF_8);
            String firstLine = clText.split("\n")[0];
            byte[] mfNode = NodeIdUtil.fromHex(firstLine.trim().substring(0, 40));
            
            int mfRev = NodeIdUtil.findRevisionByNodeId(manifestRevlog, mfNode);
            if (mfRev != -1) {
                byte[] mfContent = manifestRevlog.getRevisionContent(mfRev);
                String mfText = new String(mfContent, java.nio.charset.StandardCharsets.UTF_8);
                
                String[] lines = mfText.split("\n");
                for (String line : lines) {
                    if (line.isEmpty()) continue;
                    int nullIdx = line.indexOf('\0');
                    if (nullIdx != -1) {
                        String path = line.substring(0, nullIdx);
                        File diskFile = new File(directory, path);
                        if (diskFile.exists() && diskFile.isFile()) {
                            int mode = diskFile.canExecute() ? 0755 : 0644;
                            int size = (int) diskFile.length();
                            long time = diskFile.lastModified() / 1000;
                            dirstate.addEntry(path, new Dirstate.Entry('n', mode, size, time));
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Rebuild failure fallback
        } finally {
            dirstate.setV2(false);
        }
    }

    /**
     * Saves the dirstate to the repository.
     * 
     * @param dirstate the dirstate to save
     * @throws IOException if writing fails
     */
    public void writeDirstate(Dirstate dirstate) throws IOException {
        if (dirstate == null) {
            throw new IllegalArgumentException("Dirstate cannot be null");
        }
        File dirstateFile = new File(hgDir, "dirstate");
        dirstate.write(dirstateFile);
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
            revlogCache.put(canonicalIdx, new Revlog(canonicalIdx, datFile.getCanonicalFile()));
        }
        return revlogCache.get(canonicalIdx);
    }

    public synchronized void clearRevlogCache() {
        for (Revlog r : revlogCache.values()) {
            r.clearCache();
        }
        revlogCache.clear();
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
                
                String regex;
                if ("glob".equalsIgnoreCase(syntax)) {
                    regex = globToRegex(line);
                } else {
                    regex = line;
                }
                try {
                    ignorePatterns.add(java.util.regex.Pattern.compile(regex));
                } catch (java.util.regex.PatternSyntaxException e) {
                    // 무효 패턴 건너뜀
                }
            }
        } catch (IOException e) {
            // 무시 파일 로드 에러는 로깅 후 무시 처리 가능
        }
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
            } else if (".\\$^+|()[]{}".indexOf(c) != -1) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        sb.append("$");
        return sb.toString();
    }

    public boolean isIgnored(String relativePath) {
        loadIgnorePatterns();
        for (java.util.regex.Pattern pattern : ignorePatterns) {
            if (pattern.matcher(relativePath).find()) {
                return true;
            }
        }
        return false;
    }

    public java.util.List<String> scanWorkingCopy() {
        synchronized (this) {
            ignorePatterns = null;
        }
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
            
            boolean isDir = child.isDirectory();
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
    public String getBranch() {
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
    public void setBranch(String branch) throws IOException {
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
    public HgLock lockWorkingCopy() throws HgLockException {
        return new HgLock(new File(hgDir, "wlock"));
    }

    /**
     * Locks the store repository database (commits, metadata, index updates).
     *
     * @return the {@link HgLock} instance
     * @throws HgLockException if acquiring the lock fails
     */
    public HgLock lockStore() throws HgLockException {
        HgLock lock = new HgLock(new File(storeDir, "lock"));
        try {
            checkAndPerformAutoRollback();
        } catch (Throwable t) {
            try {
                lock.close();
            } catch (Exception ignored) {}
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
            throw new HgLockException("Failed to perform auto-rollback after lock acquisition", t);
        }
        return lock;
    }

    public void checkAndPerformAutoRollback() {
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
                    String[] parts = line.substring(7).trim().split(" ", 2);
                    if (parts.length == 2) {
                        String origRel = parts[0];
                        String backupRel = parts[1];
                        File originalFile = new File(directory, origRel);
                        File backupFile = new File(directory, backupRel);
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
                    int spaceIdx = line.lastIndexOf(' ');
                    if (spaceIdx != -1) {
                        String filePath = line.substring(0, spaceIdx);
                        long origSize = Long.parseLong(line.substring(spaceIdx + 1));
                        File file = new File(hgDir.getParentFile(), filePath);
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
            // Rollback failsafe - retain journal for retry if recovery failed
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
}
