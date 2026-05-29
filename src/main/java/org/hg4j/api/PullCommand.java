package org.hg4j.api;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.hg4j.core.ChangegroupParser;
import org.hg4j.core.Dirstate;
import org.hg4j.core.HgLock;
import org.hg4j.transport.HgRemoteConnection;
import org.hg4j.transport.HgRemoteConnectionFactory;
import org.hg4j.core.HgRepository;
import org.hg4j.core.NodeIdUtil;
import org.hg4j.core.Revlog;
import org.hg4j.core.SafeFileIO;
import org.hg4j.lib.ProgressMonitor;
import org.hg4j.lib.NullProgressMonitor;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Porcelain command to pull changes from a remote repository.
 * Built with crash-durable transaction protection and full fncache/on-disk layout fidelity.
 */
public class PullCommand {
    private static final Logger LOGGER = Logger.getLogger(PullCommand.class.getName());

    private final HgRepository repository;
    private String sourceUrl;
    private ProgressMonitor monitor = NullProgressMonitor.INSTANCE;
    private org.hg4j.core.HgTreeFilter treeFilter = org.hg4j.core.HgTreeFilter.ALL;

    public PullCommand(HgRepository repository) {
        this.repository = repository;
    }

    public PullCommand setTreeFilter(org.hg4j.core.HgTreeFilter treeFilter) {
        if (treeFilter != null) {
            this.treeFilter = treeFilter;
        }
        return this;
    }

    public PullCommand setProgressMonitor(ProgressMonitor monitor) {
        if (monitor != null) {
            this.monitor = monitor;
        }
        return this;
    }

    public PullCommand setSource(String sourceUrl) {
        this.sourceUrl = sourceUrl;
        return this;
    }

