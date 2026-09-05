package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import java.io.File;
import java.io.IOException;
import io.github.search5.hg4j.util.SafeFileIO;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

        // 2. Perform Pack GC: Compress and defragment all revlogs (.i and .d) in the store.
        // "fncachePaths" (data/*.i, meta/*.i only -- never the two fixed root revlogs, see
        // below) drives the fncache rebuild in step 3; "compactedCount" is a separate tally of
        // how many revlogs were ACTUALLY rewritten (some are legitimately skipped -- see
        // compressRevlog's javadoc), used only for the summary string.
        int compactedCount = 0;

        File clIdx = new File(storeDir, "00changelog.i");
        File clDat = new File(storeDir, "00changelog.d");
        if (compressRevlog(clIdx, clDat)) {
            compactedCount++;
        }

        File mfIdx = new File(storeDir, "00manifest.i");
        File mfDat = new File(storeDir, "00manifest.d");
        if (compressRevlog(mfIdx, mfDat)) {
            compactedCount++;
        }

        // Recursively find and compress meta and data store logs. Unlike the two root revlogs
        // above, real hg DOES track every one of these individually in fncache -- verified live
        // against real hg 7.2 (2026-09-05): a fresh repository's own fncache never lists
        // "00changelog.i"/"00manifest.i" themselves, only "data/<path>.i"/"meta/<dir>/00manifest.i"
        // entries. The previous version of this method incorrectly folded the two root paths into
        // the same set used to rebuild fncache, writing entries real hg's own fncache never
        // contains -- found live 2026-09-05 (backlog #39 requirement-matrix expansion to
        // GcCommand), even in the plain default combo (not something limited to any exotic
        // storage-extension).
        Set<String> fncachePaths = new LinkedHashSet<>();
        File dataDir = new File(storeDir, "data");
        if (dataDir.exists() && dataDir.isDirectory()) {
            scanForIndexFiles(dataDir, fncachePaths);
        }
        File metaDir = new File(storeDir, "meta");
        if (metaDir.exists() && metaDir.isDirectory()) {
            scanForIndexFiles(metaDir, fncachePaths);
        }

        // Non-inline (split) revlogs get BOTH their ".i" and ".d" path listed in fncache -- unlike
        // the root-revlog exclusion above, this is NOT limited to any particular combo: verified
        // live against plain native hg 7.2 (2026-09-05) with an ordinary >128KB file, whose own
        // fncache lists both "data/big.txt.i" AND "data/big.txt.d". The previous version of this
        // set only ever contained ".i" paths (from scanForIndexFiles, which only looks for ".i"
        // files), so GC's rebuilt fncache was silently missing every split revlog's ".d" entry --
        // caught live via real hg's own "warning: revlog '...' not in fncache!" on `hg verify`
        // after GC ran against a repository containing exactly such a file.
        List<String> dPaths = new ArrayList<>();
        for (String relPath : fncachePaths) {
            File idxFile = new File(storeDir, relPath);
            String datRelPath = relPath.substring(0, relPath.length() - 2) + ".d";
            File datFile = new File(storeDir, datRelPath);
            if (compressRevlog(idxFile, datFile)) {
                compactedCount++;
            }
            if (datFile.exists()) {
                dPaths.add(datRelPath);
            }
        }
        fncachePaths.addAll(dPaths);

        // 3. Rebuild fncache with atomic file IO -- but only for repositories that actually use
        // one. `fileindex-v1` (and `general-v2`, which always implies it) requirements drop
        // fncache/dotencode entirely in favor of the fileindex/fileindex-tree.*/fileindex-meta.*/
        // fileindex-list.* files -- verified live against hg-rust-7.2.4 (2026-09-05): such a
        // repository's `.hg/store/requires` never lists `fncache`, and its store never contains an
        // actual fncache file. Writing one anyway (the previous, unconditional behavior) would
        // create a file real hg does not expect for that combo.
        File fncacheFile = new File(storeDir, "fncache");
        if (!repository.isFileIndexV1() && !fncachePaths.isEmpty()) {
            SafeFileIO.writeLinesAtomic(fncacheFile, new ArrayList<>(fncachePaths));
        }

        // 4. Request JVM level defragmentation
        System.gc();

        return "GC / Compaction complete: defragmented and re-delta optimized " + compactedCount
                + " store revlogs, cleaned " + deletedBackups + " orphaned temp files.";
    }

    /**
     * Recompresses/re-deltas one revlog in place (a temp copy is rebuilt revision-by-revision,
     * then atomically swapped in), skipping any revlog this method cannot losslessly reproduce.
     *
     * @return {@code true} if the revlog was actually rewritten, {@code false} if it did not
     *         exist, had zero revisions, or was skipped as unsupported (see below) -- callers use
     *         this to report an honest "N store revlogs" count rather than counting revlogs that
     *         were merely found.
     *
     * <p><b>v2/docket-based revlogs are never rewritten</b> (found live 2026-09-05, backlog #39
     * requirement-matrix expansion to {@code GcCommand}): {@code changelog-v2}/{@code
     * general-v2} ({@link io.github.search5.hg4j.storage.RevlogIndex#isV2()}) store their actual
     * revisions in UUID-named companion {@code .idx}/{@code .dat}/{@code .sda} files resolved via
     * a small docket header, not in a classic {@code idxFile}/{@code datFile} pair -- {@link
     * Revlog#appendOptimizedRevision} only ever writes classic 64-byte index records and has no
     * v2/docket awareness at all. Running it against a v2 revlog would silently replace that
     * revlog's docket header with an incompatible classic-v1 one (real hg's own reader then
     * either aborts outright or {@code hg verify} reports integrity errors -- verified live
     * against hg-rust-7.2.4). Since real hg itself has no maintenance/compaction operation for
     * these experimental formats to mirror, and getting this wrong is destructive, the only safe
     * behavior is to leave a v2 revlog's on-disk bytes completely untouched.
     */
    private boolean compressRevlog(File idxFile, File datFile) throws IOException {
        if (!idxFile.exists()) return false;

        Revlog original = new Revlog(idxFile, datFile);
        if (original.getIndex().isV2()) {
            return false;
        }

        File tmpIdx = new File(idxFile.getParent(), idxFile.getName() + ".tmp");
        File tmpDat = new File(datFile.getParent(), datFile.getName() + ".tmp");

        // Cleanup stale temp files
        tmpIdx.delete();
        tmpDat.delete();

        try {
            int count = original.getRevisionCount();
            if (count == 0) {
                // Nothing to compact for an empty revlog (e.g. a zero-byte index file left
                // over from an aborted write): the compressed Revlog's tmp files are only
                // created lazily on the first append, so there would be nothing to move.
                return false;
            }

            if (!original.isInline()) {
                // Force the freshly-recreated tmp revlog to start out non-inline too -- Revlog's
                // own "a brand-new non-changelog revlog defaults to inline" policy (backlog #35)
                // is meant for a revlog that has genuinely never been written to yet, not for
                // recreating one that has already legitimately outgrown the inline threshold and
                // split into a separate .d file. Getting this wrong here would silently re-inline
                // a split filelog/manifest, embedding all its data back into the .i file while
                // leaving its real .d file orphaned on disk with stale content (found live
                // 2026-09-05 with a >128KB filelog -- Revlog's own constructor reads
                // `index.isInline()` off an EXISTING (even zero-length) idxFile instead of
                // defaulting to inline, so pre-touching tmpIdx as an empty file is enough to
                // route it there).
                tmpIdx.createNewFile();
            }
            Revlog compressed = new Revlog(tmpIdx, tmpDat);

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
            } else {
                // The original was non-inline but the recompressed copy ended up small enough
                // that nothing was ever written to tmpDat -- shouldn't normally happen (inline-
                // ness is preserved above, not re-decided per revision), but if it ever does,
                // don't leave the OLD .d file's stale bytes sitting next to the new .i file.
                Files.deleteIfExists(datFile.toPath());
            }
            return true;
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

