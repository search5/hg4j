package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.errors.HgCorruptDataException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.io.DataOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fills the coverage gaps left by {@link HgRemainingPorcelainCoverageTest}'s single HG10UN happy
 * path: the {@code bundleFile} null/missing guard, every compression variant of the HG10 bundle1
 * container (GZ/BZ/unsupported), the HG20 bundle2 container, and both "header not recognized at
 * all" fallback paths for {@link UnbundleCommand#call()}.
 */
public class UnbundleCommandCoverageTest {

    /** Builds a real serialized changegroup (as bytes, no container header) from a tiny repo. */
    private static byte[] realChangegroupBytes(Path tempDir, String name) throws Exception {
        File srcDir = tempDir.resolve(name).toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        try (Hg srcHg = Hg.wrap(srcRepo)) {
            File f = new File(srcDir, "a.txt");
            Files.writeString(f.toPath(), "content for " + name);
            srcHg.add().addFile("a.txt").call();
            srcHg.commit().setAuthor("tester").setMessage("only commit").call();
        }
        ChangegroupParser.ChangegroupBundle bundle = HgTestUtils.createMockBundleFromRepo(srcRepo);
        return HgTestUtils.serializeBundleToBytes(bundle);
    }

    private static void assertAppliesSingleRevision(Path tempDir, String dstName, byte[] bundleBytes) throws Exception {
        File bundleFile = tempDir.resolve(dstName + ".hg").toFile();
        Files.write(bundleFile.toPath(), bundleBytes);

        File dstDir = tempDir.resolve(dstName + "_repo").toFile();
        HgRepository dstRepo = Hg.init().setDirectory(dstDir).call();
        try (Hg dstHg = Hg.wrap(dstRepo)) {
            List<byte[]> imported = dstHg.unbundle().setBundleFile(bundleFile).call();
            assertEquals(1, imported.size());

            Revlog dstCl = dstRepo.getRevlog(new File(dstRepo.getStoreDir(), "00changelog.i"), new File(dstRepo.getStoreDir(), "00changelog.d"));
            assertEquals(1, dstCl.getRevisionCount());
        }
    }

    // -- UnbundleCommand.call() line 38-39: bundleFile == null || !bundleFile.exists() --------

    @Test
    public void callThrowsWhenBundleFileIsNull() {
        HgRepository repo = null;
        UnbundleCommand cmd = new UnbundleCommand(repo);
        IllegalStateException ex = assertThrows(IllegalStateException.class, cmd::call);
        assertTrue(ex.getMessage().contains("Bundle file must exist"));
    }

    @Test
    public void callThrowsWhenBundleFileDoesNotExist(@TempDir Path tempDir) {
        File missing = tempDir.resolve("does-not-exist.hg").toFile();
        UnbundleCommand cmd = new UnbundleCommand(null).setBundleFile(missing);
        IllegalStateException ex = assertThrows(IllegalStateException.class, cmd::call);
        assertTrue(ex.getMessage().contains("Bundle file must exist"));
    }

    // -- HG10 bundle1 container, GZ compression (lines 56-59) ----------------------------------

    @Test
    public void callAppliesHg10GzCompressedChangegroup(@TempDir Path tempDir) throws Exception {
        byte[] changegroupBytes = realChangegroupBytes(tempDir, "gz_src");

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(compressed)) {
            dos.write(changegroupBytes);
        }

        byte[] header = "HG10GZ".getBytes(StandardCharsets.US_ASCII);
        byte[] bundleBytes = new byte[header.length + compressed.size()];
        System.arraycopy(header, 0, bundleBytes, 0, header.length);
        System.arraycopy(compressed.toByteArray(), 0, bundleBytes, header.length, compressed.size());

