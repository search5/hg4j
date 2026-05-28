package org.hg4j.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses and writes the Mercurial binary .hg/dirstate file.
 */
public class Dirstate {

    private byte[] parent1 = new byte[20];
    private byte[] parent2 = new byte[20];
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean isV2 = false;

    public boolean isV2() {
        return isV2;
    }

    public void setV2(boolean v2) {
        this.isV2 = v2;
    }

    public record Entry(char state, int mode, int size, long time) {
        public char getState() {
            return state;
        }

        public int getMode() {
            return mode;
        }

        public int getSize() {
            return size;
        }

        public long getTime() {
            return time;
        }
    }

    public byte[] getParent1() {
        return parent1;
    }

    public byte[] getParent2() {
        return parent2;
    }

    public void setParents(byte[] p1, byte[] p2) {
        if (p1 == null || p1.length != 20) {
            throw new IllegalArgumentException("Parent 1 must be exactly 20 bytes");
        }
        if (p2 == null || p2.length != 20) {
            throw new IllegalArgumentException("Parent 2 must be exactly 20 bytes");
        }
        this.parent1 = Arrays.copyOf(p1, 20);
        this.parent2 = Arrays.copyOf(p2, 20);
    }

    public Map<String, Entry> getEntries() {
        return entries;
    }

    public void addEntry(String path, Entry entry) {
        entries.put(path, entry);
    }

    public void removeEntry(String path) {
        entries.remove(path);
    }

    public void read(byte[] bytes) throws IOException {
        if (bytes == null) {
            throw new IOException("Invalid dirstate file: content cannot be null");
        }

        // dirstate-v2 magic bytes \uac10\uc9c0 (Mercurial 6.0+ rust \uad6c\ud604\uc758 \uc2e4\uc81c \ubc14\uc774\ub108\ub9ac magic)
        // magic: \xd8\x1c\x8c\xf5\xfe\x73\x0f\x14 (8\ubc14\uc774\ud2b8)
        if (bytes.length >= 8) {
            byte[] v2Magic = {(byte)0xd8, (byte)0x1c, (byte)0x8c, (byte)0xf5,
                              (byte)0xfe, (byte)0x73, (byte)0x0f, (byte)0x14};
            boolean isV2Magic = true;
            for (int i = 0; i < v2Magic.length; i++) {
                if (bytes[i] != v2Magic[i]) {
                    isV2Magic = false;
                    break;
                }
            }
            if (isV2Magic) {
                this.isV2 = true;
                return;
            }
        }
        // \ubb38\uc790\uc5f4 \uae30\ubc18 \ud3f4\ubc31 \uac10\uc9c0 (\uc77c\ubd80 \ube44\ud45c\uc900 \uc2e4\ud5d8\uc801 \uad6c\ud604 \ub300\ube44)
        if (bytes.length >= 9) {
            String magic = new String(bytes, 0, Math.min(bytes.length, 30), StandardCharsets.UTF_8);
            if (magic.startsWith("dirstate-v2") || magic.startsWith("# dirstate-v2") || magic.startsWith("dirstate2")) {
                this.isV2 = true;
                return;
            }
        }

        if (bytes.length < 40) {
            throw new IOException("Invalid dirstate file: must be at least 40 bytes");
        }

        ByteBuffer buf = ByteBuffer.wrap(bytes);
        buf.get(parent1);
        buf.get(parent2);

        entries.clear();
        while (buf.hasRemaining()) {
            if (buf.remaining() < 17) {
                throw new IOException("Truncated dirstate entry header");
            }
            char state = (char) buf.get();
            int mode = buf.getInt();
            int size = buf.getInt();
            long time = buf.getInt() & 0xFFFFFFFFL; // Parse as unsigned 32-bit int
            int pathLen = buf.getInt();

            if (pathLen < 0 || buf.remaining() < pathLen) {
                throw new IOException("Truncated dirstate entry path");
            }

            byte[] pathBytes = new byte[pathLen];
            buf.get(pathBytes);
            String path = new String(pathBytes, StandardCharsets.UTF_8);
            entries.put(path, new Entry(state, mode, size, time));
        }
    }

    public void read(File file) throws IOException {
        if (file == null || !file.exists()) {
            throw new IOException("Dirstate file does not exist");
        }
        byte[] bytes = Files.readAllBytes(file.toPath());
        read(bytes);
    }

    public byte[] serialize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(parent1);
            out.write(parent2);
            ByteBuffer buf = ByteBuffer.allocate(17);
            for (Map.Entry<String, Entry> item : entries.entrySet()) {
                byte[] pathBytes = item.getKey().getBytes(StandardCharsets.UTF_8);
                Entry entry = item.getValue();
                buf.clear();
                buf.put((byte) entry.getState());
                buf.putInt(entry.getMode());
                buf.putInt(entry.getSize());
                buf.putInt((int) (entry.getTime() & 0xFFFFFFFFL)); // Mask safely to 32-bit for serialization
                buf.putInt(pathBytes.length);
                out.write(buf.array());
                out.write(pathBytes);
            }
        } catch (IOException e) {
            // ByteArrayOutputStream should not throw IOException
            throw new RuntimeException("Serialization failed unexpectedly", e);
        }
        return out.toByteArray();
    }

    public void write(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("Target file cannot be null");
        }
        byte[] bytes = serialize();
        SafeFileIO.writeAtomic(file, bytes);
    }
}
