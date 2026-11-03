package io.github.search5.hg4j.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs a REAL Mercurial CLI built with the Rust extensions on the host, as the oracle for the
 * interop tests. It replaces the {@code hg-rust-7.2.4} Docker image the tests used before; build the
 * CLI once with {@code scripts/build-local-hg.sh}.
 *
 * <h2>Where the CLI is found</h2>
 * <ol>
 *   <li>the {@code HG4J_HG} environment variable (path of an {@code hg} executable), else</li>
 *   <li>{@code $HG4J_HG_HOME/bin/hg}, else {@code ~/.cache/hg4j/hg-rust-7.2.4/bin/hg}.</li>
 * </ol>
 * A CLI that does not report {@code Rust extensions (installed)} in {@code hg debuginstall} is
 * rejected, because a pure-Python hg refuses {@code exp-revlogv2.2} and {@code persistent-nodemap}
 * repositories and would make those tests fail for the wrong reason.
 *
 * <h2>Isolation</h2>
 * Every invocation runs with {@code HGRCPATH} empty (no user or system hgrc), {@code TZ=UTC},
 * {@code LC_ALL=C.UTF-8} and no inherited {@code HG*} variables, which is what the container gave
 * the tests implicitly.
 */
public final class LocalHg {

    /**
     * The directory the tests' working area used to be mounted at inside the container. Arguments that
     * start with this prefix are mapped to the real working directory by {@link #hgIn}, so the old
     * hard-coded paths such as {@code /repo-root/bundle1.hg} keep their meaning.
     */
    public static final String CONTAINER_ROOT_ALIAS = "/repo-root";

    private static final long TIMEOUT_SECONDS = 300;

    private static volatile Path cachedExecutable;
    private static volatile String cachedProblem;
    private static volatile boolean resolved;

    private LocalHg() {
    }

    /** @return true when a Rust-enabled hg is available; see {@link #unavailableReason()} otherwise */
    public static boolean isAvailable() {
        resolve();
        return cachedExecutable != null;
    }

    /** @return why {@link #isAvailable()} is false, or null when it is true */
    public static String unavailableReason() {
        resolve();
        return cachedProblem;
    }

    /** @return the hg executable; fails with the reason if none is usable */
    public static Path executable() {
        resolve();
        if (cachedExecutable == null) {
            throw new IllegalStateException(cachedProblem);
        }
        return cachedExecutable;
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        List<Path> candidates = new ArrayList<>();
        String explicit = System.getenv("HG4J_HG");
        if (explicit != null && !explicit.isBlank()) {
            candidates.add(Paths.get(explicit));
        }
        String home = System.getenv("HG4J_HG_HOME");
        if (home != null && !home.isBlank()) {
            candidates.add(Paths.get(home, "bin", "hg"));
        }
        candidates.add(Paths.get(System.getProperty("user.home"), ".cache", "hg4j", "hg-rust-7.2.4", "bin", "hg"));

        StringBuilder problems = new StringBuilder();
        for (Path candidate : candidates) {
            if (!Files.isExecutable(candidate)) {
                problems.append("\n  ").append(candidate).append(": not an executable file");
                continue;
            }
            try {
                String install = capture(null, List.of(candidate.toString(), "debuginstall"), Map.of());
                if (install.contains("Rust extensions (installed)")) {
                    cachedExecutable = candidate;
                    resolved = true;
                    return;
                }
                problems.append("\n  ").append(candidate).append(": hg debuginstall does not report"
                        + " 'Rust extensions (installed)'");
            } catch (Exception e) {
                problems.append("\n  ").append(candidate).append(": ").append(e);
            }
        }
        cachedProblem = "No Rust-enabled hg found. Build one with scripts/build-local-hg.sh or point HG4J_HG at it."
                + problems;
        resolved = true;
    }

    /**
     * Runs {@code hg <args>} inside {@code workDir/repoRelPath} and returns its trimmed output
     * (stdout and stderr merged). Arguments starting with {@link #CONTAINER_ROOT_ALIAS} are mapped to
     * {@code workDir}. A non-zero exit status fails with the command and its output.
     */
    public static String hgIn(Path workDir, String repoRelPath, String... args) throws Exception {
        return run(workDir.resolve(repoRelPath), mapAlias(workDir, args)).trim();
    }

    /**
     * Like {@link #hgIn} but a non-zero exit status is NOT an error: the (trimmed) merged output is returned
     * either way. For commands whose failure text is itself the thing being compared with hg4j.
     */
    public static String hgTolerantIn(Path workDir, String repoRelPath, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(executable().toString());
        command.addAll(Arrays.asList(mapAlias(workDir, args)));
        return capture(workDir.resolve(repoRelPath), command, Map.of()).trim();
    }

    /** Runs {@code hg <args>} in {@code cwd}; the output is returned untrimmed. */
    public static String run(Path cwd, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(executable().toString());
        command.addAll(Arrays.asList(args));
        int[] exit = new int[1];
        String out = capture(cwd, command, Map.of(), exit);
        if (exit[0] != 0) {
            throw new AssertionError("hg " + String.join(" ", args) + " (in " + cwd + ") failed with exit "
                    + exit[0] + ": " + out);
        }
        return out;
    }

    /**
     * A {@link ProcessBuilder} for {@code hg <args>} with the isolated environment applied, for tests that
     * need the exit status or the streams themselves. stdout and stderr are NOT merged and nothing is
     * redirected, so the caller must consume or redirect both streams.
     */
    public static ProcessBuilder processBuilder(Path cwd, String... args) {
        List<String> command = new ArrayList<>();
        command.add(executable().toString());
        command.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        if (cwd != null) {
            pb.directory(cwd.toFile());
        }
        applyEnvironment(pb, Map.of());
        return pb;
    }

    private static String[] mapAlias(Path workDir, String[] args) {
        String[] mapped = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals(CONTAINER_ROOT_ALIAS) || a.startsWith(CONTAINER_ROOT_ALIAS + "/")) {
                mapped[i] = workDir.toString() + a.substring(CONTAINER_ROOT_ALIAS.length());
            } else {
                mapped[i] = a;
            }
        }
        return mapped;
    }

    private static void applyEnvironment(ProcessBuilder pb, Map<String, String> extra) {
        Map<String, String> env = pb.environment();
        // The hg wrapper script sets HGMODULEPOLICY itself, so every inherited HG* variable can go.
        env.keySet().removeIf(k -> k.startsWith("HG"));
        env.put("HGRCPATH", "");
        env.put("TZ", "UTC");
        env.put("LC_ALL", "C.UTF-8");
        env.put("LANG", "C.UTF-8");
        env.putAll(extra);
    }

    private static String capture(Path cwd, List<String> command, Map<String, String> extraEnv) throws Exception {
        return capture(cwd, command, extraEnv, new int[1]);
    }

    private static String capture(Path cwd, List<String> command, Map<String, String> extraEnv, int[] exitOut)
            throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (cwd != null) {
            pb.directory(cwd.toFile());
        }
        applyEnvironment(pb, extraEnv);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        byte[] out;
        try (InputStream is = p.getInputStream()) {
            out = is.readAllBytes();
        }
        if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("timed out after " + TIMEOUT_SECONDS + "s: " + String.join(" ", command));
        }
        exitOut[0] = p.exitValue();
        return new String(out, StandardCharsets.UTF_8);
    }
}
