package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.lib.HgLock;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.errors.HgLockException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import com.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import com.github.search5.hg4j.errors.HgRevisionNotFoundException;
import com.github.search5.hg4j.errors.HgValidationException;
import com.github.search5.hg4j.submodule.HgSubrepoEntry;
import com.github.search5.hg4j.submodule.HgSubrepoParser;
import com.github.search5.hg4j.treewalk.HgTreeFilter;
import com.github.search5.hg4j.treewalk.ManifestTreeIterator;
import com.github.search5.hg4j.treewalk.TreeWalk;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Porcelain command to update (checkout) working copy to a specified target revision.
 * Built with full transaction isolation and strict dirstate state transitions.
 */
public class UpdateCommand {
    private static final Logger LOGGER = Logger.getLogger(UpdateCommand.class.getName());

    private final HgRepository repository;
    private String targetRevision;
    private boolean force = false;
    private HgTreeFilter treeFilter = HgTreeFilter.ALL;
    private final List<HgHook> preUpdateHooks = new ArrayList<>();
    private final List<HgHook> postUpdateHooks = new ArrayList<>();

    public UpdateCommand(HgRepository repository) {
        this.repository = repository;
    }

    public UpdateCommand registerPreUpdateHook(HgHook hook) {
        if (hook != null) {
            preUpdateHooks.add(hook);
        }
        return this;
    }

    public UpdateCommand registerPostUpdateHook(HgHook hook) {
        if (hook != null) {
            postUpdateHooks.add(hook);
        }
        return this;
    }

    public UpdateCommand setTreeFilter(HgTreeFilter treeFilter) {
        if (treeFilter != null) {
            this.treeFilter = treeFilter;
        }
        return this;
    }

    public UpdateCommand setRevision(String targetRevision) {
        this.targetRevision = targetRevision;
        return this;
    }

    public UpdateCommand setForce(boolean force) {
        this.force = force;
        return this;
    }

    public byte[] call() throws IOException, HgLockException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");

