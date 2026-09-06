package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixBackoutCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to
 * {@link TagsCommand}, {@link PathsCommand} and {@link RootCommand} across the native 6-combo grid
 * (changelog family x treemanifest, dirstate fixed at v1 -- v2 needs Docker, see
 * {@code RequirementMatrixTagsDockerRoundTripTest}). These three are grouped into one trio,
 * matching the precedent set by wave 4's combined branch/branches/phase trios -- all three are
 * simple, self-contained metadata listings unrelated to each other functionally but identical in
 * shape (read-only, no working-copy state), so a single scenario covers all three cheaply.
 *
 * <p>{@link RootCommand} and {@link PathsCommand} do not actually depend on any of the 4
 * requirement axes at all (root is just the working directory path, paths just reads
 * {@code .hg/hgrc}) -- they are included here anyway for completeness/exhaustiveness, per the
 * campaign's mandate to cover every assigned command across the full matrix rather than assume
 * a command is unaffected.
 *
 * <p>No {@code HelperMain} subprocess is used, for the same reason as
 * {@link RequirementMatrixHeadsCoreRoundTripTest}: all three commands are pure readers over a
 * repository built exclusively via the real {@code hg} CLI.
 */
@Tag("interop")
public class RequirementMatrixTagsCoreRoundTripTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    record RequirementCombo(String label, List<String> initConfigArgs) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final List<String> CL_V1 = List.of();
    private static final List<String> CL_V2 = List.of("format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data");
    private static final List<String> CL_V2_SIDEDATA = List.of(
            "format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data",
            "format.exp-use-copies-side-data-changeset=yes");

    private static final List<String> TREEMANIFEST_OFF = List.of();
    private static final List<String> TREEMANIFEST_ON = List.of("experimental.treemanifest=1");

    static Stream<RequirementCombo> combos() {
        List<RequirementCombo> out = new ArrayList<>();
        for (var cl : List.of(Map.entry("cl1", CL_V1), Map.entry("cl2", CL_V2), Map.entry("cl2+sidedata", CL_V2_SIDEDATA))) {
            for (var tm : List.of(Map.entry("flatmanifest", TREEMANIFEST_OFF), Map.entry("treemanifest", TREEMANIFEST_ON))) {
                List<String> args = new ArrayList<>();
                args.addAll(cl.getValue());
                args.addAll(tm.getValue());
                out.add(new RequirementCombo(cl.getKey() + "/" + tm.getKey(), args));
            }
        }
        return out.stream();
    }

    private static File initWithCombo(Path tempDir, RequirementCombo combo, String suffix) throws Exception {
        File repoDir = tempDir.resolve("repo-" + combo.label().replace("/", "-").replace("+", "_") + "-" + suffix).toFile();
        repoDir.mkdirs();
        List<String> args = new ArrayList<>();
        args.add("init");
        for (String c : combo.initConfigArgs()) {
            args.add("--config");
            args.add(c);
        }
        HgTestUtils.hg(repoDir, args.toArray(new String[0]));
        return repoDir;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void tagsPathsRootMatchRealHgAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "tags");

        // c0: root.
        Files.writeString(repoDir.toPath().resolve("a.txt"), "a0\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");
        String c0Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        // c1: child of c0.
        Files.writeString(repoDir.toPath().resolve("a.txt"), "a1\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1");
        String c1Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        // Global tag "v1" on c1 (creates a tag commit c1tag on top).
        HgTestUtils.hg(repoDir, "tag", "-u", "dev", "-r", c1Hex, "v1");

        // Local tag "v2" on c0 (never committed, .hg/localtags only).
        HgTestUtils.hg(repoDir, "tag", "-u", "dev", "--local", "-r", c0Hex, "v2");

        // --- PathsCommand (empty case): checked with its own HgRepository handle constructed
        // BEFORE the [paths] section is appended below -- HgRepository loads .hg/hgrc once, at
        // construction time (like the changelog revlog cache, it is not a live view), so the
        // "populated" handle used further down must be constructed strictly after that append.
        HgRepository repoBeforePaths = new HgRepository(repoDir);
        String realPathsEmpty = HgTestUtils.hg(repoDir, "paths");
        assertEquals("", realPathsEmpty, "combo " + combo);
        assertEquals(Map.of(), new PathsCommand(repoBeforePaths).call(), "combo " + combo);

        Files.writeString(new File(repoDir, ".hg/hgrc").toPath(),
                "\n[paths]\ndefault = https://example.com/repo\n"
                        + "default-push = ssh://example.com/repo\n"
                        + "upstream = https://upstream.example.com/repo\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        HgRepository repo = new HgRepository(repoDir);

        // --- TagsCommand: verify against real `hg tags -v` (shows " local" suffix). ---
        String realTagsVerboseOut = HgTestUtils.hg(repoDir, "tags", "-v");
        List<TagsCommand.Tag> hg4jTags = new TagsCommand(repo).call();
        StringBuilder rebuilt = new StringBuilder();
        for (int i = 0; i < hg4jTags.size(); i++) {
            TagsCommand.Tag t = hg4jTags.get(i);
            if (i > 0) {
                rebuilt.append('\n');
            }
            // Mirror real hg's `hg tags -v` column layout precisely enough to compare: name
            // (padded to 35 chars), then "rev:shorthex", then " local" if local.
            String namePart = String.format("%-35s", t.getName());
            rebuilt.append(namePart).append(t.getRev()).append(':').append(NodeIdUtil.toHex(t.getNode()), 0, 12);
            if (t.isLocal()) {
                rebuilt.append(" local");
            }
        }
        assertEquals(realTagsVerboseOut, rebuilt.toString(),
                "hg4j's TagsCommand must reproduce real `hg tags -v` verbatim (name/rev/hex/local flag) for combo "
                        + combo);

        // Sanity on the actual field values (not just the reconstructed text).
        assertEquals(3, hg4jTags.size(), "combo " + combo); // tip, v1, v2
        TagsCommand.Tag tipTag = hg4jTags.stream().filter(t -> t.getName().equals("tip")).findFirst().orElseThrow();
        TagsCommand.Tag v1Tag = hg4jTags.stream().filter(t -> t.getName().equals("v1")).findFirst().orElseThrow();
        TagsCommand.Tag v2Tag = hg4jTags.stream().filter(t -> t.getName().equals("v2")).findFirst().orElseThrow();
        assertFalse(v1Tag.isLocal(), "combo " + combo);
        assertTrue(v2Tag.isLocal(), "combo " + combo);
        assertEquals(c1Hex, NodeIdUtil.toHex(v1Tag.getNode()), "combo " + combo);
        assertEquals(c0Hex, NodeIdUtil.toHex(v2Tag.getNode()), "combo " + combo);
        // tip is the tag-commit created by `hg tag ... v1`, i.e. rev 2.
        assertEquals(2, tipTag.getRev(), "combo " + combo);

        // --- PathsCommand (populated case): the [paths] section was already appended above,
        // strictly before `repo` was constructed. ---
        String realPathsPopulated = HgTestUtils.hg(repoDir, "paths");
        Map<String, String> hg4jPaths = new PathsCommand(repo).call();
        StringBuilder rebuiltPaths = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : hg4jPaths.entrySet()) {
            if (!first) {
                rebuiltPaths.append('\n');
            }
            first = false;
            rebuiltPaths.append(e.getKey()).append(" = ").append(e.getValue());
        }
        assertEquals(realPathsPopulated, rebuiltPaths.toString(),
                "hg4j's PathsCommand must reproduce real `hg paths` verbatim (alphabetical name = url lines) for combo "
                        + combo);
        assertEquals(
                Map.of("default", "https://example.com/repo",
                        "default-push", "ssh://example.com/repo",
                        "upstream", "https://upstream.example.com/repo"),
                hg4jPaths, "combo " + combo);

        // --- RootCommand: verify against real `hg root`. ---
        String realRoot = HgTestUtils.hg(repoDir, "root");
        String hg4jRoot = new RootCommand(repo).call();
        assertEquals(new File(realRoot).getCanonicalPath(), new File(hg4jRoot).getCanonicalPath(),
                "hg4j's RootCommand must match real `hg root` (canonicalized) for combo " + combo);
    }
}
