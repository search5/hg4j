package io.github.search5.hg4j.storage;
import io.github.search5.hg4j.lib.Repository;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;
import io.github.search5.hg4j.dirstate.Dirstate;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import io.github.search5.hg4j.treewalk.ManifestWalk;
import java.util.LinkedHashMap;

/**
 * Standard file-system implementation of StoreEngine.
 * Directly interfaces with the physical .hg repository store on disk.
 */
public class DefaultFileStoreEngine implements StoreEngine {

    @Override
    public Revlog getRevlog(HgRepository repository, File indexFile, File dataFile) throws IOException {
        // A repository-wide exp-revlogv2.2 requirement means EVERY revlog must be v2, including
        // one that's never existed on disk before (e.g. the filelog for a file committed for the
        // first time) -- v2-ness can't be auto-detected from nothing, so it must be requested
        // explicitly here.
        boolean createAsGeneralV2 = repository.isRevlogV2() && !indexFile.exists();
        // exp-changelog-v2 is narrower: it only ever applies to the changelog itself (real hg's
        // own requirement semantics -- manifests/filelogs stay v1 unless exp-revlogv2.2 is
        // *also* set), so this bootstrap path is gated on the index filename actually being
        // 00changelog.i, not just the requirement being present.
        boolean createAsChangelogV2 = !createAsGeneralV2 && repository.isChangelogV2()
                && "00changelog.i".equals(indexFile.getName()) && !indexFile.exists();
        return new Revlog(indexFile, dataFile, repository.isUseZstdCompression(), createAsGeneralV2, createAsChangelogV2, repository.isPersistentNodemap());
    }

    @Override
    public Map<String, String> getManifestAtCommit(HgRepository repository, byte[] commitNodeId) throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);
        int commitRev = NodeIdUtil.findRevisionByNodeId(changelog, commitNodeId);
        if (commitRev == -1) {
            throw new IOException("Commit revision not found: " + NodeIdUtil.toHex(commitNodeId));
        }

        Map<String, String> result = new LinkedHashMap<>();
        ManifestWalk mw = new ManifestWalk(repository, String.valueOf(commitRev));
        while (mw.next()) {
            ManifestWalk.Entry entry = mw.getEntry();
            String hex = entry.getNodeIdHex();
            String flag = entry.isExecutable() ? "x" : (entry.isSymlink() ? "l" : "");
            result.put(entry.getPath(), hex + flag);
        }
        return result;
    }

    @Override
    public Dirstate getDirstate(HgRepository repository) throws IOException {
        File dirstateFile = new File(repository.getHgDir(), "dirstate");
        Dirstate dirstate = new Dirstate();
        if (dirstateFile.exists()) {
            dirstate.read(dirstateFile);
        }
        return dirstate;
    }

    @Override
    public void writeDirstate(HgRepository repository, Dirstate dirstate) throws IOException {
        File dirstateFile = new File(repository.getHgDir(), "dirstate");
        dirstate.write(dirstateFile);
    }

    @Override
    public Revlog getManifestRevlog(HgRepository repository) throws IOException {
        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");
        return repository.getRevlog(mfIdx, mfDat);
    }
}
