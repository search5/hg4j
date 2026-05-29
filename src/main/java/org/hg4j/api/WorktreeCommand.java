package org.hg4j.api;

import org.hg4j.core.HgRepository;
import org.hg4j.core.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Worktree command supporting Git-like 'worktree' and Mercurial-like 'share' mechanism.
 * Creates a new working copy that points to the main repository's shared store directory.
 */
public class WorktreeCommand {
    private final HgRepository repository;
    private File newWorktreeDir;

    public WorktreeCommand(HgRepository repository) {
        this.repository = repository;
    }

    public WorktreeCommand setNewWorktreeDir(File newWorktreeDir) {
        this.newWorktreeDir = newWorktreeDir;
        return this;
    }

    /**
     * Executes the worktree / share creation.
     * Generates a separate .hg working metadata directory linking to the shared central store.
     *
     * @return the newly instantiated HgRepository representing the worktree
     * @throws IOException if directory creation or linking fails
     */
    public HgRepository call() throws IOException {
        if (newWorktreeDir == null) {
            throw new IllegalStateException("New worktree directory must be specified.");
        }

        if (newWorktreeDir.exists() && newWorktreeDir.list() != null && newWorktreeDir.list().length > 0) {
            throw new IOException("Target worktree directory must be empty or non-existent: " + newWorktreeDir);
        }

        File newHgDir = new File(newWorktreeDir, ".hg");
        newHgDir.mkdirs();

        // 1. Create sharedpath linking back to the central store's .hg directory
        File mainHgDir = repository.getHgDir();
        File sharedpathFile = new File(newHgDir, "sharedpath");
        SafeFileIO.writeStringAtomic(sharedpathFile, mainHgDir.getAbsolutePath().replace('\\', '/'));

        // 2. Clone main requires specification to new worktree's requires
        File mainRequires = new File(mainHgDir, "requires");
        File newRequires = new File(newHgDir, "requires");
        if (mainRequires.exists()) {
            Files.copy(mainRequires.toPath(), newRequires.toPath());
        }

        // 3. Initialize independent dirstate for the new worktree
        File newDirstate = new File(newHgDir, "dirstate");
        byte[] mainDirstateBytes = new File(mainHgDir, "dirstate").exists() ? 
                Files.readAllBytes(new File(mainHgDir, "dirstate").toPath()) : new byte[40];
        
        byte[] initialDirstate = new byte[40];
        if (mainDirstateBytes.length >= 40) {
            System.arraycopy(mainDirstateBytes, 0, initialDirstate, 0, 40);
        }
        Files.write(newDirstate.toPath(), initialDirstate);

        // 4. Return new repository handle bound to this worktree
        return new HgRepository(newWorktreeDir);
    }
}
