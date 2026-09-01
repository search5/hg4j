package com.github.search5.hg4j.transport.wireprotov1;

import com.github.search5.hg4j.api.AddCommand;
import com.github.search5.hg4j.api.CommitCommand;
import com.github.search5.hg4j.api.Hg;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-gap-focused tests for {@link Wire1Commands}, added alongside {@link Wire1CommandsTest}
 * to exercise branches/lines the original test file (and other suites indirectly touching this
 * class through {@code HgHttpWireServer}/interop tests) leave unexercised: {@code changegroup}/
 * {@code changegroupsubset} (previously untested at all), {@code between}/{@code known}'s
 * blank-argument and empty-repository short-circuits, {@code branchmap}'s branch-name fallback,
 * a failing {@code pushkey}, {@code batch}'s argument-shape edge cases, and {@code getbundle}'s
 * {@code bundlecaps} argument.
 */
public class Wire1CommandsCoverageTest {

    private static String bytes(Wire1Response r) {
        return new String(r.getPayload(), StandardCharsets.UTF_8);
    }

    // ==================== changegroup / changegroupsubset ====================

    @Test
    public void changegroupOnAnEmptyRepositoryReturnsAnEmptyStream(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Wire1Response r = Wire1Commands.changegroup(repo, new LinkedHashMap<>());
        assertEquals(Wire1Response.Kind.STREAM, r.getKind());
        assertEquals(0, r.getPayload().length,
                "An empty repository's changegroup is empty bytes, too short to carry the HG10 on-disk prefix");
    }

    @Test
    public void dispatchRoutesChangegroupAndChangegroupsubsetToTheirHandlers(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "hello");
        new AddCommand(repo).call();
        byte[] commit = new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();
        String hex = NodeIdUtil.toHex(commit);

        Map<String, String> cgArgs = new LinkedHashMap<>();
        cgArgs.put("roots", "");
        Wire1Response cg = Wire1Commands.dispatch(repo, "changegroup", cgArgs);
        assertEquals(Wire1Response.Kind.STREAM, cg.getKind());
        assertTrue(cg.getPayload().length > 0, "A one-commit repo's changegroup must carry real bundle bytes");

