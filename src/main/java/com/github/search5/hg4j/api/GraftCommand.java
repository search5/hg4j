package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.errors.HgLockException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import com.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import com.github.search5.hg4j.errors.HgRevisionNotFoundException;
import com.github.search5.hg4j.lib.HgLock;
import com.github.search5.hg4j.obsolete.HgObsMarker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Graft command (equivalent to git cherry-pick) for Mercurial repositories.
 * Copies the changes of a source revision and commits them on top of the current parent.
 */
public class GraftCommand {
    private static final Logger LOGGER = Logger.getLogger(GraftCommand.class.getName());
    private final HgRepository repository;
    private String sourceRevision;
    private final List<HgHook> postGraftHooks = new ArrayList<>();

    public GraftCommand(HgRepository repository) {
        this.repository = repository;
    }

    public GraftCommand setSource(String sourceRevision) {
        this.sourceRevision = sourceRevision;
        return this;
    }

    public GraftCommand registerPostGraftHook(HgHook hook) {
        if (hook != null) {
            postGraftHooks.add(hook);
        }
        return this;
    }

    /**
     * Executes the graft operation.
     * Extracts source file contents and commits them to the current parent, updating the workspace.
     *
     * @return hex node ID of the newly grafted commit
     * @throws IOException if history traversal or file write fails
     */
    public String call() throws IOException, HgLockException {
        if (sourceRevision == null || sourceRevision.isEmpty()) {
            throw new IllegalArgumentException("Source revision must be specified for graft");
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");
        Revlog manifestRevlog = repository.getRevlog(mfIdx, mfDat);

        byte[] origNode = NodeIdUtil.resolveRevision(changelog, sourceRevision);
        if (origNode == null) {
            throw new IOException("Graft source revision not found: " + sourceRevision);
        }
        int origRev = changelog.findRevision(origNode);

        // 1. Get original manifest and changed files
        byte[] origClContent = changelog.getRevisionContent(origRev);
        String origClText = new String(origClContent, StandardCharsets.UTF_8);
        String[] origClLines = origClText.split("\n");

        List<String> filesModified = new ArrayList<>();
        String author = "graft";
        if (origClLines.length > 1) {
            author = origClLines[1].trim();
        }
        // Real hg (mercurial/cmdutil.py graft logic, verified against `hg graft` v7.2) copies
        // the source changeset's exact date onto the grafted commit unless --currentdate/--date
        // is given; parse "secs offset[ extra]" from the 3rd changelog line (date line) so we can
        // pass it through to CommitCommand instead of letting it default to "now".
        Long origDateSecs = null;
        Integer origDateOffset = null;
        if (origClLines.length > 2) {
            String[] dateParts = origClLines[2].trim().split(" ");
            if (dateParts.length >= 2) {
                try {
                    origDateSecs = Long.parseLong(dateParts[0]);
                    origDateOffset = Integer.parseInt(dateParts[1]);
                } catch (NumberFormatException ignored) {
                    origDateSecs = null;
                    origDateOffset = null;
                }
            }
        }
        StringBuilder msgBuilder = new StringBuilder();
        int msgStartIdx = -1;
        for (int i = 3; i < origClLines.length; i++) {
            if (origClLines[i].isEmpty()) {
                msgStartIdx = i + 1;
                break;
            }
            filesModified.add(origClLines[i]);
        }
        if (msgStartIdx != -1) {
            for (int i = msgStartIdx; i < origClLines.length; i++) {
                if (msgBuilder.length() > 0) msgBuilder.append("\n");
                msgBuilder.append(origClLines[i]);
            }
        }
        // Real hg only appends "(grafted from CHANGESETHASH)" to the description when --log is
        // passed (`hg help graft`); a plain `hg graft REV` leaves the description byte-for-byte
        // equal to the source's message (verified with `hg log -r tip` against real hg v7.2).
        // This command doesn't implement --log, so the description is always left untouched.
        String graftMessage = msgBuilder.toString();

        Map<String, String> originalManifest = getManifestForCommit(changelog, manifestRevlog, origNode);

        // Acquire lock explicitly to restore files and commit safely in a transaction
        try (HgLock storeLock = repository.lockStore();
             HgLock wlock = repository.lockWorkingCopy()) {

            // 2. For each modified file in the source revision, copy contents and write to working copy.
            // The dirstate is also updated here (mirroring AddCommand/RemoveCommand's own bookkeeping):
            // a file the source revision touched but that isn't already tracked on the current branch
            // must be marked 'a' (added) or CommitCommand's WorkingDirTreeIterator reports it as
            // untracked ('?') and silently drops it from the grafted commit's manifest; a file the
            // source revision removed must be marked 'r' (removed) or CommitCommand throws
            // "Tracked file not found on disk" once the physical file is deleted below.
            Dirstate dirstate = repository.getDirstate();
            for (String path : filesModified) {
                String hexAndFlag = originalManifest.get(path);
                Dirstate.Entry existingEntry = dirstate.getEntries().get(path);
                if (hexAndFlag == null) {
                    // File deleted in source revision -> delete in working copy too
                    File wFile = new File(repository.getDirectory(), path);
                    if (wFile.exists()) {
                        wFile.delete();
                    }
                    if (existingEntry != null) {
                        if (existingEntry.getState() == 'a') {
                            dirstate.removeEntry(path);
                        } else {
                            dirstate.addEntry(path, new Dirstate.Entry('r', 0, 0, 0));
                        }
                    }
                    continue;
                }
                String fileHex = hexAndFlag.substring(0, 40);
                // Manifest entries are "<40-hex-nodeid><flag>": flag is "x" (executable),
                // "l" (symlink) or empty (verified against `hg manifest --debug`, e.g.
                // "<hash> 755 * script.sh" / "<hash> 644 @ link.txt"). CommitCommand.getRevisionContent
                // only needs the node hex, but real `hg graft` (verified against `hg graft` v7.2:
                // grafting an executable script or a symlink onto a branch that never had it
                // restores the exact mode/symlink-ness in the working copy) also restores this
                // flag onto the copied working-copy file -- previously dropped here, which meant a
                // grafted executable script or symlink silently lost its mode/symlink-ness.
                String flag = hexAndFlag.length() > 40 ? hexAndFlag.substring(40) : "";
                boolean symlink = flag.contains("l");
                boolean executable = flag.contains("x");
                byte[] fileContent = getFileRevisionContent(repository, path, fileHex);

                File wFile = new File(repository.getDirectory(), path);
                wFile.getParentFile().mkdirs();
                if (symlink) {
                    if (wFile.exists() || Files.isSymbolicLink(wFile.toPath())) {
                        Files.delete(wFile.toPath());
                    }
                    String target = new String(fileContent, StandardCharsets.UTF_8).trim();
                    try {
                        Files.createSymbolicLink(wFile.toPath(), java.nio.file.Path.of(target));
                    } catch (Exception e) {
                        Files.write(wFile.toPath(), fileContent);
                    }
                } else {
                    Files.write(wFile.toPath(), fileContent);
                    wFile.setExecutable(executable, false);
                }

                if (existingEntry == null) {
                    int mode = symlink ? 0120000 : (executable ? 0755 : 0644);
                    int size = fileContent.length;
                    long time = wFile.lastModified() / 1000;
                    dirstate.addEntry(path, new Dirstate.Entry('a', mode, size, time));
                }
            }
            repository.writeDirstate(dirstate);

            // 3. Delegate execution to CommitCommand to ensure locks, rollback journal,
            // fncache registry, phase draft transition, and hooks are fully executed!
            CommitCommand commitCmd = new CommitCommand(repository);
            commitCmd.setAuthor(author);
            commitCmd.setMessage(graftMessage);
            commitCmd.setSkipLockAndJournal(true);
            if (origDateSecs != null && origDateOffset != null) {
                commitCmd.setDate(origDateSecs, origDateOffset);
            }

            byte[] newCommitNode = commitCmd.call();

            // Register obsolescence marker linking original commit to grafted commit
            try {
                HgObsMarker.writeMarker(repository.getStoreDir(), origNode, List.of(newCommitNode), "graft");
            } catch (Exception e) {
                // non-blocking
            }

            // POST_GRAFT hooks trigger
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("sourceRevision", sourceRevision);
            ctx.put("graftedNode", NodeIdUtil.toHex(newCommitNode));
            ctx.put("repository", repository);
            for (HgHook hook : postGraftHooks) {
                try {
                    hook.run(ctx);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Post-graft hook execution failed", e);
                }
            }

            return NodeIdUtil.toHex(newCommitNode);
        }
    }

    private Map<String, String> getManifestForCommit(Revlog changelog, Revlog manifestRevlog, byte[] commitNode) throws IOException {
        Map<String, String> manifestMap = new LinkedHashMap<>();
        if (commitNode == null || NodeIdUtil.isAllZero(commitNode)) {
            return manifestMap;
        }
        int rev = changelog.findRevision(commitNode);
        if (rev == -1) {
            return manifestMap;
        }
        byte[] content = changelog.getRevisionContent(rev);
        String text = new String(content, StandardCharsets.UTF_8);
        String[] lines = text.split("\n");
        if (lines.length == 0) return manifestMap;

        String manifestHex = lines[0].trim();
        byte[] manifestNode = NodeIdUtil.fromHex(manifestHex);
        int mRev = manifestRevlog.findRevision(manifestNode);
        if (mRev != -1) {
            byte[] mContent = manifestRevlog.getRevisionContent(mRev);
            String mText = new String(mContent, StandardCharsets.UTF_8);
            for (String line : mText.split("\n")) {
                if (line.isEmpty()) continue;
                int nullIdx = line.indexOf('\0');
                if (nullIdx != -1) {
                    manifestMap.put(line.substring(0, nullIdx), line.substring(nullIdx + 1));
                }
            }
        }
        return manifestMap;
    }

    private byte[] getFileRevisionContent(HgRepository repository, String path, String nodeHex) throws IOException {
        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        if (!flIdx.exists()) {
            throw new HgRepositoryNotFoundException("Filelog index does not exist for: " + path);
        }
        Revlog filelog = repository.getRevlog(flIdx, flDat);
        int rev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(nodeHex.substring(0, 40)));
        if (rev == -1) {
            throw new HgRevisionNotFoundException("File revision not found: " + path + " @ " + nodeHex);
        }
        return filelog.getRevisionContent(rev);
    }
}
