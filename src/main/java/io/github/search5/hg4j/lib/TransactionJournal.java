package io.github.search5.hg4j.lib;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The on-disk journal of a transaction, in the format of the original ({@code mercurial/transaction.py}), so that a real
 * {@code hg recover} can roll back what an interrupted hg4j process left, and hg4j can roll back what an interrupted {@code hg} left.
 *
 * <ul>
 * <li>{@code .hg/store/journal}: one line per file that is appended to, {@code <path relative to the store>\0<size before the
 * transaction>\n}. A file that did not exist has size 0 and is removed by the rollback.</li>
 * <li>{@code .hg/store/journal.backupfiles}: the version {@code 2}, then one line per file that is rewritten,
 * {@code <location>\0<file>\0<backup file>\0<cache>\n}. The location is empty for the store and {@code plain} for {@code .hg}. The
 * rollback copies the backup over the file, or removes the file when there is no backup (it did not exist before).</li>
 * <li>{@code .hg/store/journal.truncateonly}: hg4j only, ignored by the original. The files that the rollback has to cut back to
 * their size even when it is 0, never remove (the companion files of a version 2 revlog that its docket still names).</li>
 * </ul>
 *
 * <p>The writers of the commands describe an entry as a short text ({@code "fncache"}, {@code "store/<path>\t<size>"},
 * {@code "trunc store/<path>\t<size>"}, {@code "backup store/<path>\tstore/<backup>"}); {@link #append(File, String)} writes it in the
 * original's form. A journal that an older hg4j wrote (lines without a NUL) is still read, see
 * {@link #isOriginalFormat(File)}.</p>
 */
public final class TransactionJournal {
    private static final String BACKUP_FILES_VERSION = "2";

    private TransactionJournal() {
    }

    private static File backupFiles(File journal) {
        return new File(journal.getParentFile(), journal.getName() + ".backupfiles");
    }

    private static File truncateOnly(File journal) {
        return new File(journal.getParentFile(), journal.getName() + ".truncateonly");
    }

    private static void appendLine(File file, String line) throws IOException {
        Files.writeString(file.toPath(), line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (FileChannel fc = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
            fc.force(true);
        }
    }

    private static String storeRelative(String path) {
        return path.startsWith("store/") ? path.substring("store/".length()) : path;
    }

    /**
     * Appends an entry to the journal of a transaction, in the original's format.
     * @param journal the {@code store/journal} file
     * @param entry {@code "fncache"} (the file {@code fncache.backup} next to the journal is its backup),
     *         {@code "store/<path>\t<size>"}, {@code "trunc store/<path>\t<size>"} or {@code "backup store/<path>\tstore/<backup>"}
     * @throws IOException if the journal cannot be written
     */
    public static void append(File journal, String entry) throws IOException {
        if (entry.equals("fncache")) {
            addBackup(journal, "", "fncache", "fncache.backup");
        } else if (entry.startsWith("backup ")) {
            String[] parts = entry.substring("backup ".length()).trim().split("\t", 2);
            if (parts.length != 2) {
                throw new IOException("malformed journal entry: " + entry);
            }
            if (parts[0].startsWith("store/")) {
                addBackup(journal, "", storeRelative(parts[0]), storeRelative(parts[1]));
            } else {
                addBackup(journal, "plain", parts[0], parts[1]);
            }
        } else if (entry.startsWith("trunc ")) {
            String[] parts = splitSize(entry.substring("trunc ".length()));
            appendLine(journal, storeRelative(parts[0]) + "\0" + parts[1]);
            appendLine(truncateOnly(journal), storeRelative(parts[0]));
        } else {
            String[] parts = splitSize(entry);
            appendLine(journal, storeRelative(parts[0]) + "\0" + parts[1]);
        }
    }

    private static String[] splitSize(String text) throws IOException {
        int tab = text.lastIndexOf('\t');
        if (tab < 0) {
            throw new IOException("malformed journal entry: " + text);
        }
        return new String[] {text.substring(0, tab), text.substring(tab + 1).trim()};
    }

    private static void addBackup(File journal, String location, String file, String backup) throws IOException {
        File backups = backupFiles(journal);
        if (!backups.exists()) {
            appendLine(backups, BACKUP_FILES_VERSION);
        }
        appendLine(backups, location + "\0" + file + "\0" + backup + "\0" + "0");
    }

    /**
     * Removes the journal and the files that belong to it (not the backup files they name).
     * @param journal the {@code store/journal} file
     * @throws IOException if a file cannot be removed
     */
    public static void delete(File journal) throws IOException {
        Files.deleteIfExists(journal.toPath());
        Files.deleteIfExists(backupFiles(journal).toPath());
        Files.deleteIfExists(truncateOnly(journal).toPath());
    }

    /**
     * Tells whether a journal is in the original's format (its lines hold a NUL) and not a journal that an older hg4j wrote.
     * @param journal the {@code store/journal} file
     * @return {@code true} for the original's format
     * @throws IOException if the file cannot be read
     */
    public static boolean isOriginalFormat(File journal) throws IOException {
        for (byte b : Files.readAllBytes(journal.toPath())) {
            if (b == 0) {
                return true;
            }
        }
        // an empty journal, or one with only backup entries, has no offset lines
        return Files.readAllBytes(journal.toPath()).length == 0 && backupFiles(journal).exists();
    }

    /**
     * Plays a journal in the original's format back ({@code transaction._playback}): the files are cut back to their recorded size (a
     * file of size 0 is removed, unless it is listed as truncate-only), then the backups are put back, or the file is removed where
     * there is no backup. The journal files and the backups are removed afterwards.
     * @param journal the {@code store/journal} file
     * @param hgDir the {@code .hg} directory (the {@code plain} location)
     * @throws IOException if a file cannot be restored; the journal is kept then
     */
    public static void playback(File journal, File hgDir) throws IOException {
        File storeDir = journal.getParentFile();
        Map<String, Long> entries = new LinkedHashMap<>();
        for (String line : Files.readAllLines(journal.toPath(), StandardCharsets.UTF_8)) {
            if (line.isEmpty()) {
                continue;
            }
            int nul = line.indexOf('\0');
            if (nul < 0) {
                throw new IOException("couldn't read journal entry '" + line + "'");
            }
            entries.put(line.substring(0, nul), Long.parseLong(line.substring(nul + 1).trim()));
        }
        Set<String> cutOnly = new LinkedHashSet<>();
        File cutOnlyFile = truncateOnly(journal);
        if (cutOnlyFile.exists()) {
            cutOnly.addAll(Files.readAllLines(cutOnlyFile.toPath(), StandardCharsets.UTF_8));
        }
        for (Map.Entry<String, Long> e : entries.entrySet()) {
            File file = new File(storeDir, e.getKey());
            long size = e.getValue();
            if (size == 0 && !cutOnly.contains(e.getKey())) {
                Files.deleteIfExists(file.toPath());
            } else if (file.exists() || cutOnly.contains(e.getKey())) {
                file.getParentFile().mkdirs();
                try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
                    channel.truncate(size);
                    channel.force(true);
                }
            }
        }
        List<File> backupsToRemove = new ArrayList<>();
        File backups = backupFiles(journal);
        if (backups.exists()) {
            List<String> lines = Files.readAllLines(backups.toPath(), StandardCharsets.UTF_8);
            if (!lines.isEmpty() && !lines.get(0).equals(BACKUP_FILES_VERSION)) {
                throw new IOException("journal.backupfiles has an unknown version: " + lines.get(0));
            }
            for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\0", -1);
                if (parts.length != 4) {
                    throw new IOException("couldn't read journal backup entry '" + line + "'");
                }
                File base = parts[0].equals("plain") ? hgDir : storeDir;
                File backup = new File(base, parts[2]);
                if (!parts[1].isEmpty() && backup.exists()) {
                    File target = new File(base, parts[1]);
                    target.getParentFile().mkdirs();
                    Files.copy(backup.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    backupsToRemove.add(backup);
                } else {
                    Files.deleteIfExists(new File(base, parts[1].isEmpty() ? parts[2] : parts[1]).toPath());
                }
            }
        }
        for (File backup : backupsToRemove) {
            Files.deleteIfExists(backup.toPath());
        }
        delete(journal);
    }
}
