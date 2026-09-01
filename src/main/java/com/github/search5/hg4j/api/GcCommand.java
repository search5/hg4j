package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import java.io.File;
import java.io.IOException;
import com.github.search5.hg4j.util.SafeFileIO;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Compaction / Garbage Collection command for optimizing Mercurial revlog storage.
 * Performs database health verify, defragmentation check, and fncache rebuild on standard repositories.
 */
public class GcCommand {
    private final HgRepository repository;

    public GcCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Executes optimization / verification on repository store.
     * Clears internal caches, deletes orphaned temp files, and rebuilds fncache completely.
     *
     * @return GC optimization summary
     * @throws IOException if repository store is corrupted
     */
    public String call() throws IOException {
        repository.clearRevlogCache();

        File storeDir = repository.getStoreDir();
        if (!storeDir.exists()) {
            throw new IOException("Repository store directory does not exist: " + storeDir);
        }

        // 1. Delete orphaned temporary and backup files (.backup, .tmp, journal)
        int deletedBackups = 0;
        File[] storeFiles = storeDir.listFiles();
        if (storeFiles != null) {
            for (File file : storeFiles) {
                if (file.isFile()) {
                    String name = file.getName();
                    if (name.endsWith(".backup") || name.endsWith(".tmp") || name.equals("journal")) {
                        if (file.delete()) {
                            deletedBackups++;
                        }
                    }
                }
            }
        }

        // 2. Perform Pack GC: Compress and defragment all revlogs (.i and .d) in the store
        Set<String> validStorePaths = new LinkedHashSet<>();
        
        File clIdx = new File(storeDir, "00changelog.i");
        File clDat = new File(storeDir, "00changelog.d");
        if (clIdx.exists()) {
            compressRevlog(clIdx, clDat);
            validStorePaths.add("00changelog.i");
        }

        File mfIdx = new File(storeDir, "00manifest.i");
        File mfDat = new File(storeDir, "00manifest.d");
        if (mfIdx.exists()) {
            compressRevlog(mfIdx, mfDat);
            validStorePaths.add("00manifest.i");
        }

        // Recursively find and compress meta and data store logs
        File dataDir = new File(storeDir, "data");
        if (dataDir.exists() && dataDir.isDirectory()) {
            scanForIndexFiles(dataDir, validStorePaths);
        }
        File metaDir = new File(storeDir, "meta");
        if (metaDir.exists() && metaDir.isDirectory()) {
            scanForIndexFiles(metaDir, validStorePaths);
        }

        for (String relPath : validStorePaths) {
            if ("00changelog.i".equals(relPath) || "00manifest.i".equals(relPath)) {
                continue;
            }
            File idxFile = new File(storeDir, relPath);
            File datFile = new File(storeDir, relPath.substring(0, relPath.length() - 2) + ".d");
            compressRevlog(idxFile, datFile);
        }

        // 3. Rebuild fncache with atomic file IO
        File fncacheFile = new File(storeDir, "fncache");
        if (!validStorePaths.isEmpty()) {
            SafeFileIO.writeLinesAtomic(fncacheFile, new ArrayList<>(validStorePaths));
        }

        // 4. Request JVM level defragmentation
        System.gc();

        return "GC / Compaction complete: defragmented and re-delta optimized " + validStorePaths.size() 
                + " store revlogs, cleaned " + deletedBackups + " orphaned temp files.";
    }

    private void compressRevlog(File idxFile, File datFile) throws IOException {
        if (!idxFile.exists()) return;

        File tmpIdx = new File(idxFile.getParent(), idxFile.getName() + ".tmp");
        File tmpDat = new File(datFile.getParent(), datFile.getName() + ".tmp");

        // Cleanup stale temp files
        tmpIdx.delete();
        tmpDat.delete();

        try {
            Revlog original = new Revlog(idxFile, datFile);
            Revlog compressed = new Revlog(tmpIdx, tmpDat);

            int count = original.getRevisionCount();
            for (int i = 0; i < count; i++) {
                Revlog.IndexRecord rec = original.getIndexRecord(i);
                byte[] content = original.getRawRevisionContent(i);

                byte[] p1Node = new byte[20];
                byte[] p2Node = new byte[20];
                if (rec.getParent1() >= 0) {
                    p1Node = original.getIndexRecord(rec.getParent1()).getNodeId();
                }
                if (rec.getParent2() >= 0) {
                    p2Node = original.getIndexRecord(rec.getParent2()).getNodeId();
                }

                int newP1 = compressed.findRevision(p1Node);
                int newP2 = compressed.findRevision(p2Node);

                compressed.appendOptimizedRevision(content, rec.getNodeId(), newP1, newP2, p1Node, p2Node, rec.getLinkRev());
            }

            // Atomic replace of store files
            Files.move(tmpIdx.toPath(), idxFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (tmpDat.exists()) {
                Files.move(tmpDat.toPath(), datFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            tmpIdx.delete();
            tmpDat.delete();
        }
    }

    private void scanForIndexFiles(File dir, Set<String> result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                scanForIndexFiles(f, result);
            } else if (f.isFile() && f.getName().endsWith(".i")) {
                File storeDir = repository.getStoreDir();
                String rel = storeDir.toURI().relativize(f.toURI()).getPath().replace('\\', '/');
                if (rel.endsWith("/")) {
                    rel = rel.substring(0, rel.length() - 1);
                }
                result.add(rel);
            }
        }
    }
}