        try (HgLock wlock = repository.lockWorkingCopy();
             HgLock storeLock = repository.lockStore()) {

            // PRE_UPDATE hooks trigger
            if (!preUpdateHooks.isEmpty()) {
                Map<String, Object> ctx = new HashMap<>();
                ctx.put("repository", repository);
                ctx.put("targetRevision", targetRevision);
                ctx.put("force", force);
                for (HgHook hook : preUpdateHooks) {
                    if (!hook.run(ctx)) {
                        throw new HgValidationException("Update rejected by PRE_UPDATE hook");
                    }
                }
            }

            Dirstate dirstate = repository.getDirstate();
            if (!force) {
                if (!dirstate.getEntries().isEmpty()) {
                    Status status = new StatusCommand(repository).call();
                    if (!status.getAdded().isEmpty() || !status.getModified().isEmpty() || !status.getRemoved().isEmpty()) {
                        throw new HgValidationException("Working directory has uncommitted changes. Use force to update.");
                    }
                }
            }

            Revlog changelog = repository.getRevlog(clIdx, clDat);
            byte[] targetNodeId = resolveTargetNodeId(changelog);
            if (targetNodeId == null) {
                throw new HgValidationException("Repository is empty, cannot update.");
            }

            int commitRev = NodeIdUtil.findRevisionByNodeId(changelog, targetNodeId);
            if (commitRev == -1) {
                throw new HgRevisionNotFoundException(NodeIdUtil.toHex(targetNodeId));
            }

            // Resolve current parent revision for TreeWalk
            dirstate = repository.getDirstate();
            String parentRev = "";
            if (changelog.getRevisionCount() > 0) {
                byte[] parentNode = dirstate.getParent1();
                int parentRevNum = NodeIdUtil.findRevisionByNodeId(changelog, parentNode);
                if (parentRevNum != -1) {
                    parentRev = String.valueOf(parentRevNum);
                }
            }

            TreeWalk tw = new TreeWalk();
            tw.addTree(new ManifestTreeIterator(repository, parentRev)); // Tree 0: Current Parent Manifest
            tw.addTree(new ManifestTreeIterator(repository, String.valueOf(commitRev))); // Tree 1: Target Commit Manifest

            tw.reset();
            while (tw.next()) {
                String path = tw.getPath();
                if (treeFilter != null && !treeFilter.accept(path)) {
                    continue;
                }
                boolean inParent = tw.isTracked(0);
                boolean inTarget = tw.isTracked(1);

                if (inParent && !inTarget) {
                    // Process deletions: Files in current manifest that are NOT in target manifest
                    File diskFile = new File(repository.getDirectory(), path);
                    if (diskFile.exists()) {
                        Files.delete(diskFile.toPath());
                        // Delete empty parent directories if clean
                        File parent = diskFile.getParentFile();
                        while (parent != null && !parent.equals(repository.getDirectory())) {
                            File[] files = parent.listFiles();
                            if (files == null || files.length == 0) {
                                Files.delete(parent.toPath());
                                parent = parent.getParentFile();
                            } else {
                                break;
                            }
                        }
                    }
                    dirstate.removeEntry(path);
                } else if (inTarget) {
                    // Process updates and creations: Files in target manifest
                    byte[] targetNode = tw.getNodeId(1);
                    String hexNode = NodeIdUtil.toHex(targetNode);
                    boolean executable = tw.isExecutable(1);

                    File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
                    File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");

                    if (!flIdx.exists()) {
                        throw new HgRepositoryNotFoundException("Filelog index not found for tracked file: " + path);
                    }

                    Revlog filelog = repository.getRevlog(flIdx, flDat);
                    int fileRev = NodeIdUtil.findRevisionByNodeId(filelog, targetNode);
                    if (fileRev == -1) {
                        throw new HgRevisionNotFoundException("File version not found in filelog: " + path + " rev hex " + hexNode);
                    }

                    byte[] fileContent = filelog.getRevisionContent(fileRev);

                    boolean symlink = tw.isSymlink(1);

                    File diskFile = new File(repository.getDirectory(), path);
                    boolean needsWrite = true;

                    if (diskFile.exists() || Files.isSymbolicLink(diskFile.toPath())) {
                        if (!symlink) {
                            try {
                                byte[] existingContent = Files.readAllBytes(diskFile.toPath());
                                if (Arrays.equals(existingContent, fileContent)) {
                                    needsWrite = false;
                                }
                            } catch (Exception ignored) {
                            }
                        }
                        if (needsWrite) {
                            Files.delete(diskFile.toPath());
                        }
                    }

                    int mode = 0644;
                    if (needsWrite) {
                        diskFile.getParentFile().mkdirs();
                        if (symlink) {
                            mode = 0120000;
                            String target = new String(fileContent, StandardCharsets.UTF_8).trim();
                            try {
                                Files.createSymbolicLink(diskFile.toPath(), Path.of(target));
                            } catch (Exception e) {
                                Files.write(diskFile.toPath(), fileContent);
                            }
                        } else {
                            Files.write(diskFile.toPath(), fileContent);
                            diskFile.setExecutable(executable, false);
                            mode = executable ? 0755 : 0644;
                        }
                    } else {
                        if (symlink) {
                            mode = 0120000;
                        } else {
                            diskFile.setExecutable(executable, false);
                            mode = executable ? 0755 : 0644;
                        }
                    }

                    int size = fileContent.length;
                    long time = diskFile.lastModified() / 1000;

                    dirstate.addEntry(path, new Dirstate.Entry('n', mode, size, time));
                }
            }

            // 4. Conclude checkout parent node updates
            dirstate.setParents(targetNodeId, new byte[20]);
            repository.writeDirstate(dirstate);

            // Real hg's `hg update` switches the working directory's active branch to
            // whatever branch the target revision was committed on (`hg branch` reflects
            // dirstate.branch, which `merge.update()` always resets on checkout). Without
            // this, the working branch stays stuck on whatever it was before the update,
            // so e.g. the next commit lands on the wrong branch.
            repository.setBranch(CommitCommand.getBranchOfRevision(changelog, commitRev));

            // 4a. Update active bookmark based on targetNodeId (Auto-Switch)
            BookmarkCommand bookmarkCmd = new BookmarkCommand(repository);
            Map<String, String> allBookmarks = bookmarkCmd.call();
            String targetHex = NodeIdUtil.toHex(targetNodeId);
            List<String> matchingBookmarks = new ArrayList<>();
            for (Map.Entry<String, String> entry : allBookmarks.entrySet()) {
                if (entry.getValue().equals(targetHex)) {
                    matchingBookmarks.add(entry.getKey());
                }
            }
            if (matchingBookmarks.size() == 1) {
                bookmarkCmd.setBookmarkName(matchingBookmarks.get(0)).setActive(true).call();
            } else {
                bookmarkCmd.setActive(true).setBookmarkName(null).call();
            }

            // 5. Recursive Subrepo Checkout (JGit-like subrepository checkout support)
            File hgsubFile = new File(repository.getDirectory(), ".hgsub");
            File hgsubstateFile = new File(repository.getDirectory(), ".hgsubstate");
            if (hgsubFile.exists() && hgsubstateFile.exists()) {
                try {
                    byte[] hgsubBytes = Files.readAllBytes(hgsubFile.toPath());
                    byte[] hgsubstateBytes = Files.readAllBytes(hgsubstateFile.toPath());
                    Map<String, HgSubrepoEntry> subrepos = HgSubrepoParser.parseSubrepositories(hgsubBytes, hgsubstateBytes);
                    
                    for (HgSubrepoEntry subEntry : subrepos.values()) {
                        if (subEntry.isGit()) {
                            continue; // Skip Git subrepos
                        }
                        
                        File subDir = new File(repository.getDirectory(), subEntry.getPath());
                        HgRepository subRepo;
                        if (!new File(subDir, ".hg").exists()) {
                            subRepo = Hg.init().setDirectory(subDir).call();
                        } else {
                            subRepo = new HgRepository(subDir);
                        }

                        try (Hg hgSub = Hg.wrap(subRepo)) {
                            if (subEntry.getSourceUrl() != null && !subEntry.getSourceUrl().isEmpty()) {
                                try {
                                    hgSub.pull().setSource(subEntry.getSourceUrl()).call();
                                } catch (Exception e) {
                                    LOGGER.log(Level.WARNING, "Failed to pull subrepo from: " + subEntry.getSourceUrl() + ", error: " + e.getMessage(), e);
                                }
                            }
                            
                            if (subEntry.getRevision() != null && !subEntry.getRevision().isEmpty()) {
                                hgSub.update().setRevision(subEntry.getRevision()).setForce(true).call();
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to perform recursive subrepo checkout", e);
                }
            }

            // POST_UPDATE hooks trigger
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("targetNode", NodeIdUtil.toHex(targetNodeId));
            ctx.put("repository", repository);
            for (HgHook hook : postUpdateHooks) {
                try {
                    hook.run(ctx);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Post-update hook execution failed", e);
                }
            }

            return targetNodeId;
        }
    }

    private byte[] resolveTargetNodeId(Revlog changelog) throws IOException {
        if (changelog.getRevisionCount() == 0) {
            return null;
        }
        try {
            byte[] resolved = NodeIdUtil.resolveRevision(changelog, targetRevision);
            if (resolved != null) {
                return resolved;
            }
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("Ambiguous")) {
                throw new HgRevisionNotFoundException(e.getMessage());
            }
            throw e;
        }

        // 3. Try named branch head
        byte[] branchHead = null;
        for (int i = changelog.getRevisionCount() - 1; i >= 0; i--) {
            String branch = CommitCommand.getBranchOfRevision(changelog, i);
            if (targetRevision.equals(branch)) {
                branchHead = changelog.getIndexRecord(i).getNodeId();
                break;
            }
        }
        if (branchHead != null) {
            return branchHead;
        }

        throw new HgRevisionNotFoundException("Unable to resolve revision identifier: " + targetRevision);
    }
}