        assertAppliesSingleRevision(tempDir, "gz_dst", bundleBytes);
    }

    // -- HG10 bundle1 container, BZ compression (lines 60-69) ----------------------------------

    @Test
    public void callAppliesHg10BzCompressedChangegroup(@TempDir Path tempDir) throws Exception {
        byte[] changegroupBytes = realChangegroupBytes(tempDir, "bz_src");

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (BZip2CompressorOutputStream bzos = new BZip2CompressorOutputStream(compressed)) {
            bzos.write(changegroupBytes);
        }
        // BZip2CompressorOutputStream writes the standard "BZh..." bzip2 magic; UnbundleCommand's
        // decoder re-adds a literal "BZ" prefix before decompressing, so the stored payload must
        // have that leading "BZ" stripped (leaving just "h...") to round-trip correctly.
        byte[] compressedBytes = compressed.toByteArray();
        assertEquals('B', compressedBytes[0]);
        assertEquals('Z', compressedBytes[1]);
        byte[] storedPayload = new byte[compressedBytes.length - 2];
        System.arraycopy(compressedBytes, 2, storedPayload, 0, storedPayload.length);

        byte[] header = "HG10BZ".getBytes(StandardCharsets.US_ASCII);
        byte[] bundleBytes = new byte[header.length + storedPayload.length];
        System.arraycopy(header, 0, bundleBytes, 0, header.length);
        System.arraycopy(storedPayload, 0, bundleBytes, header.length, storedPayload.length);

        assertAppliesSingleRevision(tempDir, "bz_dst", bundleBytes);
    }

    // -- HG10 bundle1 container, unsupported compression code (line 70-71 -- the fully-uncovered
    //    HgCorruptDataException branch) -------------------------------------------------------

    @Test
    public void callThrowsForUnsupportedBundle1CompressionCode(@TempDir Path tempDir) throws Exception {
        byte[] header = "HG10XX".getBytes(StandardCharsets.US_ASCII);
        byte[] bundleBytes = new byte[header.length + 4];
        System.arraycopy(header, 0, bundleBytes, 0, header.length);

        File bundleFile = tempDir.resolve("unsupported-compression.hg").toFile();
        Files.write(bundleFile.toPath(), bundleBytes);

        File dstDir = tempDir.resolve("unsupported_compression_repo").toFile();
        HgRepository dstRepo = Hg.init().setDirectory(dstDir).call();
        try (Hg dstHg = Hg.wrap(dstRepo)) {
            UnbundleCommand cmd = dstHg.unbundle().setBundleFile(bundleFile);
            HgCorruptDataException ex = assertThrows(HgCorruptDataException.class, cmd::call);
            assertEquals("Unsupported bundle1 compression format: HG10XX", ex.getMessage());
        }
    }

    // -- HG20 bundle2 container success path (line 45-49) ---------------------------------------

    @Test
    public void callAppliesHg20Bundle2Changegroup(@TempDir Path tempDir) throws Exception {
        byte[] changegroupBytes = realChangegroupBytes(tempDir, "bundle2_src");
        byte[] bundleBytes = wrapInBundle2(changegroupBytes);

        assertAppliesSingleRevision(tempDir, "bundle2_dst", bundleBytes);
    }

    /** Minimal HG20 bundle2 stream wrapping {@code changegroupBytes} in a single CHANGEGROUP part. */
    private static byte[] wrapInBundle2(byte[] changegroupBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);

        dos.write("HG20".getBytes(StandardCharsets.US_ASCII));
        dos.writeInt(0); // empty stream params

        String partName = "CHANGEGROUP";
        byte[] nameBytes = partName.getBytes(StandardCharsets.US_ASCII);
        String key = "version";
        String value = "01";
        ByteArrayOutputStream partHeader = new ByteArrayOutputStream();
        partHeader.write(nameBytes.length);
        partHeader.write(nameBytes);
        partHeader.write(new byte[]{0, 0, 0, 1}); // part id
        partHeader.write(1); // mandatory param count
        partHeader.write(0); // advisory param count
        partHeader.write(key.getBytes(StandardCharsets.US_ASCII).length);
        partHeader.write(value.getBytes(StandardCharsets.US_ASCII).length);
        partHeader.write(key.getBytes(StandardCharsets.US_ASCII));
        partHeader.write(value.getBytes(StandardCharsets.US_ASCII));
        byte[] headerBlock = partHeader.toByteArray();

        dos.writeInt(headerBlock.length);
        dos.write(headerBlock);
        dos.writeInt(changegroupBytes.length);
        dos.write(changegroupBytes);
        dos.writeInt(0); // end of part payload
        dos.writeInt(0); // end of bundle2

        return out.toByteArray();
    }

    // -- Unrecognized header fallback (line 74-75) -----------------------------------------------

    @Test
    public void callThrowsForCompletelyUnrecognizedHeader(@TempDir Path tempDir) throws Exception {
        // "H","G" match but the 3rd/4th bytes don't match either HG20's "20" or HG10's "10",
        // and the buffer is long enough to fail both length-gated container checks outright.
        byte[] bundleBytes = "HG99SOMEJUNKDATA".getBytes(StandardCharsets.US_ASCII);

        File bundleFile = tempDir.resolve("unrecognized-header.hg").toFile();
        Files.write(bundleFile.toPath(), bundleBytes);

        File dstDir = tempDir.resolve("unrecognized_header_repo").toFile();
        HgRepository dstRepo = Hg.init().setDirectory(dstDir).call();
        try (Hg dstHg = Hg.wrap(dstRepo)) {
            UnbundleCommand cmd = dstHg.unbundle().setBundleFile(bundleFile);
            HgCorruptDataException ex = assertThrows(HgCorruptDataException.class, cmd::call);
            assertTrue(ex.getMessage().startsWith("Unrecognized bundle file header:"));
        }
    }

    @Test
    public void callThrowsForHeaderNotStartingWithH(@TempDir Path tempDir) throws Exception {
        // Long enough to pass both the HG20 ">=4" and HG10 ">=6" length gates, but the very
        // first byte isn't 'H', so both container checks fail on their bundleBytes[0]=='H'
        // condition (as opposed to every other test here, which fails deeper in the chain).
        byte[] bundleBytes = "XG10UNjunkjunk".getBytes(StandardCharsets.US_ASCII);

        File bundleFile = tempDir.resolve("bad-first-byte-header.hg").toFile();
        Files.write(bundleFile.toPath(), bundleBytes);

        File dstDir = tempDir.resolve("bad_first_byte_header_repo").toFile();
        HgRepository dstRepo = Hg.init().setDirectory(dstDir).call();
        try (Hg dstHg = Hg.wrap(dstRepo)) {
            UnbundleCommand cmd = dstHg.unbundle().setBundleFile(bundleFile);
            HgCorruptDataException ex = assertThrows(HgCorruptDataException.class, cmd::call);
            assertTrue(ex.getMessage().startsWith("Unrecognized bundle file header:"));
        }
    }

    @Test
    public void callThrowsForHg20LikeHeaderWithWrongFourthByte(@TempDir Path tempDir) throws Exception {
        // "HG2" matches the first three HG20 bytes, but the fourth byte is '1' instead of '0',
        // so the HG20 check fails specifically on its bundleBytes[3]=='0' condition rather than
        // short-circuiting earlier (as the HG10-header tests elsewhere in this class all do).
        byte[] bundleBytes = "HG21".getBytes(StandardCharsets.US_ASCII);

        File bundleFile = tempDir.resolve("hg2x-wrong-fourth-byte.hg").toFile();
        Files.write(bundleFile.toPath(), bundleBytes);

        File dstDir = tempDir.resolve("hg2x_wrong_fourth_byte_repo").toFile();
        HgRepository dstRepo = Hg.init().setDirectory(dstDir).call();
        try (Hg dstHg = Hg.wrap(dstRepo)) {
            UnbundleCommand cmd = dstHg.unbundle().setBundleFile(bundleFile);
            HgCorruptDataException ex = assertThrows(HgCorruptDataException.class, cmd::call);
            assertTrue(ex.getMessage().startsWith("Unrecognized bundle file header:"));
        }
    }

    @Test
    public void callThrowsForHeaderWithWrongSecondByte(@TempDir Path tempDir) throws Exception {
        // First byte is 'H' (matching both HG20 and HG10 prefixes) but the second byte isn't
        // 'G', so both container checks fail specifically on their bundleBytes[1]=='G' condition
        // -- a branch none of the other malformed-header cases in this class exercise, since
        // they all either fail earlier (bad first byte / too short) or later (bad 3rd/4th byte).
        byte[] bundleBytes = "HX10UNjunkjunk".getBytes(StandardCharsets.US_ASCII);

        File bundleFile = tempDir.resolve("bad-second-byte-header.hg").toFile();
        Files.write(bundleFile.toPath(), bundleBytes);

        File dstDir = tempDir.resolve("bad_second_byte_header_repo").toFile();
        HgRepository dstRepo = Hg.init().setDirectory(dstDir).call();
        try (Hg dstHg = Hg.wrap(dstRepo)) {
            UnbundleCommand cmd = dstHg.unbundle().setBundleFile(bundleFile);
            HgCorruptDataException ex = assertThrows(HgCorruptDataException.class, cmd::call);
            assertTrue(ex.getMessage().startsWith("Unrecognized bundle file header:"));
        }
    }

    @Test
    public void callThrowsForHg10LikeHeaderWithWrongFourthByte(@TempDir Path tempDir) throws Exception {
        // "HG1" matches the first three HG10 bytes (and also fails the HG20 check on its 3rd
        // byte, same as every other HG10-prefixed header here), but the fourth byte is '1'
        // instead of '0', so the HG10 check fails specifically on its bundleBytes[3]=='0'
        // condition rather than short-circuiting on byte[2] like the other HG10 variants do.
        byte[] bundleBytes = "HG11XX".getBytes(StandardCharsets.US_ASCII);

        File bundleFile = tempDir.resolve("hg1x-wrong-fourth-byte.hg").toFile();
        Files.write(bundleFile.toPath(), bundleBytes);

        File dstDir = tempDir.resolve("hg1x_wrong_fourth_byte_repo").toFile();
        HgRepository dstRepo = Hg.init().setDirectory(dstDir).call();
        try (Hg dstHg = Hg.wrap(dstRepo)) {
            UnbundleCommand cmd = dstHg.unbundle().setBundleFile(bundleFile);
            HgCorruptDataException ex = assertThrows(HgCorruptDataException.class, cmd::call);
            assertTrue(ex.getMessage().startsWith("Unrecognized bundle file header:"));
        }
    }

    @Test
    public void callThrowsForTooShortHeaderThatFailsBothLengthChecks(@TempDir Path tempDir) throws Exception {
        // Only 3 bytes: fails the ">= 4" length gate for HG20 and the ">= 6" length gate for
        // HG10, so both container checks short-circuit on their very first condition.
        byte[] bundleBytes = "abc".getBytes(StandardCharsets.US_ASCII);

        File bundleFile = tempDir.resolve("too-short-header.hg").toFile();
        Files.write(bundleFile.toPath(), bundleBytes);

        File dstDir = tempDir.resolve("too_short_header_repo").toFile();
        HgRepository dstRepo = Hg.init().setDirectory(dstDir).call();
        try (Hg dstHg = Hg.wrap(dstRepo)) {
            UnbundleCommand cmd = dstHg.unbundle().setBundleFile(bundleFile);
            HgCorruptDataException ex = assertThrows(HgCorruptDataException.class, cmd::call);
            assertTrue(ex.getMessage().startsWith("Unrecognized bundle file header:"));
        }
    }
}
