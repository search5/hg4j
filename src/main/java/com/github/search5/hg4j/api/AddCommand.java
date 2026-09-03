package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.errors.HgLockException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import com.github.search5.hg4j.errors.HgValidationException;
import com.github.search5.hg4j.lib.HgLock;
import com.github.search5.hg4j.util.SafeFileIO;

/**
 * Adds untracked files to the repository tracking list.
 */
public class AddCommand {

    private final HgRepository repository;
    private final List<String> files = new ArrayList<>();

    public AddCommand(HgRepository repository) {
        this.repository = repository;
    }

    public AddCommand addFile(String file) {
        if (file != null && !file.isEmpty()) {
            files.add(file.replace('\\', '/'));
        }
        return this;
    }

    public void call() throws IOException, HgLockException {
        try (HgLock wlock = repository.lockWorkingCopy()) {
            Dirstate dirstate = repository.getDirstate();

            List<String> filesToAdd = new ArrayList<>(files);
            if (filesToAdd.isEmpty()) {
                // Scan for all untracked files
                List<String> allFiles = repository.scanWorkingCopy();
                for (String relPath : allFiles) {
                    if (!dirstate.getEntries().containsKey(relPath)) {
                        filesToAdd.add(relPath);
                    }
                }
            }

            for (String relPath : filesToAdd) {
                File diskFile = new File(repository.getDirectory(), relPath);
                boolean isSymlink = Files.isSymbolicLink(diskFile.toPath());
                // A symlink is tracked as-is even when its target is missing (dangling) or not
                // a plain file — real hg accepts it (verified live: `ln -s missing.txt l.txt;
                // hg add` -> "A l.txt"). Only reject non-symlink paths that genuinely aren't a
                // regular file.
                if (!isSymlink && (!diskFile.exists() || !diskFile.isFile())) {
                    throw new HgValidationException("File not found or is not a standard file: " + relPath);
                }

                boolean executable = !isSymlink && diskFile.canExecute();
                int mode = executable ? 0755 : 0644;
                // A symlink's tracked "size" is the length of its own target path string
                // (lstat semantics, matching what CommitCommand records for the filelog
                // content), not File.length(), which follows the link (and returns 0 for a
                // dangling target).
                int size = isSymlink
                        ? Files.readSymbolicLink(diskFile.toPath()).toString().getBytes(StandardCharsets.UTF_8).length
                        : (int) diskFile.length();
                long time = SafeFileIO.lastModifiedSeconds(diskFile);

                Dirstate.Entry entry = new Dirstate.Entry('a', mode, size, time);
                dirstate.addEntry(relPath, entry);
            }

            repository.writeDirstate(dirstate);
        }
    }
}
