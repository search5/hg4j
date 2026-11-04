package io.github.search5.hg4j.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

/**
 * Provides production-grade safe and atomic file I/O operations.
 */
public final class SafeFileIO {

    /**
     * Gives {@code file} an inode of its own when other directory entries share it. Real hg's vfs does the same before it opens a
     * file for writing ({@code nlink > 1}: a temporary copy replaces the file), which is what makes a store copied with hard links
     * ({@code hg clone}, {@code unshare}) safe to write to: without it, appending to one repository would change the other.
     */
    public static void breakHardLinks(File file) throws IOException {
        java.nio.file.Path path = file.toPath();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Object links;
        try {
            links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException | IllegalArgumentException notUnix) {
            return; // no link count on this file system
        }
        if (!(links instanceof Integer) || (Integer) links <= 1) {
            return;
        }
        java.nio.file.Path copy = Files.createTempFile(path.getParent(), file.getName(), ".nlink");
        try {
            Files.copy(path, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            Files.move(copy, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    /** {@code new FileOutputStream(file, true)} for a repository file that may be hard linked to another repository's. */
    public static java.io.FileOutputStream openAppend(File file) throws IOException {
        breakHardLinks(file);
        return new java.io.FileOutputStream(file, true);
    }

    /** {@code new RandomAccessFile(file, "rw")} for a repository file that may be hard linked to another repository's. */
    public static RandomAccessFile openReadWrite(File file) throws IOException {
        breakHardLinks(file);
        return new RandomAccessFile(file, "rw");
    }


    private SafeFileIO() {
        // Prevent instantiation
    }

    /**
     * A file's own last-modified time, in whole epoch seconds — for a symlink, its own mtime,
     * <b>not</b> whatever it points at. {@code java.io.File#lastModified()} always follows a
     * symlink (there is no {@code NOFOLLOW_LINKS} equivalent for the legacy {@code File} API),
     * so calling it on a symlink silently returns the TARGET's mtime instead. For dirstate
     * bookkeeping this is a real, timing-dependent bug — not just wrong for a dangling symlink —
     * since it makes an untouched symlink look "changed" (or "unchanged" by coincidence)
     * whenever its target's mtime happens to floor to a different whole second than what was
     * last recorded, confirmed by reproducing it in isolated test runs (2026-09-03). Every
     * porcelain command that records a file's mtime into the dirstate must use this instead of
     * {@code File#lastModified()}.
     */
    public static long lastModifiedSeconds(File file) throws IOException {
        return Files.getLastModifiedTime(file.toPath(), LinkOption.NOFOLLOW_LINKS).to(TimeUnit.SECONDS);
    }

    /**
     * Writes raw bytes atomically by writing to a temporary file in the same directory,
     * and then renaming it via ATOMIC_MOVE.
     * Falls back to defensive .lock file-level exclusive locking by default for ultimate safety (L-4).
     */
    public static void writeAtomic(File file, byte[] data) throws IOException {
        writeAtomic(file, data, false); // Default safe fallback (Defense-in-depth)
    }

    /**
     * Writes raw bytes atomically with optional lock bypass when the upper transaction lock (wlock/lock) is guaranteed.
     */
    public static void writeAtomic(File file, byte[] data, boolean bypassLock) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("Target file cannot be null");
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        if (bypassLock) {
            // High-performance write bypassing duplicate OS locks
            executeAtomicMove(file, data, parent);
        } else {
            // Fail-safe defense: Acquire exclusive OS file-level lock on dedicated .lock file
            // Bind with synchronized to prevent OverlappingFileLockException when multiple threads in the same JVM process try to call lock() on the same file
            synchronized (SafeFileIO.class) {
                File lockFile = new File(parent, file.getName() + ".lock");
                try (RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
                     FileChannel lockChannel = raf.getChannel();
                     FileLock fileLock = lockChannel.lock()) {
                    executeAtomicMove(file, data, parent);
                } finally {
                    try {
                        Files.deleteIfExists(lockFile.toPath());
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private static void executeAtomicMove(File file, byte[] data, File parent) throws IOException {
        File tempFile = File.createTempFile(file.getName() + "_", ".tmp", parent);
        try {
            // Write and physically fsync to disk before renaming (Durability)
            try (FileChannel channel = FileChannel.open(tempFile.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(data));
                channel.force(true);
            }
            
            Files.move(tempFile.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            
            // Attempt to fsync the parent directory to persist directory entries (metadata) on supported filesystems/platforms
            if (parent != null) {
                try (FileChannel dirChannel = FileChannel.open(parent.toPath(),
                        StandardOpenOption.READ)) {
                    dirChannel.force(true);
                } catch (Exception ignored) {
                    // Silent fallback if platform/filesystem does not support directory fsync
                }
            }
        } catch (IOException e) {
            if (tempFile.exists()) {
                tempFile.delete();
            }
            throw e;
        }
    }

    /**
     * Writes a String atomically using UTF-8 encoding.
     */
    public static void writeStringAtomic(File file, String content) throws IOException {
        writeAtomic(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes a list of lines atomically.
     */
    public static void writeLinesAtomic(File file, List<String> lines) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        writeStringAtomic(file, sb.toString());
    }
}
