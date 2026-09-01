package com.github.search5.hg4j.treewalk;

import com.github.search5.hg4j.api.Hg;
import com.github.search5.hg4j.errors.HgRevisionNotFoundException;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targeted coverage for {@link ManifestTreeIterator}: the raw {@code parseManifestContent}
 * text/binary parsing branches, the "no manifest" revision shortcuts, revision-not-found error
 * handling, and out-of-bounds accessor behavior before the first / after the last {@code next()}.
 */
public class ManifestTreeIteratorCoverageTest {

    private File tempRepoDir;
    private HgRepository repository;

    @BeforeEach
    public void setUp() throws Exception {
        tempRepoDir = Files.createTempDirectory("hg4j_mti_cov_").toFile();
        repository = Hg.init().setDirectory(tempRepoDir).call();
        assertNotNull(repository);
    }

    @AfterEach
    public void tearDown() {
        deleteDirRecursively(tempRepoDir);
    }

    private void deleteDirRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteDirRecursively(child);
            }
        }
        file.delete();
    }

    private byte[] commitSingleFile(String path, String content) throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            File f = new File(tempRepoDir, path);
            Files.writeString(f.toPath(), content, StandardCharsets.UTF_8);
            hg.add().addFile(path).call();
            return hg.commit().setAuthor("tester <test@example.com>").setMessage("commit " + path).call();
        }
    }

    // ------------------------------------------------------------------
    // parseManifestContent: pure-function edge cases for line parsing
    // ------------------------------------------------------------------

    @Test
    public void testParseManifestContent_EmptyContentYieldsNoEntries() {
        List<ManifestTreeIterator.Entry> entries = ManifestTreeIterator.parseManifestContent(new byte[0]);
        assertTrue(entries.isEmpty());
    }

    @Test
    public void testParseManifestContent_LineWithoutNulByteIsSkipped() {
        // A line with no NUL separator between path and node id is malformed and must be
        // dropped instead of throwing or fabricating an entry.
        byte[] content = "no-null-separator-here\n".getBytes(StandardCharsets.UTF_8);
        List<ManifestTreeIterator.Entry> entries = ManifestTreeIterator.parseManifestContent(content);
        assertTrue(entries.isEmpty());
    }

    @Test
    public void testParseManifestContent_LastLineWithoutTrailingNewlineIsStillParsed() {
        // A final line that reaches the end of the buffer without a trailing '\n' must still be
        // recognized as a complete entry (the scan hits end-of-array, not a newline byte).
        String hex = "a".repeat(40);
        byte[] content = ("noeol.txt\0" + hex).getBytes(StandardCharsets.UTF_8);
        List<ManifestTreeIterator.Entry> entries = ManifestTreeIterator.parseManifestContent(content);
        assertEquals(1, entries.size());
        assertEquals("noeol.txt", entries.get(0).path);
    }

    @Test
    public void testParseManifestContent_BlankLineBetweenEntriesIsSkipped() throws IOException {
        String hex1 = "0".repeat(40);
        String hex2 = "1".repeat(40);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("a.txt\0".getBytes(StandardCharsets.UTF_8));
        out.write(hex1.getBytes(StandardCharsets.UTF_8));
        out.write('\n');
        out.write('\n'); // blank line: end == start, must be skipped without a crash
        out.write("b.txt\0".getBytes(StandardCharsets.UTF_8));
        out.write(hex2.getBytes(StandardCharsets.UTF_8));
        out.write('\n');

        List<ManifestTreeIterator.Entry> entries = ManifestTreeIterator.parseManifestContent(out.toByteArray());
        assertEquals(2, entries.size());
        assertEquals("a.txt", entries.get(0).path);
        assertEquals("b.txt", entries.get(1).path);
    }

    @Test
    public void testParseManifestContent_ValueTooShortForEitherFormatIsSkipped() throws IOException {
        // Only 5 bytes follow the NUL byte: too short for a 40-char hex node id and too short
        // for a raw 20-byte binary node id either, so the line must be dropped.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("short.txt\0abcde".getBytes(StandardCharsets.UTF_8));
        out.write('\n');
        List<ManifestTreeIterator.Entry> entries = ManifestTreeIterator.parseManifestContent(out.toByteArray());
        assertTrue(entries.isEmpty());
    }

    @Test
    public void testParseManifestContent_NonHexFortyByteValueFallsBackToRawBinaryNodeId() throws IOException {
        // A 40-byte value that is NOT valid hex text (first bytes are raw control bytes) must
        // fall back to treating the first 20 bytes as a raw binary node id, with the remaining
        // bytes treated as the flag string.
        byte[] rawHash = new byte[20];
        for (int i = 0; i < 20; i++) {
            rawHash[i] = (byte) (i + 20); // avoid 0x0A ('\n') so the line isn't split early
        }
        byte[] trailer = new byte[20];
        Arrays.fill(trailer, (byte) 'z'); // not "x"/"l" -> no flags recognized

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("bin.txt\0".getBytes(StandardCharsets.UTF_8));
        out.write(rawHash);
        out.write(trailer);
        out.write('\n');

        List<ManifestTreeIterator.Entry> entries = ManifestTreeIterator.parseManifestContent(out.toByteArray());
        assertEquals(1, entries.size());
        ManifestTreeIterator.Entry entry = entries.get(0);
        assertEquals("bin.txt", entry.path);
        assertArrayEquals(rawHash, entry.nodeId);
        assertFalse(entry.executable);
        assertFalse(entry.symlink);
    }

    @Test
    public void testParseManifestContent_RawBinaryNodeIdExactLengthNoFlags() throws IOException {
        // Exactly 20 raw bytes after the NUL byte, no trailing flag byte at all: flagStart == end.
        byte[] rawHash = new byte[20];
        Arrays.fill(rawHash, (byte) 0x00); // all-zero raw node id (not valid hex text either way)

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("plain.txt\0".getBytes(StandardCharsets.UTF_8));
        out.write(rawHash);
        out.write('\n');

        List<ManifestTreeIterator.Entry> entries = ManifestTreeIterator.parseManifestContent(out.toByteArray());
        assertEquals(1, entries.size());
        ManifestTreeIterator.Entry entry = entries.get(0);
        assertEquals("plain.txt", entry.path);
        assertArrayEquals(rawHash, entry.nodeId);
        assertFalse(entry.executable);
        assertFalse(entry.symlink);
    }

    @Test
    public void testParseManifestContent_RawBinaryNodeIdWithExecutableAndSymlinkFlags() throws IOException {
        byte[] rawHash = new byte[20];
        for (int i = 0; i < 20; i++) {
            rawHash[i] = (byte) (0x80 + i); // high bytes: never valid hex text
        }

        ByteArrayOutputStream execOut = new ByteArrayOutputStream();
        execOut.write("exe.txt\0".getBytes(StandardCharsets.UTF_8));
        execOut.write(rawHash);
        execOut.write('x');
        execOut.write('\n');

        ByteArrayOutputStream linkOut = new ByteArrayOutputStream();
        linkOut.write("link.txt\0".getBytes(StandardCharsets.UTF_8));
        linkOut.write(rawHash);
        linkOut.write('l');
        linkOut.write('\n');

        List<ManifestTreeIterator.Entry> execEntries = ManifestTreeIterator.parseManifestContent(execOut.toByteArray());
        assertEquals(1, execEntries.size());
        assertTrue(execEntries.get(0).executable);
        assertFalse(execEntries.get(0).symlink);

        List<ManifestTreeIterator.Entry> linkEntries = ManifestTreeIterator.parseManifestContent(linkOut.toByteArray());
        assertEquals(1, linkEntries.size());
        assertFalse(linkEntries.get(0).executable);
        assertTrue(linkEntries.get(0).symlink);
    }

    // ------------------------------------------------------------------
    // loadEntries(): "no manifest" revision shortcuts
    // ------------------------------------------------------------------

    @Test
    public void testReset_NullRevisionYieldsNoEntries() throws IOException {
        ManifestTreeIterator it = new ManifestTreeIterator(repository, (String) null);
        it.reset();
        assertFalse(it.next());
    }

    @Test
    public void testReset_EmptyStringRevisionYieldsNoEntries() throws IOException {
        ManifestTreeIterator it = new ManifestTreeIterator(repository, "");
        it.reset();
        assertFalse(it.next());
    }

    @Test
    public void testReset_MinusOneRevisionYieldsNoEntries() throws IOException {
        ManifestTreeIterator it = new ManifestTreeIterator(repository, "-1");
        it.reset();
        assertFalse(it.next());
    }

    @Test
    public void testReset_LiteralNullStringCaseInsensitiveYieldsNoEntries() throws IOException {
        ManifestTreeIterator it = new ManifestTreeIterator(repository, "NuLL");
        it.reset();
        assertFalse(it.next());
    }

    @Test
    public void testReset_UnknownRevisionThrowsRevisionNotFound() throws Exception {
        commitSingleFile("a.txt", "content");

        // "zzzzzzzz" is neither a valid revision number nor a valid hex prefix of any real
        // node id in this repo, so NodeIdUtil.resolveRevision(...) must return null.
        ManifestTreeIterator it = new ManifestTreeIterator(repository, "zzzzzzzz");
        assertThrows(HgRevisionNotFoundException.class, it::reset);
    }

    // ------------------------------------------------------------------
    // Direct manifest-node constructor
    // ------------------------------------------------------------------

    @Test
    public void testConstructor_DirectManifestNodeMatchesRevisionBasedLookup() throws Exception {
        commitSingleFile("a.txt", "content");

        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");
        Revlog manifestRevlog = repository.getRevlog(mfIdx, mfDat);
        byte[] manifestNode = manifestRevlog.getIndexRecord(0).getNodeId();

        ManifestTreeIterator direct = new ManifestTreeIterator(repository, manifestNode);
        direct.reset();
        assertTrue(direct.next());
        assertEquals("a.txt", direct.getEntryPath());
        assertFalse(direct.next());
    }

    @Test
    public void testReset_HexEntryWithExecutableFlagIsParsedAsExecutable() throws Exception {
        // Real manifest text entries carry an extra flag byte ('x'/'l') appended right after
        // the 40 hex chars (valLen > 40) -- exercise that path with a real hg-tracked
        // executable file, not just the raw-binary fallback covered above.
        try (Hg hg = Hg.open(tempRepoDir)) {
            File f = new File(tempRepoDir, "run.sh");
            Files.writeString(f.toPath(), "#!/bin/sh\necho hi\n", StandardCharsets.UTF_8);
            assertTrue(f.setExecutable(true));
            hg.add().addFile("run.sh").call();
            hg.commit().setAuthor("tester <test@example.com>").setMessage("exec commit").call();
        }

        ManifestTreeIterator it = new ManifestTreeIterator(repository, "0");
        it.reset();
        assertTrue(it.next());
        assertEquals("run.sh", it.getEntryPath());
        assertTrue(it.isExecutable());
        assertFalse(it.isSymlink());
    }

    // ------------------------------------------------------------------
    // Accessor behavior before first next() / after exhaustion, and getEntryState()
    // ------------------------------------------------------------------

    @Test
    public void testAccessors_OutOfRangeBeforeFirstNextAndAfterExhaustion() throws Exception {
        commitSingleFile("a.txt", "content");

        ManifestTreeIterator it = new ManifestTreeIterator(repository, "0");
        it.reset();

        // Before the first next(), index == -1: every accessor must return its "no entry" default.
        assertNull(it.getEntryPath());
        assertNull(it.getEntryNodeId());
        assertFalse(it.isExecutable());
        assertFalse(it.isSymlink());
        assertEquals('n', it.getEntryState());

        assertTrue(it.next());
        assertEquals("a.txt", it.getEntryPath());
        assertNotNull(it.getEntryNodeId());

        // Exhaust the iterator: index becomes entries.size(), out of range again.
        assertFalse(it.next());
        assertNull(it.getEntryPath());
        assertNull(it.getEntryNodeId());
        assertFalse(it.isExecutable());
        assertFalse(it.isSymlink());

        // Calling next() again once already finished must keep reporting false, not throw.
        assertFalse(it.next());
    }
}
