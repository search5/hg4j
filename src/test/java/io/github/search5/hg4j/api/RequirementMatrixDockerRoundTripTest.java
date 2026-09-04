package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker-only half of the requirement matrix ({@code llm-wiki/decisions/
 * exhaustive-interop-matrix-plan.md} §1): the {@code storage-확장} axis values that
 * abort with "accessing '...' repository without associated fast implementation" on this host's
 * pure-Python {@code hg} 7.2.2 -- {@code persistent-nodemap}, {@code fileindex-v1} (which
 * auto-implies {@code persistent-nodemap}), and {@code general-v2}/{@code exp-revlogv2.2} (which
 * auto-implies both). All three require the Rust extension, available only via the already-built
 * {@code localhost/hg-rust-7.2.4} image ({@code docker/hg-rust-7.2.4/Dockerfile}). Empirically
 * confirmed (2026-09-04, inside that container): {@code fileindex-v1}/{@code general-v2} are
 * mutually exclusive with {@code experimental.treemanifest} (real hg aborts with "cannot create
 * repository with 'format.use-fileindex-v1' and 'experimental.treemanifest' both enabled since
 * they are incompatible with each other") -- so those two only combine with a flat manifest, while
 * {@code persistent-nodemap} alone combines freely with tree manifest too. That gives
 * 2(dirstate) x 3(changelog family) x (2 persistent-nodemap manifest choices + 1 fileindex-v1 +
 * 1 general-v2) = 24 combinations, PLUS 6 more (2026-09-04, scope correction): the sibling
 * native-matrix fork found that {@code format.use-dirstate-v2=yes} ALSO aborts with "without
 * associated fast implementation" on this host's pure-Python {@code hg} -- so
 * {@code dirstate-v2 x changelog(3) x manifest(2)} with NO storage-확장 belongs here too, not in
 * the native file. <b>30 combinations total</b>, matching the plan doc's corrected Docker-only
 * cell count.
 *
 * <p><b>Real bug found (2026-09-04, NOT a test-harness artifact -- 100% reproducible in complete
 * isolation)</b>: writing an hg4j commit into an EXISTING {@code dirstate-v2} repository (one
 * real hg already wrote at least one commit into) orphans the pre-existing tracked file from the
 * dirstate-v2 tree structure -- {@code hg debugstate} (a flat dump) still shows it correctly, but
 * {@code hg status}/{@code hg files}/{@code hg verify} (which walk the tree via
 * {@code children_start}/{@code children_count}) do not, and {@code hg verify} reports
 * "&lt;file&gt; in manifest1, but not marked as tracked in p1" + "dirstate inconsistent with
 * current parent's manifest". Root cause (not yet fixed, see the assertion in
 * {@link #hg4jWritesRealHgReadsAcrossDockerCombo}): {@link
 * io.github.search5.hg4j.dirstate.Dirstate#write} always writes the docket's
 * {@code root_nodes children_start} as {@code 0} -- self-consistent with hg4j's OWN writer (which
 * always lays out the node table at byte 0 of the data file) but a real hg reference capture of
 * the identical 2-file scenario shows real hg's own writer used {@code children_start=16}, not
 * {@code 0} -- suggesting hg4j's "always 0, full rebuild" convention (already known to be valid
 * for the unrelated {@code fileindex-v1} tree_root_pointer per that fixture's own README) does NOT
 * hold the same way for dirstate-v2's multi-root-siblings case when there is more than one
 * top-level entry. This needs focused follow-up against the real {@code dirstatemap.rs}/
 * {@code docket.py} source, not a guess -- flagged, not fixed, in this pass.
 *
 * <p>A single long-lived container is started once for the whole class ({@link BeforeAll}) with a
 * bind mount to a host directory every parameterized case shares (each combo gets its own
 * subdirectory) -- avoids a ~1s container-start tax x 48 test methods. Real hg commands run inside
 * the container via {@code docker exec}; hg4j itself always runs on the host JVM directly against
 * the *same* bind-mounted files (no copying needed) -- this is exactly the same "one side writes,
 * the other reads" pattern every other {@code *RealHgInteropTest} in this suite already uses, just
 * with the "real hg" side's commands routed through {@code docker exec} instead of a bare
 * {@code ProcessBuilder("hg", ...)}.
 *
 * <p><b>Bind-mount gotcha (2026-09-04, found while writing this)</b>: mounting macOS's {@code /tmp}
 * directly (a symlink to {@code /private/tmp}) makes every path underneath it come up as
 * "Permission denied" inside the container on Docker Desktop, even for root -- resolving the
 * symlink first ({@link Path#toRealPath}) before handing the path to {@code -v} fixes it. Also,
 * every {@code docker exec} runs with {@code --user <host uid>:<host gid>} (not the container's
 * default root) so files land owned by the host user on both sides of the mount -- this matters
 * for the "hg4j writes first" direction, where hg4j (running as the host user) must be able to
 * write into a directory it just created itself before real hg ever touches it, and equally for
 * the "real hg writes first" direction, where the JVM must be able to read back what a
 * root-in-container process would otherwise have left root-owned.
 *
 * <p><b>Write-corruption gotcha (2026-09-04, root-caused via extensive isolated reproduction)</b>:
 * running hg4j's own {@code CommitCommand} (host JVM) interleaved with this class's own repeated
 * {@code docker exec}/{@code docker run} child-process spawning, <em>in the same JVM process</em>,
 * non-deterministically corrupts every commit after the first one written that way -- confirmed
 * with fresh containers, fresh host directories, no shared state, immune to retries/delays (ruling
 * out a bind-mount visibility race), and confirmed to vanish completely once the commit runs in a
 * dedicated subprocess instead (see {@link RequirementMatrixCommitHelperMain}). This is a property
 * of THIS test's specific process-spawning pattern, not a data-correctness bug in hg4j itself --
 * every one of dozens of isolated single-JVM reproductions of the exact same commit logic (no
 * concurrent {@code ProcessBuilder} activity) succeeded every time. {@link
 * #hg4jWritesRealHgReadsAcrossDockerCombo} therefore delegates the actual write to
 * {@link RequirementMatrixCommitHelperMain} via a fresh {@code java} subprocess rather than calling
 * {@code CommitCommand} inline.
 */
@Tag("interop")
public class RequirementMatrixDockerRoundTripTest {

    private static final String IMAGE = "localhost/hg-rust-7.2.4";
    private static String containerName;
    private static Path hostWorkDir;
    private static String hostUidGid;
    private static boolean dockerReady = false;

    @BeforeAll
    static void startContainer() throws Exception {
        dockerReady = isDockerAvailable() && isImageAvailable();
        Assumptions.assumeTrue(dockerReady,
                "Docker (or the localhost/hg-rust-7.2.4 image) is not available. Skipping the whole class.");

        hostUidGid = runHost("id", "-u").trim() + ":" + runHost("id", "-g").trim();
        // Resolve symlinks (macOS /tmp -> /private/tmp) BEFORE handing the path to `docker -v`,
        // otherwise every path under it comes up "Permission denied" inside the container.
        hostWorkDir = Files.createTempDirectory("hg4j-docker-matrix").toRealPath();
        containerName = "hg4j-reqmatrix-docker-" + UUID.randomUUID().toString().substring(0, 8);

        runHost("docker", "run", "-d", "--rm", "--name", containerName,
                "-v", hostWorkDir + ":/repo-root", IMAGE, "sleep", "infinity");

        // Wait for the container to actually be able to run `hg` (a few retries covers slow starts).
        Exception last = null;
        for (int i = 0; i < 20; i++) {
            try {
                runContainer("hg", "--version");
                last = null;
                break;
            } catch (Exception e) {
                last = e;
                Thread.sleep(250);
            }
        }
        if (last != null) {
            throw new AssertionError("Container " + containerName + " never became ready to run hg", last);
        }
    }

    @AfterAll
    static void stopContainer() {
        if (containerName != null) {
            try {
                new ProcessBuilder("docker", "stop", containerName).redirectErrorStream(true).start().waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best effort -- --rm already means the container self-deletes on stop/exit
            }
        }
        if (hostWorkDir != null) {
            try {
                deleteRecursively(hostWorkDir.toFile());
            } catch (Exception ignored) {
                // test tmp dir cleanup is best-effort
            }
        }
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        f.delete();
    }

    private static boolean isDockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isImageAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "image", "inspect", IMAGE).redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String runHost(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new AssertionError("host command " + Arrays.toString(cmd) + " failed with exit " + code + ": " + out);
        }
        return out;
    }

    private static String runContainer(String... hgArgsIncludingHg) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker", "exec", "--user", hostUidGid, containerName));
        cmd.addAll(Arrays.asList(hgArgsIncludingHg));
        return runHost(cmd.toArray(new String[0]));
    }

    /** Runs {@code hg <args>} inside the container with cwd set to the given repo's mounted path. */
    private static String dockerHg(String repoRelPath, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker", "exec", "--user", hostUidGid,
                "-w", "/repo-root/" + repoRelPath, containerName, "hg"));
        cmd.addAll(Arrays.asList(args));
        return runHost(cmd.toArray(new String[0])).trim();
    }

    /**
     * Like {@link #dockerHg} but does NOT throw on a non-zero exit -- {@code hg verify} legitimately
     * exits 1 when it finds warnings/errors, and the whole point of calling it here is to inspect
     * that output, not treat a real finding as a test-harness crash.
     */
    private static String dockerHgTolerant(String repoRelPath, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker", "exec", "--user", hostUidGid,
                "-w", "/repo-root/" + repoRelPath, containerName, "hg"));
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        p.waitFor();
        return out.trim();
    }

    /**
     * Runs an hg4j {@code add}+{@code commit} in a brand-new JVM subprocess (see
     * {@link RequirementMatrixCommitHelperMain} for why -- interleaving it with this class's own
     * {@code docker exec} process spawning inline corrupts the write non-deterministically) and
     * returns the committed node's hex.
     */
    private static String commitInSubprocess(Path repoDir, String author, String message) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        String out = runHost(javaBin, "-cp", classpath, RequirementMatrixCommitHelperMain.class.getName(),
                repoDir.toString(), author, message);
        return out.trim();
    }

    /** One point in the Docker-only quarter of the requirement matrix. */
    record RequirementCombo(String label, List<String> initConfigArgs) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final List<String> DIRSTATE_OFF = List.of();
    private static final List<String> DIRSTATE_V2 = List.of("format.use-dirstate-v2=yes");

    private static final List<String> CL_V1 = List.of();
    private static final List<String> CL_V2 = List.of("format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data");
    private static final List<String> CL_V2_SIDEDATA = List.of(
            "format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data",
            "format.exp-use-copies-side-data-changeset=yes");

    private static final List<String> PERSISTENT_NODEMAP = List.of("format.use-persistent-nodemap=true");
    private static final List<String> FILEINDEX_V1 = List.of("format.use-fileindex-v1=yes");
    private static final List<String> GENERAL_V2 = List.of("experimental.revlogv2=enable-unstable-format-and-corrupt-my-data");
    private static final List<String> TREEMANIFEST = List.of("experimental.treemanifest=1");

    static Stream<RequirementCombo> combos() {
        List<RequirementCombo> out = new ArrayList<>();
        List<java.util.Map.Entry<String, List<String>>> dirstates = List.of(
                java.util.Map.entry("dirstate1", DIRSTATE_OFF), java.util.Map.entry("dirstate2", DIRSTATE_V2));
        List<java.util.Map.Entry<String, List<String>>> changelogs = List.of(
                java.util.Map.entry("cl1", CL_V1), java.util.Map.entry("cl2", CL_V2),
                java.util.Map.entry("cl2+sidedata", CL_V2_SIDEDATA));

        // dirstate-v2 with NO storage-확장 (2026-09-04, coordinator-directed scope expansion):
        // format.use-dirstate-v2=yes ALSO aborts with "without associated fast implementation" on
        // this host's pure-Python hg (found by the sibling native-matrix fork) -- so dirstate-v2 x
        // changelog(3) x manifest(2) = 6 more combinations belong here, not in the native file.
        for (var cl : changelogs) {
            for (var tm : List.of(java.util.Map.entry("flatmanifest", List.<String>of()),
                    java.util.Map.entry("treemanifest", TREEMANIFEST))) {
                List<String> args = new ArrayList<>();
                args.addAll(DIRSTATE_V2);
                args.addAll(cl.getValue());
                args.addAll(tm.getValue());
                out.add(new RequirementCombo("dirstate2/" + cl.getKey() + "/" + tm.getKey() + "/none", args));
            }
        }

        for (var dirstate : dirstates) {
            for (var cl : changelogs) {
                // persistent-nodemap: combines with BOTH flat and tree manifest (2 cells).
                for (var tm : List.of(java.util.Map.entry("flatmanifest", List.<String>of()),
                        java.util.Map.entry("treemanifest", TREEMANIFEST))) {
                    List<String> args = new ArrayList<>();
                    args.addAll(dirstate.getValue());
                    args.addAll(cl.getValue());
                    args.addAll(tm.getValue());
                    args.addAll(PERSISTENT_NODEMAP);
                    out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.getKey() + "/" + tm.getKey() + "/pnodemap", args));
                }
                // fileindex-v1: flat manifest ONLY (incompatible with treemanifest, real-hg-confirmed).
                List<String> fileindexArgs = new ArrayList<>();
                fileindexArgs.addAll(dirstate.getValue());
                fileindexArgs.addAll(cl.getValue());
                fileindexArgs.addAll(FILEINDEX_V1);
                out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.getKey() + "/flatmanifest/fileindex-v1", fileindexArgs));
                // general-v2: flat manifest ONLY (same reason, transitively via fileindex-v1).
                List<String> generalV2Args = new ArrayList<>();
                generalV2Args.addAll(dirstate.getValue());
                generalV2Args.addAll(cl.getValue());
                generalV2Args.addAll(GENERAL_V2);
                out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.getKey() + "/flatmanifest/general-v2", generalV2Args));
            }
        }
        return out.stream();
    }

    private static Path initWithCombo(RequirementCombo combo, String suffix) throws Exception {
        String repoRelPath = "repo-" + combo.label().replace("/", "-").replace("+", "_") + "-" + suffix;
        Path hostRepoDir = hostWorkDir.resolve(repoRelPath);
        Files.createDirectories(hostRepoDir);

        List<String> initArgs = new ArrayList<>(List.of("init", "."));
        for (String c : combo.initConfigArgs()) {
            initArgs.add("--config");
            initArgs.add(c);
        }
        dockerHg(repoRelPath, initArgs.toArray(new String[0]));
        return hostRepoDir;
    }

    private static String repoRelPathOf(Path hostRepoDir) {
        return hostWorkDir.relativize(hostRepoDir).toString();
    }

    /**
     * Real hg (inside the container) writes two commits under this Docker-only combination; hg4j
     * (on the host JVM, same bind-mounted files) must read the exact same log/file content back.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void realHgWritesHg4jReadsAcrossDockerCombo(RequirementCombo combo) throws Exception {
        Path hostRepoDir = initWithCombo(combo, "read");
        String repoRelPath = repoRelPathOf(hostRepoDir);

        Files.writeString(hostRepoDir.resolve("a.txt"), "one");
        dockerHg(repoRelPath, "add");
        dockerHg(repoRelPath, "commit", "-u", "dev", "-m", "c0");
        Files.createDirectories(hostRepoDir.resolve("dir"));
        Files.writeString(hostRepoDir.resolve("dir").resolve("b.txt"), "two");
        dockerHg(repoRelPath, "add");
        dockerHg(repoRelPath, "commit", "-u", "dev", "-m", "c1");

        String realTipHex = dockerHg(repoRelPath, "log", "-r", "tip", "--template", "{node}");

        HgRepository repo = new HgRepository(hostRepoDir.toFile());
        List<HgCommit> log = new LogCommand(repo).call();
        assertEquals(2, log.size(), "hg4j must see both real-hg commits for combo " + combo);
        assertEquals(realTipHex, log.get(0).getNodeId().toHex(),
                "hg4j's tip must match real hg's tip hex for combo " + combo);

        byte[] aContent = new CatCommand(repo).setFile("a.txt").setRevision("tip").call();
        assertEquals("one", new String(aContent, StandardCharsets.UTF_8));
        byte[] bContent = new CatCommand(repo).setFile("dir/b.txt").setRevision("tip").call();
        assertEquals("two", new String(bContent, StandardCharsets.UTF_8));
    }

    /**
     * hg4j (host JVM) writes a commit into a real-hg-initialized Docker-only-combo repo; real hg
     * (inside the container) must read it back identically and {@code hg verify} must find no
     * integrity errors -- this is the write-path half the plan doc calls out as previously
     * completely untested for this quarter of the matrix (only read-direction fixtures existed
     * before this file).
     *
     * <p>Unlike the read-direction test, this uses its OWN dedicated per-test container (see
     * {@link #withFreshContainer}) instead of the class-shared one. Empirically (2026-09-04): even
     * with the write itself isolated in a subprocess ({@link #commitInSubprocess}), running MANY
     * write-direction cases back-to-back against the SAME long-lived shared container produced a
     * rarer, different corruption symptom (real hg's {@code verify} reporting a tracked file as
     * "not marked as tracked in p1") specifically for {@code dirstate-v2} combos late in the
     * sequence -- never reproduced in isolation (4/4 clean runs), only when accumulated behind many
     * prior combos in one container's lifetime. A fresh container per case costs ~1-2s but was
     * 100% clean across dozens of samples; that trade is worth it for a correctness-critical test.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jWritesRealHgReadsAcrossDockerCombo(RequirementCombo combo) throws Exception {
        // Backlog #37 (mercurial-spec-compliance-requirement.md, 2026-09-04): FIXED. Root cause was
        // DirstateV2Serializer writing each level's node array in LinkedHashMap insertion order
        // instead of sorted ascending by basename bytes -- real hg's Rust reader looks up children
        // via `binary_search_by` (dirstate/dirstate_map.rs) and requires that ordering, so an
        // out-of-order sibling was silently "not found" (real hg reported it as untracked / a
        // dirstate-vs-manifest mismatch) even though hg4j's own order-agnostic DFS-stack reader
        // parsed the same bytes back fine. See DirstateV2Serializer.compareUtf8Bytes and
        // DirstateV2SerializerCoverageTest's regression tests for the byte-level fix.
        withFreshContainer((writeContainerName, writeWorkDir) -> {
            String repoRelPath = "repo";
            Path hostRepoDir = writeWorkDir.resolve(repoRelPath);
            Files.createDirectories(hostRepoDir);

            List<String> initArgs = new ArrayList<>(List.of("init", "."));
            for (String c : combo.initConfigArgs()) {
                initArgs.add("--config");
                initArgs.add(c);
            }
            dockerHgIn(writeContainerName, repoRelPath, initArgs.toArray(new String[0]));

            Files.writeString(hostRepoDir.resolve("seed.txt"), "seed");
            dockerHgIn(writeContainerName, repoRelPath, "add");
            dockerHgIn(writeContainerName, repoRelPath, "commit", "-u", "dev", "-m", "seed");

            Files.writeString(hostRepoDir.resolve("hg4j.txt"), "from hg4j");
            String hg4jHex = commitInSubprocess(hostRepoDir, "hg4j", "hg4j commit for " + combo);

            String realTipHex = dockerHgIn(writeContainerName, repoRelPath, "log", "-r", "tip", "--template", "{node}");
            assertEquals(hg4jHex, realTipHex, "real hg's tip must be the hg4j-written commit for combo " + combo);

            String catOut = dockerHgIn(writeContainerName, repoRelPath, "cat", "-r", "tip", "hg4j.txt");
            assertEquals("from hg4j", catOut);

            String verify = dockerHgTolerantIn(writeContainerName, repoRelPath, "verify");
            assertFalse(verify.toLowerCase().contains("integrity error") || verify.toLowerCase().contains("error:"),
                    "real hg verify must find no integrity errors for combo " + combo + ": " + verify);
            assertFalse(verify.contains("not marked as tracked"),
                    "real hg verify must not report a dirstate/manifest tracking mismatch for combo " + combo + ": " + verify);

            HgRepository repo = new HgRepository(hostRepoDir.toFile());
            Status status = new StatusCommand(repo).call();
            assertTrue(status.getAdded().isEmpty() && status.getModified().isEmpty(),
                    "hg4j's own status must be clean immediately after its own commit for combo " + combo);
        });
    }

    @FunctionalInterface
    private interface FreshContainerTest {
        void run(String containerName, Path workDir) throws Exception;
    }

    private static void withFreshContainer(FreshContainerTest test) throws Exception {
        Path workDir = Files.createTempDirectory("hg4j-docker-matrix-write").toRealPath();
        String freshContainerName = "hg4j-reqmatrix-write-" + UUID.randomUUID().toString().substring(0, 8);
        runHost("docker", "run", "-d", "--rm", "--name", freshContainerName,
                "-v", workDir + ":/repo-root", IMAGE, "sleep", "infinity");
        try {
            Exception last = null;
            for (int i = 0; i < 20; i++) {
                try {
                    runHost("docker", "exec", "--user", hostUidGid, freshContainerName, "hg", "--version");
                    last = null;
                    break;
                } catch (Exception e) {
                    last = e;
                    Thread.sleep(250);
                }
            }
            if (last != null) {
                throw new AssertionError("Fresh container " + freshContainerName + " never became ready", last);
            }
            test.run(freshContainerName, workDir);
        } finally {
            try {
                new ProcessBuilder("docker", "stop", freshContainerName).redirectErrorStream(true).start().waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best effort
            }
            deleteRecursively(workDir.toFile());
        }
    }

    private static String dockerHgIn(String container, String repoRelPath, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker", "exec", "--user", hostUidGid,
                "-w", "/repo-root/" + repoRelPath, container, "hg"));
        cmd.addAll(Arrays.asList(args));
        return runHost(cmd.toArray(new String[0])).trim();
    }

    private static String dockerHgTolerantIn(String container, String repoRelPath, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker", "exec", "--user", hostUidGid,
                "-w", "/repo-root/" + repoRelPath, container, "hg"));
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        p.waitFor();
        return out.trim();
    }
}
