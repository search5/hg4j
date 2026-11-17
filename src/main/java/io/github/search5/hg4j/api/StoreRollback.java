package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.RevlogIndex;
import io.github.search5.hg4j.util.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

/**
 * The transaction of a store write: the sizes of the files about to be appended to are written to {@code store/journal} first, in the
 * original's format (so an interrupted process leaves what {@code hg recover} needs, see {@link io.github.search5.hg4j.lib.TransactionJournal}),
 * and {@link #abort()} cuts every tracked file back to its size (a file that did not exist is removed, with the companion and nodemap
 * files that a new version 2 revlog got during the transaction), puts back the dockets of version 2 revlogs and the files that were
 * rewritten, and removes the journal. {@link #commit()} removes the journal and the backups. The caller holds the store lock.
 */
final class StoreRollback {

    private final HgRepository repository;
    private final File journal;
    private final Map<File, Long> sizes = new LinkedHashMap<>();
    private final Map<File, byte[]> dockets = new LinkedHashMap<>();
    private final List<File> docketBackups = new ArrayList<>();
    private final Set<File> truncateOnly = new HashSet<>();

    StoreRollback(HgRepository repository) throws IOException {
        this.repository = repository;
        this.journal = new File(repository.getStoreDir(), "journal");
        io.github.search5.hg4j.lib.TransactionJournal.delete(journal);
    }

    private String relative(File f) {
        return repository.getStoreDir().toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/');
    }

    private void log(String entry) throws IOException {
        io.github.search5.hg4j.lib.TransactionJournal.append(journal, entry);
    }

    /** Records a plain store file ({@code fncache}, {@code phaseroots}) that may be appended to or rewritten. */
    void trackFile(File file) throws IOException {
        if (sizes.containsKey(file) || dockets.containsKey(file)) {
            return;
        }
        if (file.exists()) {
            // rewritten files cannot be cut back: keep their content
            byte[] content = Files.readAllBytes(file.toPath());
            dockets.put(file, content);
            File backup = new File(repository.getStoreDir(), "journal.backup." + UUID.randomUUID() + ".bck");
            SafeFileIO.writeAtomic(backup, content);
            docketBackups.add(backup);
            log("backup store/" + relative(file) + "\tstore/" + relative(backup));
        } else {
            sizes.put(file, 0L);
            log("store/" + relative(file) + "\t0");
        }
    }

    /** Records a revlog (index and data file) before it is appended to. */
    void trackRevlog(File index, File data) throws IOException {
        if (sizes.containsKey(index) || dockets.containsKey(index)) {
            return;
        }
        if (index.exists()) {
            RevlogIndex probe = new RevlogIndex(index);
            if (probe.isV2()) {
                byte[] content = Files.readAllBytes(index.toPath());
                dockets.put(index, content);
                File backup = new File(repository.getStoreDir(), "journal.docket." + UUID.randomUUID() + ".bck");
                SafeFileIO.writeAtomic(backup, content);
                docketBackups.add(backup);
                log("backup store/" + relative(index) + "\tstore/" + relative(backup));
                for (File resolved : new File[]{probe.getResolvedIndexFile(), probe.getResolvedDataFile(), probe.getResolvedSidedataFile()}) {
                    if (resolved == null) {
                        continue;
                    }
                    long length = resolved.exists() ? resolved.length() : 0L;
                    sizes.put(resolved, length);
                    truncateOnly.add(resolved);
                    log("trunc store/" + relative(resolved) + "\t" + length);
                }
                return;
            }
        }
        long indexLength = index.exists() ? index.length() : 0L;
        sizes.put(index, indexLength);
        log("store/" + relative(index) + "\t" + indexLength);
        if (data != null) {
            long dataLength = data.exists() ? data.length() : 0L;
            sizes.put(data, dataLength);
            log("store/" + relative(data) + "\t" + dataLength);
        }
    }

    /**
     * A revlog the transaction created in a version 2 repository has companion files ({@code <name>-<id>.idx/.dat/.sda}) and a
     * persistent nodemap ({@code <name>.n}, {@code <name>-<id>.nd}) that the journal could not list, as their names were chosen
     * during the transaction. They go with the new index, like the original's rollback removes every file the transaction created.
     */
    private static void deleteCompanionsOfNewRevlog(File index) throws IOException {
        String name = index.getName();
        if (!name.endsWith(".i") || !index.exists()) {
            return;
        }
        String base = name.substring(0, name.length() - 2);
        java.util.regex.Pattern companion = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(base) + "(-[0-9a-f]{8}\\.(idx|dat|sda|nd)|\\.n)");
        File[] siblings = index.getParentFile().listFiles();
        if (siblings != null) {
            for (File sibling : siblings) {
                if (companion.matcher(sibling.getName()).matches()) {
                    Files.deleteIfExists(sibling.toPath());
                }
            }
        }
    }

    /** The transaction closed: the journal and the backups go. */
    void commit() throws IOException {
        io.github.search5.hg4j.lib.TransactionJournal.delete(journal);
        for (File backup : docketBackups) {
            Files.deleteIfExists(backup.toPath());
        }
    }

    /** The transaction failed: every tracked file is put back, then the journal goes. */
    void abort() throws IOException {
        for (Map.Entry<File, byte[]> e : dockets.entrySet()) {
            SafeFileIO.writeAtomic(e.getKey(), e.getValue());
        }
        for (Map.Entry<File, Long> e : sizes.entrySet()) {
            File file = e.getKey();
            long length = e.getValue();
            if (length == 0 && !truncateOnly.contains(file)) {
                deleteCompanionsOfNewRevlog(file);
                Files.deleteIfExists(file.toPath());
            } else {
                file.getParentFile().mkdirs();
                try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
                    channel.truncate(length);
                    channel.force(true);
                }
            }
        }
        // the persistent nodemap of a revlog was written for the records that are gone; it is rebuilt when needed
        repository.clearRevlogCache();
        commit();
    }
}
