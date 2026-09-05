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
        // exp-changelog-v2 is narrower: it only ever applies to the changelog itself (real hg's
        // own requirement semantics -- manifests/filelogs stay v1 unless exp-revlogv2.2 is
        // *also* set), so this bootstrap path is gated on the index filename actually being
        // 00changelog.i, not just the requirement being present.
        //
        // For the changelog specifically, exp-changelog-v2 ALWAYS takes precedence over a
        // repository-wide exp-revlogv2.2 (general-v2) requirement when both are set together --
        // matches real hg's own precedence exactly (mercurial/revlog.py's `_init_opts`: `if
        // 'changelogv2' in opts and revlog_kind == KIND_CHANGELOG: new_header = CHANGELOGV2 ...
        // elif 'revlogv2' in opts: new_header = REVLOGV2` -- changelogv2 is checked first and wins
        // outright for the changelog, general-v2 never overrides it there). A prior version of
        // this method computed createAsGeneralV2 first and let it win whenever both requirements
        // were active, silently bootstrapping the changelog as plain general-v2 (INDEX_ENTRY_V2,
        // no `rank` field) instead of CHANGELOGV2 (INDEX_ENTRY_CL_V2, has `rank`) -- real hg's own
        // `fast_rank()` unconditionally returns None for any revlog whose format_version isn't
        // CHANGELOGV2, so a *second* real-hg commit on top of such an hg4j-created changelog
        // crashed inside `revlog.py`'s `rank = 1 + self.fast_rank(p1r)` with `TypeError:
        // unsupported operand type(s) for +: 'int' and 'NoneType'` -- found 2026-09-05 by the
        // requirement matrix (RequirementMatrixInitDockerRoundTripTest, the `cl2/general-v2` and
        // `cl2+sidedata/general-v2` combos) doing exactly that as its own acceptance check.
        boolean createAsChangelogV2 = repository.isChangelogV2()
                && "00changelog.i".equals(indexFile.getName()) && !indexFile.exists();
        // A repository-wide exp-revlogv2.2 requirement means EVERY OTHER revlog must be v2,
        // including one that's never existed on disk before (e.g. the filelog for a file committed
        // for the first time) -- v2-ness can't be auto-detected from nothing, so it must be
        // requested explicitly here. Excludes the changelog whenever createAsChangelogV2 already
        // claimed it (see above).
        boolean createAsGeneralV2 = repository.isRevlogV2() && !indexFile.exists() && !createAsChangelogV2;
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
