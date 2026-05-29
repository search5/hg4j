package org.hg4j.api;

import org.hg4j.core.HgRepository;
import org.hg4j.core.Dirstate;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Purge command (equivalent to git clean) for Mercurial repositories.
 * Deletes all untracked files and optionally untracked directories in the workspace.
 */
public class PurgeCommand {
    private final HgRepository repository;
    private boolean purgeDirectories = false;

    public PurgeCommand(HgRepository repository) {
        this.repository = repository;
    }

    public PurgeCommand setPurgeDirectories(boolean purgeDirectories) {
        this.purgeDirectories = purgeDirectories;
        return this;
    }

    /**
     * Executes the purge operation by scanning workspace and physically deleting untracked entities.
     *
     * @throws IOException if workspace scanning or deletion fails
     */
    public void call() throws IOException {
        Dirstate dirstate = repository.getDirstate();
        Set<String> trackedFiles = new HashSet<>(dirstate.getEntries().keySet());

        File root = repository.getDirectory();
        purgePath(root.toPath(), trackedFiles);
    }

    private void purgePath(Path path, Set<String> trackedFiles) throws IOException {
        if (!Files.exists(path)) return;

        // Ensure the metadata .hg directory is never traversed or deleted
        if (path.getFileName() != null && path.getFileName().toString().equals(".hg")) {
            return;
        }

        if (Files.isDirectory(path)) {
            // Traverse children
            try (java.util.stream.Stream<Path> stream = Files.list(path)) {
                java.util.List<Path> children = stream.toList();
                for (Path child : children) {
                    purgePath(child, trackedFiles);
                }
            }
            // Check if directory is untracked and empty, and if we should purge directories
            if (purgeDirectories && !path.equals(repository.getDirectory().toPath())) {
                String rel = repository.getDirectory().toPath().relativize(path).toString().replace('\\', '/');
                if (!rel.isEmpty() && !trackedFiles.contains(rel)) {
                    try (java.util.stream.Stream<Path> stream = Files.list(path)) {
                        if (stream.findAny().isEmpty()) {
                            Files.delete(path);
                        }
                    }
                }
            }
        } else {
            // File node
            String rel = repository.getDirectory().toPath().relativize(path).toString().replace('\\', '/');
            if (!trackedFiles.contains(rel)) {
                Files.delete(path);
            }
        }
    }
}