    public List<byte[]> call() throws IOException {
        if (sourceUrl == null || sourceUrl.isEmpty()) {
            throw new IllegalStateException("Remote source URL must be specified.");
        }

        monitor.start("Pulling changes", 3);

        monitor.update(1);
        try (HgRemoteConnection client = HgRemoteConnectionFactory.createConnection(sourceUrl)) {
            List<String> remoteHeads = client.getHeads();
            if (remoteHeads.isEmpty()) {
                return new ArrayList<>(); // Nothing to pull
            }

            // Check our local heads to request only new history (Delta Pull optimization)
            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            Revlog localChangelog = repository.getRevlog(clIdx, clDat);

            List<String> common = new ArrayList<>();
            int count = localChangelog.getRevisionCount();
            if (count > 0) {
                boolean[] isParent = new boolean[count];
                for (int i = 0; i < count; i++) {
                    Revlog.IndexRecord rec = localChangelog.getIndexRecord(i);
                    if (rec.getParent1() >= 0 && rec.getParent1() < count) {
                        isParent[rec.getParent1()] = true;
                    }
                    if (rec.getParent2() >= 0 && rec.getParent2() < count) {
                        isParent[rec.getParent2()] = true;
                    }
                }
                // Send only local heads as common to avoid overflowing URL length limitations (E6 solved)
                for (int i = 0; i < count; i++) {
                    if (!isParent[i]) {
                        byte[] node = localChangelog.getIndexRecord(i).getNodeId();
                        common.add(NodeIdUtil.toHex(node));
                    }
                }
            }

            // Negotiate getbundle vs changegroup based on remote capabilities
            List<String> caps = client.getCapabilities();
            boolean supportsGetBundle = caps.contains("getbundle") || caps.stream().anyMatch(c -> c.startsWith("getbundle"));

            byte[] bundleBytes;
            if (supportsGetBundle) {
                List<String> bundleCaps = new ArrayList<>();
                boolean supportsBundle2 = caps.contains("bundle2") || caps.stream().anyMatch(c -> c.startsWith("bundle2"));
                if (supportsBundle2) {
                    bundleCaps.add("bundle2");
                    bundleCaps.add("HG20");
                    bundleCaps.add("changegroup=01,02,03");
                    bundleCaps.add("compression=GZ,BZ,ZS");
                }
                bundleBytes = client.getBundle(common, remoteHeads, bundleCaps);
            } else {
                bundleBytes = client.getChangegroup(common);
            }
            monitor.update(1);

            if (bundleBytes == null || bundleBytes.length == 0) {
                monitor.end();
                return new ArrayList<>(); // No new changes
            }

            // Automatically resolve bundle2 container if the payload is packed with HG20 format
            byte[] changegroupBytes = bundleBytes;
            String cgVersion = "01";
            if (bundleBytes.length >= 4 && bundleBytes[0] == 'H' && bundleBytes[1] == 'G' && bundleBytes[2] == '2' && bundleBytes[3] == '0') {
                org.hg4j.core.Bundle2Parser.ExtractedBundle2 ext = org.hg4j.core.Bundle2Parser.extractChangegroupDetailed(new ByteArrayInputStream(bundleBytes));
                changegroupBytes = ext.changegroupBytes;
                cgVersion = ext.cgVersion;
            } else if (bundleBytes.length >= 6 && bundleBytes[0] == 'H' && bundleBytes[1] == 'G' && bundleBytes[2] == '1' && bundleBytes[3] == '0') {
                String comp = new String(bundleBytes, 4, 2, StandardCharsets.US_ASCII);
                ByteArrayInputStream bais = new ByteArrayInputStream(bundleBytes, 6, bundleBytes.length - 6);
                if ("UN".equals(comp)) {
                    changegroupBytes = bais.readAllBytes();
                } else if ("GZ".equals(comp)) {
                    try (java.util.zip.InflaterInputStream iis = new java.util.zip.InflaterInputStream(bais)) {
                        changegroupBytes = iis.readAllBytes();
                    }
                } else if ("BZ".equals(comp)) {
                    byte[] rawData = bais.readAllBytes();
                    byte[] bzData = new byte[rawData.length + 2];
                    bzData[0] = 'B';
                    bzData[1] = 'Z';
                    System.arraycopy(rawData, 0, bzData, 2, rawData.length);
                    try (org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream bzis = 
                                 new org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(new ByteArrayInputStream(bzData))) {
                        changegroupBytes = bzis.readAllBytes();
                    }
                } else {
                    throw new org.hg4j.errors.HgCorruptDataException("Unsupported bundle1 compression format: HG10" + comp);
                }
                cgVersion = "01";
            }

            ChangegroupParser.ChangegroupBundle bundle = ChangegroupParser.parseBundle(new ByteArrayInputStream(changegroupBytes), cgVersion);
            List<byte[]> results = applyBundle(bundle);

            // 5. Bookmarks & Phases Synchronization (TDD Integrity)
            try {
                // Bookmarks Sync
                java.util.Map<String, String> remoteBookmarks = client.listKeys("bookmarks");
                if (remoteBookmarks != null && !remoteBookmarks.isEmpty()) {
                    File bkFile = new File(repository.getHgDir(), "bookmarks");
                    java.util.Map<String, String> localBookmarks = new java.util.LinkedHashMap<>();
                    if (bkFile.exists()) {
                        List<String> bkLines = Files.readAllLines(bkFile.toPath(), StandardCharsets.UTF_8);
                        for (String line : bkLines) {
                            line = line.trim();
                            if (line.isEmpty()) continue;
                            int spaceIdx = line.indexOf(' ');
                            if (spaceIdx != -1) {
                                String node = line.substring(0, spaceIdx).trim();
                                String name = line.substring(spaceIdx + 1).trim();
                                localBookmarks.put(name, node);
                            }
                        }
                    }
                    boolean modifiedBookmarks = false;
                    for (java.util.Map.Entry<String, String> entry : remoteBookmarks.entrySet()) {
                        String name = entry.getKey();
                        String hexNode = entry.getValue();
                        byte[] nodeBytes = NodeIdUtil.fromHex(hexNode);
                        if (localChangelog.findRevision(nodeBytes) != -1) {
                            localBookmarks.put(name, hexNode.substring(0, 40));
                            modifiedBookmarks = true;
                        }
                    }
                    if (modifiedBookmarks) {
                        StringBuilder sb = new StringBuilder();
                        for (java.util.Map.Entry<String, String> entry : localBookmarks.entrySet()) {
                            sb.append(entry.getValue()).append(" ").append(entry.getKey()).append("\n");
                        }
                        SafeFileIO.writeStringAtomic(bkFile, sb.toString());
                    }
                }

                // Phases Sync
                org.hg4j.core.PhaseRoots phaseRoots = repository.getPhaseRoots();
                java.util.Map<String, String> remotePhases = client.listKeys("phases");
                
                // 1. Pull된 커밋들은 기본적으로 로컬에서 PUBLIC(0)으로 격상 (Mercurial 스펙)
                for (byte[] nodeBytes : results) {
                    org.hg4j.lib.NodeId nodeId = new org.hg4j.lib.NodeId(nodeBytes);
                    phaseRoots.setPhase(nodeId, org.hg4j.core.PhaseRoots.Phase.PUBLIC, localChangelog);
                }
                
                // 2. 만약 원격에 명시적인 phase 정보가 들어온 경우 해당 노드의 phase를 로컬에 동기화
                if (remotePhases != null && !remotePhases.isEmpty()) {
                    for (java.util.Map.Entry<String, String> entry : remotePhases.entrySet()) {
                        String hexNode = entry.getKey();
                        int phaseVal = Integer.parseInt(entry.getValue().trim());
                        byte[] nodeBytes = NodeIdUtil.fromHex(hexNode);
                        if (localChangelog.findRevision(nodeBytes) != -1) {
                            org.hg4j.core.PhaseRoots.Phase p = org.hg4j.core.PhaseRoots.Phase.fromValue(phaseVal);
                            phaseRoots.setPhase(new org.hg4j.lib.NodeId(nodeBytes), p, localChangelog);
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to synchronize remote bookmarks or phases during pull", e);
            }

            monitor.update(1);
            monitor.end();
            return results;
        }
    }

    public List<byte[]> applyBundle(ChangegroupParser.ChangegroupBundle bundle) throws IOException {
        List<byte[]> importedCommits = new ArrayList<>();
        if (bundle.changelogEntries.isEmpty()) {
            return importedCommits;
        }

        File dirstateFile = new File(repository.getDirectory(), ".hg/dirstate");
        byte[] dirstateBackup = dirstateFile.exists() ? Files.readAllBytes(dirstateFile.toPath()) : null;
        File fncacheFile = new File(repository.getStoreDir(), "fncache");
        byte[] fncacheBackup = fncacheFile.exists() ? Files.readAllBytes(fncacheFile.toPath()) : null;
        File journalFile = new File(repository.getStoreDir(), "journal");

        java.util.Map<File, Long> fileSizes = new java.util.HashMap<>();

        try (HgLock storeLock = repository.lockStore();
             HgLock wlock = repository.lockWorkingCopy()) {

            // Create physical journal and backups for Crash Resilience (TDD Robustness)
            Files.deleteIfExists(journalFile.toPath());
            
            if (dirstateFile.exists()) {
                File dirstateBackupFile = new File(repository.getDirectory(), ".hg/dirstate.backup");
                Files.copy(dirstateFile.toPath(), dirstateBackupFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                appendToJournal(journalFile, "dirstate");
            }
            if (fncacheFile.exists()) {
                File fncacheBackupFile = new File(repository.getStoreDir(), "fncache.backup");
                Files.copy(fncacheFile.toPath(), fncacheBackupFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                appendToJournal(journalFile, "fncache");
            }

            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
            File mfDat = new File(repository.getStoreDir(), "00manifest.d");

            long clIdxLen = clIdx.exists() ? clIdx.length() : 0L;
            long clDatLen = clDat.exists() ? clDat.length() : 0L;
            long mfIdxLen = mfIdx.exists() ? mfIdx.length() : 0L;
            long mfDatLen = mfDat.exists() ? mfDat.length() : 0L;

            fileSizes.put(clIdx, clIdxLen);
            fileSizes.put(clDat, clDatLen);
            fileSizes.put(mfIdx, mfIdxLen);
            fileSizes.put(mfDat, mfDatLen);

            appendToJournal(journalFile, "store/00changelog.i\t" + clIdxLen);
            appendToJournal(journalFile, "store/00changelog.d\t" + clDatLen);
            appendToJournal(journalFile, "store/00manifest.i\t" + mfIdxLen);
            appendToJournal(journalFile, "store/00manifest.d\t" + mfDatLen);

            // 1. Apply Changelog
            Revlog changelog = repository.getRevlog(clIdx, clDat);
            for (ChangegroupParser.ChangeGroupEntry entry : bundle.changelogEntries) {
                LOGGER.log(Level.FINE, "[DEBUG CHANGELOG] node={0}, deltabase={1}", new Object[]{NodeIdUtil.toHex(entry.node), (entry.deltabase != null ? NodeIdUtil.toHex(entry.deltabase) : "null")});
                int rev = changelog.getRevisionCount();
                changelog.appendChangeGroupEntry(entry, rev);
                importedCommits.add(entry.node);
            }

            // Load fncache paths to record pulled filelogs & treemanifests
            Set<String> fncachePaths = new LinkedHashSet<>();
            if (fncacheFile.exists()) {
                fncachePaths.addAll(Files.readAllLines(fncacheFile.toPath()));
            }

            // 2. Apply Manifest
            if (bundle.manifestGroups != null && !bundle.manifestGroups.isEmpty()) {
                for (ChangegroupParser.ManifestGroup mg : bundle.manifestGroups) {
                    File mIdx, mDat;
                    if (mg.path == null || mg.path.isEmpty()) {
                        mIdx = mfIdx;
                        mDat = mfDat;
                    } else {
                        String storeRel = "meta/" + mg.path + "/00manifest";
                        mIdx = new File(repository.getStoreDir(), NodeIdUtil.encodeFname(storeRel) + ".i");
                        mDat = new File(repository.getStoreDir(), NodeIdUtil.encodeFname(storeRel) + ".d");
                        
                        // Register sub-manifest into fncache for absolute integrity
                        fncachePaths.add("meta/" + mg.path + "/00manifest.i");
                        fncachePaths.add("meta/" + mg.path + "/00manifest.d");

                        if (!fileSizes.containsKey(mIdx)) {
                            long idxLen = mIdx.exists() ? mIdx.length() : 0L;
                            fileSizes.put(mIdx, idxLen);
                            String storeRelIdx = "store/" + NodeIdUtil.encodeFname(storeRel) + ".i";
                            appendToJournal(journalFile, storeRelIdx + "\t" + idxLen);
                        }
                        if (!fileSizes.containsKey(mDat)) {
                            long datLen = mDat.exists() ? mDat.length() : 0L;
                            fileSizes.put(mDat, datLen);
                            String storeRelDat = "store/" + NodeIdUtil.encodeFname(storeRel) + ".d";
                            appendToJournal(journalFile, storeRelDat + "\t" + datLen);
                        }
                        mIdx.getParentFile().mkdirs();
                    }
                    Revlog subManifest = (mIdx == mfIdx) ? repository.getManifestRevlog() : repository.getRevlog(mIdx, mDat);
                    for (ChangegroupParser.ChangeGroupEntry entry : mg.entries) {
                        int linkRev = changelog.findRevision(entry.cs);
                        if (linkRev == -1) {
                            throw new org.hg4j.errors.HgCorruptDataException("Missing link commit for manifest: " + NodeIdUtil.toHex(entry.cs));
                        }
                        subManifest.appendChangeGroupEntry(entry, linkRev);
                    }
                }
            } else {
                Revlog manifest = repository.getManifestRevlog();
                for (ChangegroupParser.ChangeGroupEntry entry : bundle.manifestEntries) {
                    LOGGER.log(Level.FINE, "[DEBUG PULL] manifest entry node={0}, deltabase={1}", new Object[]{NodeIdUtil.toHex(entry.node), (entry.deltabase != null ? NodeIdUtil.toHex(entry.deltabase) : "null")});
                    int linkRev = changelog.findRevision(entry.cs);
                    if (linkRev == -1) {
                        throw new org.hg4j.errors.HgCorruptDataException("Missing link commit for manifest: " + NodeIdUtil.toHex(entry.cs));
                    }
                    manifest.appendChangeGroupEntry(entry, linkRev);
                }
            }

            // 3. Apply Filelogs
            for (ChangegroupParser.FileGroup fg : bundle.fileGroups) {
                String path = fg.path;
                if (treeFilter != null && !treeFilter.accept(path)) {
                    continue;
                }
                File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
                File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");

                if (!fileSizes.containsKey(flIdx)) {
                    long idxLen = flIdx.exists() ? flIdx.length() : 0L;
                    fileSizes.put(flIdx, idxLen);
                    String storeRelIdx = "store/" + NodeIdUtil.encodeFname(path) + ".i";
                    appendToJournal(journalFile, storeRelIdx + "\t" + idxLen);
                }
                if (!fileSizes.containsKey(flDat)) {
                    long datLen = flDat.exists() ? flDat.length() : 0L;
                    fileSizes.put(flDat, datLen);
                    String storeRelDat = "store/" + NodeIdUtil.encodeFname(path) + ".d";
                    appendToJournal(journalFile, storeRelDat + "\t" + datLen);
                }

                flIdx.getParentFile().mkdirs();
                Revlog filelog = repository.getRevlog(flIdx, flDat);

                for (ChangegroupParser.ChangeGroupEntry entry : fg.entries) {
                    int linkRev = changelog.findRevision(entry.cs);
                    if (linkRev == -1) {
                        throw new org.hg4j.errors.HgCorruptDataException("Missing link commit for file revision: " + NodeIdUtil.toHex(entry.cs));
                    }
                    filelog.appendChangeGroupEntry(entry, linkRev);
                }

                String rawPath = "data/" + path.replace('\\', '/');
                fncachePaths.add(rawPath + ".i");
            }

            // Write updated fncache atomically
            if (!fncachePaths.isEmpty()) {
                SafeFileIO.writeLinesAtomic(fncacheFile, new ArrayList<>(fncachePaths));
            }

            // 4. Update Dirstate parent to the latest pulled head if it was empty
            Dirstate dirstate = repository.getDirstate();
            if (NodeIdUtil.isAllZero(dirstate.getParent1()) && !importedCommits.isEmpty()) {
                byte[] latestHead = importedCommits.get(importedCommits.size() - 1);
                dirstate.setParents(latestHead, new byte[20]);
                repository.writeDirstate(dirstate);
            }

            // Clear journal and backup files on complete success
            try {
                Files.deleteIfExists(journalFile.toPath());
                Files.deleteIfExists(new File(repository.getDirectory(), ".hg/dirstate.backup").toPath());
                Files.deleteIfExists(new File(repository.getStoreDir(), "fncache.backup").toPath());
            } catch (Exception ignored) {}

            return importedCommits;

        } catch (Exception t) {
            // Transaction Rollback Session
            for (java.util.Map.Entry<File, Long> sizeEntry : fileSizes.entrySet()) {
                File file = sizeEntry.getKey();
                long origSize = sizeEntry.getValue();
                if (origSize == 0) {
                    try {
                        Files.deleteIfExists(file.toPath());
                    } catch (Exception ignored) {
                        LOGGER.log(Level.WARNING, "Failed to delete size-0 file during pull rollback: " + file, ignored);
                    }
                } else {
                    try (java.nio.channels.FileChannel outChan = java.nio.channels.FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.WRITE)) {
                        outChan.truncate(origSize);
                        outChan.force(true);
                    } catch (Exception ignored) {
                        LOGGER.log(Level.WARNING, "Failed to truncate file during pull rollback: " + file, ignored);
                    }
                }
            }
            if (fncacheBackup != null) {
                try {
                    SafeFileIO.writeAtomic(fncacheFile, fncacheBackup);
                } catch (Exception ignored) {
                    LOGGER.log(Level.WARNING, "Failed to restore fncache backup during pull rollback", ignored);
                }
            } else {
                try {
                    Files.deleteIfExists(fncacheFile.toPath());
                } catch (Exception ignored) {
                    LOGGER.log(Level.WARNING, "Failed to delete fncache during pull rollback", ignored);
                }
            }
            if (dirstateBackup != null) {
                try {
                    SafeFileIO.writeAtomic(dirstateFile, dirstateBackup);
                } catch (Exception ignored) {
                    LOGGER.log(Level.WARNING, "Failed to restore dirstate backup during pull rollback", ignored);
                }
            } else {
                try {
                    Files.deleteIfExists(dirstateFile.toPath());
                } catch (Exception ignored) {
                    LOGGER.log(Level.WARNING, "Failed to delete dirstate during pull rollback", ignored);
                }
            }
            try {
                Files.deleteIfExists(journalFile.toPath());
                Files.deleteIfExists(new File(repository.getDirectory(), ".hg/dirstate.backup").toPath());
                Files.deleteIfExists(new File(repository.getStoreDir(), "fncache.backup").toPath());
            } catch (Exception ignored) {
                LOGGER.log(Level.WARNING, "Failed to clean up journal/backups after pull rollback", ignored);
            }
            repository.clearRevlogCache();
            throw t;
        }
    }

    private void appendToJournal(File journalFile, String entry) throws IOException {
        Files.writeString(journalFile.toPath(), entry + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        try (java.nio.channels.FileChannel fc = java.nio.channels.FileChannel.open(journalFile.toPath(), java.nio.file.StandardOpenOption.WRITE)) {
            fc.force(true);
        }
    }
}
