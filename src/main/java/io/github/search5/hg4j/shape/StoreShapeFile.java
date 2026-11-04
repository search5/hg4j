package io.github.search5.hg4j.shape;

import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code store-shape} file of a repository cloned with {@code --shape} ({@code narrowspec.SHAPE_FILENAME}, {@code write_shape},
 * {@code parse_shape}, {@code load_store_shape}): version 0 is two lines, {@code 0} and the shape's names joined by commas.
 */
public final class StoreShapeFile {

    public static final String FILENAME = "store-shape";

    private StoreShapeFile() {
    }

    private static String decode(byte[] name) throws HgValidationException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(name)).toString();
        } catch (CharacterCodingException e) {
            throw new HgValidationException("store-shape contains invalid UTF8");
        }
    }

    /** {@code write_shape}: the bytes of the file for these names. */
    public static byte[] format(List<String> names) throws HgValidationException {
        String joined = String.join(",", names);
        decode(joined.getBytes(StandardCharsets.UTF_8));
        return ("0\n" + joined + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * {@code parse_shape}: the names in the file.
     *
     * @throws HgValidationException {@code empty store-hape file}, {@code invalid store-hape version line}, {@code unknown store-hape version
     *                               number: N}, {@code too many lines in store-hape: expected 2, got N} (sic, as hg spells them)
     */
    public static List<String> parse(byte[] contents) throws HgValidationException {
        List<byte[]> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= contents.length; i++) {
            if (i == contents.length || contents[i] == '\n') {
                if (i == contents.length && start == i) {
                    break;
                }
                int end = i > start && contents[i - 1] == '\r' ? i - 1 : i;
                lines.add(java.util.Arrays.copyOfRange(contents, start, end));
                start = i + 1;
            }
        }
        if (lines.isEmpty()) {
            throw new HgValidationException("empty store-hape file");
        }
        int version;
        try {
            version = Integer.parseInt(new String(lines.get(0), StandardCharsets.US_ASCII).trim());
        } catch (NumberFormatException e) {
            throw new HgValidationException("invalid store-hape version line");
        }
        if (version != 0) {
            throw new HgValidationException("unknown store-hape version number: " + version);
        }
        if (lines.size() != 2) {
            throw new HgValidationException("too many lines in store-hape: expected 2, got " + lines.size());
        }
        return List.of(decode(lines.get(1)).split(",", -1));
    }

    /** {@code load_store_shape}: the names the repository was cloned with, or {@code null} when it has no {@code store-shape} file. */
    public static List<String> load(HgRepository repo) throws IOException {
        File pending = new File(repo.getStoreDir(), FILENAME + ".pending");
        File file = new File(repo.getStoreDir(), FILENAME);
        if (pending.isFile() && System.getenv("HG_PENDING") != null) {
            return parse(Files.readAllBytes(pending.toPath()));
        }
        return file.isFile() ? parse(Files.readAllBytes(file.toPath())) : null;
    }

    /** Writes the file (the caller holds the store lock, as hg writes it inside the clone's transaction). */
    public static void write(HgRepository repo, List<String> names) throws IOException {
        SafeFileIO.writeAtomic(new File(repo.getStoreDir(), FILENAME), format(names));
    }
}
