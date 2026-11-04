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
 * The transaction of a store write: the sizes of the files about to be appended to are written to {@code store/journal} first (so an
 * interrupted process leaves what {@code hg recover} needs), and {@link #abort()} cuts every tracked file back to its size, restores the
 * dockets of version 2 revlogs, and removes the journal. {@link #commit()} removes the journal. The caller holds the store lock.
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
        Files.deleteIfExists(journal.toPath());
    }

    private String relative(File f) {
        return repository.getStoreDir().toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/');
    }

    private void log(String entry) throws IOException {
        Files.writeString(journal.toPath(), entry + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (FileChannel fc = FileChannel.open(journal.toPath(), StandardOpenOption.WRITE)) {
            fc.force(true);
        }
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

    /** The transaction closed: the journal and the backups go. */
    void commit() throws IOException {
        Files.deleteIfExists(journal.toPath());
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
