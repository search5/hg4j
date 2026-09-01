package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.SafeFileIO;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.nio.file.StandardCopyOption;

/**
 * Porcelain command to rollback the last successful transaction.
 * Conforms to the undo.* layout specifications to restore store files, dirstate, and bookmarks.
 */
public class RollbackCommand {

    private final HgRepository repository;

    public RollbackCommand(HgRepository repository) {
        this.repository = repository;
    }

    public void call() throws IOException {
        File undoFile = new File(repository.getStoreDir(), "undo");
        File undoBackupFiles = new File(repository.getStoreDir(), "undo.backup.files");
        File undoDirstate = new File(repository.getDirectory(), ".hg/undo.backup.dirstate");
        File undoBookmarks = new File(repository.getDirectory(), ".hg/undo.backup.bookmarks");

        if (!undoFile.exists()) {
            throw new IllegalStateException("No rollback information available (undo file not found)");
        }

        try {
            // 1. Truncate store files based on undo log
            List<String> lines = Files.readAllLines(undoFile.toPath(), StandardCharsets.UTF_8);
            File storeDir = repository.getStoreDir();

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int tabIdx = line.indexOf('\t');
                if (tabIdx != -1) {
                    String relPath = line.substring(0, tabIdx).trim();
                    long origSize = Long.parseLong(line.substring(tabIdx + 1).trim());

                    File file = new File(storeDir, relPath);
                    if (origSize == 0) {
                        Files.deleteIfExists(file.toPath());
                    } else if (file.exists()) {
                        try (FileChannel outChan = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
                            outChan.truncate(origSize);
                            outChan.force(true);
                        }
                    }
                }
            }

            // 2. Restore Dirstate
            File dirstateFile = new File(repository.getDirectory(), ".hg/dirstate");
            if (undoDirstate.exists()) {
                byte[] backupBytes = Files.readAllBytes(undoDirstate.toPath());
                SafeFileIO.writeAtomic(dirstateFile, backupBytes);
            } else {
                Files.deleteIfExists(dirstateFile.toPath());
            }

            // 3. Restore Bookmarks
            File bookmarksFile = new File(repository.getDirectory(), ".hg/bookmarks");
            if (undoBookmarks.exists()) {
                Files.copy(undoBookmarks.toPath(), bookmarksFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(bookmarksFile.toPath());
            }

            // Clean current active bookmark pointer if needed
            File curBkFile = new File(repository.getDirectory(), ".hg/bookmarks.current");
            Files.deleteIfExists(curBkFile.toPath());

        } finally {
            // 4. Clean up undo info files
            Files.deleteIfExists(undoFile.toPath());
            Files.deleteIfExists(undoBackupFiles.toPath());
            Files.deleteIfExists(undoDirstate.toPath());
            Files.deleteIfExists(undoBookmarks.toPath());

            repository.clearRevlogCache();
        }
    }
}
