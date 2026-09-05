package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.SafeFileIO;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
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
        File undoDirstateData = new File(repository.getDirectory(), ".hg/undo.backup.dirstate.data");
        File undoBookmarks = new File(repository.getDirectory(), ".hg/undo.backup.bookmarks");

        if (!undoFile.exists()) {
            throw new IllegalStateException("No rollback information available (undo file not found)");
        }

        List<File> consumedDocketBackups = new ArrayList<>();
        try {
            // 1. Truncate store files based on undo log
            List<String> lines = Files.readAllLines(undoFile.toPath(), StandardCharsets.UTF_8);
            File storeDir = repository.getStoreDir();

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("backup ")) {
                    // Full-content backup+restore for a v2/docket revlog's "index" file
                    // (00changelog.i/00manifest.i/a filelog's own .i) -- see
                    // CommitCommand#recordRevlogRollbackState's javadoc: unlike every other undo
                    // entry (a byte-length to truncate back to), a v2 docket file never changes
                    // size across commits (only its own content/index_end-data_end pointers do),
                    // so it needs its full pre-commit bytes restored instead. Both paths are
                    // relative to storeDir, matching every other entry in this same file.
                    String rest = line.substring("backup ".length());
                    int tabIdx = rest.indexOf('\t');
                    if (tabIdx != -1) {
                        String origRel = rest.substring(0, tabIdx).trim();
                        String backupRel = rest.substring(tabIdx + 1).trim();
                        File backupFile = new File(storeDir, backupRel);
                        File originalFile = new File(storeDir, origRel);
                        if (backupFile.exists()) {
                            SafeFileIO.writeAtomic(originalFile, Files.readAllBytes(backupFile.toPath()));
                        }
                        consumedDocketBackups.add(backupFile);
                    }
                    continue;
                }
                if (line.startsWith("trunc ")) {
                    // Truncate-only restore, never delete-on-zero -- for a v2/docket revlog's
                    // companion .idx/.dat/.sda files, which real hg always expects to physically
                    // exist (even 0 bytes) as long as the docket references them. See
                    // HgRepository#checkAndPerformAutoRollback's matching branch and
                    // CommitCommand#recordRevlogRollbackState's javadoc for the full story (found
                    // live 2026-09-05: a fresh v2 revlog's sidedata companion is legitimately 0
                    // bytes, and this undo file's generic size-0-means-delete entries below made
                    // rollback delete it outright, leaving real hg unable to even open the repo
                    // afterward).
                    String content = line.substring("trunc ".length()).trim();
                    int truncTabIdx = content.lastIndexOf('\t');
                    if (truncTabIdx != -1) {
                        String relPath = content.substring(0, truncTabIdx).trim();
                        long origSize = Long.parseLong(content.substring(truncTabIdx + 1).trim());
                        File file = new File(storeDir, relPath);
                        File parent = file.getParentFile();
                        if (parent != null) {
                            parent.mkdirs();
                        }
                        try (FileChannel outChan = FileChannel.open(file.toPath(),
                                StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
                            outChan.truncate(origSize);
                            outChan.force(true);
                        }
                    }
                    continue;
                }
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
                // dirstate-v2's own companion data file (backlog #39, found live 2026-09-05):
                // the docket bytes just restored may reference a "<uid>" whose
                // ".hg/dirstate.<uid>" data file Dirstate.write()'s own "W-LEAK" cleanup already
                // deleted (it removes the *previous* uid's data file the instant the commit being
                // rolled back durably wrote its own new docket) -- restore it from
                // CommitCommand#writeUndoInfo's durable backup if it is indeed missing, exactly
                // mirroring HgRepository#checkAndPerformAutoRollback's matching "dirstate" branch.
                // Without this, real hg's own dirstate-v2 reader aborts with "dirstate read race
                // happened 5 times in a row" (verified live against hg-rust-7.2.4).
                String uid = readDirstateV2Uid(dirstateFile);
                if (uid != null) {
                    File dataFile = new File(repository.getDirectory(), ".hg/dirstate." + uid);
                    if (!dataFile.exists() && undoDirstateData.exists()) {
                        Files.copy(undoDirstateData.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
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
            Files.deleteIfExists(undoDirstateData.toPath());
            Files.deleteIfExists(undoBookmarks.toPath());
            for (File backupFile : consumedDocketBackups) {
                Files.deleteIfExists(backupFile.toPath());
            }

            repository.clearRevlogCache();
        }
    }

    /**
     * Parses a dirstate-v2 docket file's own {@code uid} field (the identifier of its companion
     * {@code .hg/dirstate.<uid>} data file), or {@code null} if {@code dirstateFile} does not
     * exist, is empty, or is not dirstate-v2. Mirrors {@code
     * HgRepository#readDirstateV2Uid}/{@code CommitCommand#captureDirstateV2DataBackup} (see
     * either's javadoc for the exact on-disk layout being read).
     */
    private static String readDirstateV2Uid(File dirstateFile) {
        try {
            byte[] bytes = Files.readAllBytes(dirstateFile.toPath());
            if (bytes.length < 125) {
                return null;
            }
            if (!new String(bytes, 0, 12, StandardCharsets.US_ASCII).equals("dirstate-v2\n")) {
                return null;
            }
            int uidSize = bytes[124] & 0xFF;
            if (bytes.length < 125 + uidSize) {
                return null;
            }
            return new String(bytes, 125, uidSize, StandardCharsets.US_ASCII);
        } catch (Exception e) {
            return null;
        }
    }
}