        Map<String, String> subsetArgs = new LinkedHashMap<>();
        subsetArgs.put("bases", "");
        subsetArgs.put("heads", hex);
        Wire1Response subset = Wire1Commands.dispatch(repo, "changegroupsubset", subsetArgs);
        assertEquals(Wire1Response.Kind.STREAM, subset.getKind());
        assertTrue(subset.getPayload().length > 0, "changegroupsubset must carry real bundle bytes too");
    }

    @Test
    public void getbundleAcceptsABundlecapsArgumentWithoutBreaking(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "hello");
        new AddCommand(repo).call();
        byte[] commit = new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();

        // Real hg's HTTP client always sends bundlecaps; HgLocalClient.getBundle() doesn't act on
        // them today, but Wire1Commands.getbundle() must still parse the argument without failing.
        Map<String, String> args = new LinkedHashMap<>();
        args.put("common", "");
        args.put("heads", NodeIdUtil.toHex(commit));
        args.put("bundlecaps", "HG20,bundle2=bookmarks%3Dyes");
        Wire1Response r = Wire1Commands.getbundle(repo, args);
        assertEquals(Wire1Response.Kind.STREAM, r.getKind());
        assertTrue(r.getPayload().length > 0);
    }

    // ==================== between ====================

    @Test
    public void betweenWithNoPairsArgumentReturnsEmptyBytes() {
        Wire1Response r = Wire1Commands.between(new LinkedHashMap<>());
        assertEquals(0, r.getPayload().length);
    }

    @Test
    public void betweenReturnsOneNewlinePerRequestedPair() {
        // Real wire format (mercurial/wireprotov1server.py's between()): space-separated
        // "hash1-hash2" pairs; this hg4j implementation only counts the space-separated tokens.
        Map<String, String> args = new LinkedHashMap<>();
        args.put("pairs", "0".repeat(40) + "-" + "0".repeat(40) + " " + "1".repeat(40) + "-" + "1".repeat(40));
        Wire1Response r = Wire1Commands.between(args);
        assertEquals("\n\n", bytes(r));
    }

    // ==================== known ====================

    @Test
    public void knownWithNoNodesArgumentReturnsEmptyString(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Wire1Response r = Wire1Commands.known(repo, new LinkedHashMap<>());
        assertEquals("", bytes(r));
    }

    @Test
    public void knownOnAnEmptyRepositoryReportsUnknownWithoutConsultingTheChangelog(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Map<String, String> args = new LinkedHashMap<>();
        args.put("nodes", "a".repeat(40));
        assertEquals("0", bytes(Wire1Commands.known(repo, args)));
    }

    // ==================== lookup ====================

    @Test
    public void lookupReportsTheRealFailureReasonInsteadOfMasqueradingEveryFailureAsUnknown(@TempDir Path tempDir) throws Exception {
        // Real hg's lookup() (mercurial/wireprotov1server.py) reports whatever exception message
        // repo.lookup() actually raised -- "unknown revision" for a genuinely missing key, but a
        // distinct "ambiguous identifier"-style message for an ambiguous short hex prefix
        // (mercurial/revlog.py raises error.AmbiguousPrefixLookupError, a *different* error type
        // than the plain LookupError used for "not found"). Verify hg4j surfaces that same
        // distinction rather than hardcoding "unknown revision" for every failure.
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "seed");
        new AddCommand(repo).call();

        List<String> hexes = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            Files.writeString(f.toPath(), "content-" + i);
            byte[] commit = new CommitCommand(repo).setMessage("c" + i).setAuthor("dev").call();
            hexes.add(NodeIdUtil.toHex(commit));
        }

        // Find a real 2-hex-char prefix shared by at least two of the actual commits -- and skip
        // any prefix made only of digits, since resolveRevision() tries Integer.parseInt() first
        // and a small enough numeric value would resolve as a revision index rather than a hex
        // prefix (a purely-numeric-but-out-of-range prefix would still fall through fine, but
        // it's simplest to just require a non-digit character to sidestep the ambiguity).
        Map<String, List<String>> byPrefix = new HashMap<>();
        for (String hex : hexes) {
            byPrefix.computeIfAbsent(hex.substring(0, 2), k -> new ArrayList<>()).add(hex);
        }
        String ambiguousPrefix = null;
        for (Map.Entry<String, List<String>> e : byPrefix.entrySet()) {
            if (e.getValue().size() >= 2 && !e.getKey().matches("[0-9]{2}")) {
                ambiguousPrefix = e.getKey();
                break;
            }
        }
        assertNotNull(ambiguousPrefix,
                "Expected at least one colliding non-numeric 2-hex-char prefix among " + hexes.size() + " real commit hashes");

        Map<String, String> args = new LinkedHashMap<>();
        args.put("key", ambiguousPrefix);
        String result = bytes(Wire1Commands.lookup(repo, args));
        assertTrue(result.startsWith("0 "), "An ambiguous revision must still report failure: " + result);
        assertTrue(result.toLowerCase().contains("ambig"),
                "Real hg surfaces the actual lookup failure reason (an ambiguous-identifier message) "
                        + "rather than masquerading every failure as 'unknown revision': " + result);
    }

    // ==================== pushkey ====================

    @Test
    public void pushkeyFailsWhenTheOldValueDoesNotMatchTheCurrentOne(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "hello");
        new AddCommand(repo).call();
        byte[] commit = new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();

        Map<String, String> args = new LinkedHashMap<>();
        args.put("namespace", "bookmarks");
        args.put("key", "mybook");
        args.put("old", "f".repeat(40)); // mismatched: the bookmark doesn't exist yet, so its current value is ""
        args.put("new", NodeIdUtil.toHex(commit));
        assertEquals("0\n", bytes(Wire1Commands.pushkey(repo, args)));
    }

    // ==================== branchmap ====================

    @Test
    public void branchmapOnAnEmptyRepositoryReturnsEmptyString(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        assertEquals("", bytes(Wire1Commands.branchmap(repo)));
    }

    @Test
    public void branchmapDefaultsToDefaultBranchWhenTheBranchFileIsBlank(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "hello");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();

        // HgRepository.getBranch() trims the .hg/branch file's content; a whitespace-only file
        // trims down to "", which must still fall back to "default" the same way a missing file does.
        Files.writeString(new File(repo.getHgDir(), "branch").toPath(), "   \n");

        assertTrue(bytes(Wire1Commands.branchmap(repo)).startsWith("default "));
    }

    // ==================== batch ====================

    @Test
    public void batchWithNoCmdsArgumentReturnsEmptyString(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Wire1Response r = Wire1Commands.batch(repo, new LinkedHashMap<>());
        assertEquals("", bytes(r));
    }

    @Test
    public void batchHandlesASubcommandWithNoTrailingSpaceOrArguments(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "hello");
        new AddCommand(repo).call();
        byte[] commit = new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();

        Map<String, String> args = new LinkedHashMap<>();
        args.put("cmds", "heads"); // no trailing space at all -- exercises the sp == -1 branch
        Wire1Response r = Wire1Commands.batch(repo, args);
        assertEquals(NodeIdUtil.toHex(commit) + "\n", bytes(r));
    }

    @Test
    public void batchSkipsEmptyArgumentEntriesProducedByADoubleComma(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "hello");
        new AddCommand(repo).call();
        byte[] commit = new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();
        String hex = NodeIdUtil.toHex(commit);

        // A *trailing* comma wouldn't actually exercise this: String.split(",") with the default
        // limit silently drops trailing empty tokens, so "nodes=<hex>," splits the same as
        // "nodes=<hex>" with no empty entry at all. An empty entry in the *middle* (a doubled
        // comma) is what actually produces one of the split's empty tokens.
        Map<String, String> args = new LinkedHashMap<>();
        args.put("cmds", "known nodes=" + hex + ",,foo=bar");
        Wire1Response r = Wire1Commands.batch(repo, args);
        assertEquals("1", bytes(r));
    }

    @Test
    public void batchOmitsRawPayloadForANonBytesSubcommandResult(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "hello");
        new AddCommand(repo).call();
        byte[] commit = new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();

        // "changegroup" is a STREAM-kind result; batch() only ever forwards BYTES-kind payloads
        // (matching real hg's own server, which asserts this for batched sub-commands), so its
        // segment in the joined response must come back empty rather than containing raw bundle bytes.
        Map<String, String> args = new LinkedHashMap<>();
        args.put("cmds", "changegroup roots=;heads");
        Wire1Response r = Wire1Commands.batch(repo, args);
        String[] parts = bytes(r).split(";", -1);
        assertEquals(2, parts.length);
        assertEquals("", parts[0], "A STREAM-kind sub-response must not leak its raw bytes into the batch response");
        assertEquals(NodeIdUtil.toHex(commit) + "\n", parts[1]);
    }
}
