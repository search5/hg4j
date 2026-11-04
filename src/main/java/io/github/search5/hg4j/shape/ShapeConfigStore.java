package io.github.search5.hg4j.shape;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The saved previous {@code server-shapes} configs ({@code shape.py previous_configs}, {@code read_previous_configs},
 * {@code save_previous_config}, {@code clear_previous_configs}). During the time between an admin resharding the store and the new sharded
 * bundles being generated, clients must still be able to clone via the old bundles; the old config keeps their shard fingerprints known.
 *
 * <p>The writers must hold the {@code store-shapes-lock} (and the store lock): they update the list of saved configs.</p>
 */
public final class ShapeConfigStore {

    /** File listing the filenames of the saved configs, one per line. */
    public static final String PREVIOUS_SHAPES_LIST = "server-shapes-previous-list";
    private static final String PREFIX = "server-shapes-previous-";

    private ShapeConfigStore() {
    }

    private static File store(HgRepository repo, String name) {
        return new File(repo.getStoreDir(), name);
    }

    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    /** The filename of each saved config, oldest first. */
    public static List<String> previousConfigs(HgRepository repo) throws IOException {
        File list = store(repo, PREVIOUS_SHAPES_LIST);
        return list.isFile() ? lines(Files.readString(list.toPath(), StandardCharsets.UTF_8)) : new ArrayList<>();
    }

    /** The contents of each saved config that is still there and not empty, oldest first. */
    public static List<byte[]> readPreviousConfigs(HgRepository repo) throws IOException {
        List<byte[]> out = new ArrayList<>();
        for (String name : previousConfigs(repo)) {
            File file = store(repo, name);
            if (file.isFile()) {
                byte[] contents = Files.readAllBytes(file.toPath());
                if (contents.length > 0) {
                    out.add(contents);
                }
            }
        }
        return out;
    }

    /** Saves {@code contents} as a previous config: {@code server-shapes-previous-<first 12 hex of its sha256>}. @return the filename */
    public static String savePreviousConfig(HgRepository repo, byte[] contents) throws IOException {
        String name;
        try {
            name = PREFIX + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents)).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        SafeFileIO.writeAtomic(store(repo, name), contents);
        List<String> saved = previousConfigs(repo);
        if (!saved.contains(name)) {
            saved.add(name);
            SafeFileIO.writeAtomic(store(repo, PREVIOUS_SHAPES_LIST), String.join("\n", saved).getBytes(StandardCharsets.UTF_8));
        }
        return name;
    }

    /** Removes every saved config and empties the list. @return the filenames that were removed */
    public static List<String> clearPreviousConfigs(HgRepository repo) throws IOException {
        List<String> cleared = previousConfigs(repo);
        for (String name : cleared) {
            Files.deleteIfExists(store(repo, name).toPath());
        }
        SafeFileIO.writeAtomic(store(repo, PREVIOUS_SHAPES_LIST), new byte[0]);
        return cleared;
    }

    /**
     * {@code shape.previous_shards_sets}: the shard fingerprint sets, from saved configs, of the shape {@code names} wherever it still has
     * {@code fingerprint}. Only those: a different fingerprint means the shape covered other files.
     */
    public static List<java.util.Set<Fingerprint>> previousShardsSets(HgRepository repo, List<String> names, Fingerprint fingerprint)
            throws IOException {
        List<java.util.Set<Fingerprint>> sets = new ArrayList<>();
        for (byte[] contents : readPreviousConfigs(repo)) {
            StoreShards previous = StoreShards.fromBytes(contents);
            Shape shape;
            try {
                shape = previous.shape(names);
            } catch (ShapeException unknown) {
                continue;
            }
            if (!shape.storeFingerprint().equals(fingerprint)) {
                continue;
            }
            sets.addAll(previous.shardFingerprintsForShape(shape));
        }
        return sets;
    }
}
