package io.github.search5.hg4j.api;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs the Rust-extension-enabled real hg build ({@code docker/hg-rust-7.2.4/build-native.sh}'s
 * output) directly as a host subprocess -- the native replacement for what the ~50
 * {@code RequirementMatrix*DockerRoundTripTest} classes used to do via a fresh Docker container
 * per test case ({@code docker run}/{@code docker exec}/{@code docker stop} through
 * {@code localhost/hg-rust-7.2.4}). Measured 2026-09-08/09: a single 30-combo class took ~12
 * minutes under Docker on this machine (macOS + Podman's VM, ~24s/combo of container
 * start/exec/stop overhead alone) versus native subprocess exec, which is sub-second per call --
 * turning the full ~50-class matrix from an ~8-10 hour run into minutes.
 *
 * <p>Every machine that runs the interop suite is expected to already have run
 * {@code docker/hg-rust-7.2.4/build-native.sh} (2026-09-09 user decision) -- there is
 * deliberately no Docker fallback here. A machine without it just skips the whole class via
 * {@link #isAvailable()}.</p>
 */
public final class NativeHgRust {
    private NativeHgRust() {
    }

    private static final String HG_BIN = resolveHgBin();

    private static String resolveHgBin() {
        String override = System.getenv("HG4J_HG_RUST_BIN");
        if (override != null && !override.isBlank()) {
            return override;
        }
        // Gradle test workers run with the project root as their working directory by default.
        return new File(System.getProperty("user.dir"), ".native/hg-rust-7.2.4/hg").getAbsolutePath();
    }

    public static boolean isAvailable() {
        return new File(HG_BIN).canExecute();
    }

    @FunctionalInterface
    public interface FreshWorkDirTest {
        void run(Path workDir) throws Exception;
    }

    /**
     * Replaces the old per-class {@code withFreshContainer} -- a fresh temp directory instead of
     * a fresh container, since there is no container lifecycle to manage anymore.
     */
    public static void withFreshWorkDir(String tempDirPrefix, FreshWorkDirTest test) throws Exception {
        Path workDir = Files.createTempDirectory(tempDirPrefix).toRealPath();
        try {
            test.run(workDir);
        } finally {
            deleteRecursively(workDir.toFile());
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

    /**
     * Replaces the old per-class {@code dockerHgIn(containerName, repoRelPath, args...)} -- runs
     * the native rust-enabled {@code hg} directly against {@code workDir/repoRelPath}, no
     * container/UID-mapping involved.
     */
    public static String hg(Path workDir, String repoRelPath, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(HG_BIN));
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.resolve(repoRelPath).toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new AssertionError("hg " + Arrays.toString(args) + " failed with exit " + code + ": " + out);
        }
        return out.trim();
    }

    /** Same as {@link #hg}, but does not throw on a non-zero exit code -- for call sites that
     * expect (and assert on) real hg's own failure output, e.g. an intentionally-conflicting
     * merge. Replaces the old per-class {@code dockerHgTolerantIn}/{@code dockerHgInTolerant}. */
    public static String hgTolerant(Path workDir, String repoRelPath, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(HG_BIN));
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.resolve(repoRelPath).toFile());
        pb.redirectErrorStream(true);
        // Belt-and-suspenders against any interactive prompt hanging forever in a headless test
        // run (the real fix is forcing ui.merge=internal:merge at the call site) -- an immediate
        // EOF makes any prompt fail fast instead of blocking indefinitely on the parent's stdin.
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process p = pb.start();
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        p.waitFor();
        return out.trim();
    }
}
