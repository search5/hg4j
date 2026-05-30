package org.hg4j.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Provides production-grade safe and atomic file I/O operations.
 */
public final class SafeFileIO {

    private SafeFileIO() {
        // Prevent instantiation
    }

    /**
     * Writes raw bytes atomically by writing to a temporary file in the same directory,
     * and then renaming it via ATOMIC_MOVE.
     */
    public static void writeAtomic(File file, byte[] data) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("Target file cannot be null");
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        File tempFile = File.createTempFile(file.getName() + "_", ".tmp", parent);
        try {
            // Write and physically fsync to disk before renaming (Durability)
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(tempFile.toPath(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(data));
                channel.force(true);
            }
            
            Files.move(tempFile.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            
            // Attempt to fsync the parent directory to persist directory entries (metadata) on supported filesystems/platforms
            if (parent != null) {
                try (java.nio.channels.FileChannel dirChannel = java.nio.channels.FileChannel.open(parent.toPath(),
                        java.nio.file.StandardOpenOption.READ)) {
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
