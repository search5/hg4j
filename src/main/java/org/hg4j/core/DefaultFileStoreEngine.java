package org.hg4j.core;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Standard file-system implementation of StoreEngine.
 * Directly interfaces with the physical .hg repository store on disk.
 */
public class DefaultFileStoreEngine implements StoreEngine {

    @Override
    public Revlog getRevlog(HgRepository repository, File indexFile, File dataFile) throws IOException {
        return new Revlog(indexFile, dataFile);
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

        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        org.hg4j.treewalk.ManifestWalk mw = new org.hg4j.treewalk.ManifestWalk(repository, String.valueOf(commitRev));
        while (mw.next()) {
            org.hg4j.treewalk.ManifestWalk.Entry entry = mw.getEntry();
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
}
