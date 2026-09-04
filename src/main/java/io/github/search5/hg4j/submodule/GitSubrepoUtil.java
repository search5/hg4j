package io.github.search5.hg4j.submodule;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Minimal shell-out helper replicating the pieces of a {@code [git]}-prefixed {@code .hgsub}
 * subrepo's lifecycle that hg4j needs from real hg's {@code mercurial/subrepo.py}
 * {@code gitsubrepo} class (read live against Mercurial 7.2, backlog item 32 gap #3/#4):
 * reading its currently checked-out commit ({@code git rev-parse HEAD}, real hg's
 * {@code _gitstate()}/{@code basestate()}), detecting uncommitted local changes to tracked
 * files while ignoring untracked ones ({@code git status --porcelain --untracked-files=no},
 * real hg's {@code dirty()} via {@code git diff-index --quiet HEAD}), committing them
 * recursively ({@code git commit -a -m ... [--author ...]}, real hg's {@code commit()}), and
 * checking out a pinned commit ({@code git clone}/{@code fetch}/{@code checkout}, real hg's
 * {@code _fetch()}/{@code get()}).
 *
 * <p>Simplification versus real hg (documented, not a functional gap for the scenarios this
 * class supports): {@code gitsubrepo.get()} prefers checking out a named branch that happens to
 * point at the target commit (falling back to a detached-HEAD checkout with a warning only when
 * no such branch exists); hg4j always does a plain detached {@code git checkout <sha>}, which
 * lands on identical content/commit either way, just without a named branch attached
 * afterwards.
 */
public final class GitSubrepoUtil {

    private GitSubrepoUtil() {
    }

    /** Whether {@code dir} is (or contains) a local git checkout, mirroring real hg's
     * {@code gitsubrepo._gitmissing()} check (inverted). */
    public static boolean isGitCheckout(File dir) {
        return new File(dir, ".git").exists();
    }

    /** {@code git rev-parse HEAD} -- real hg's {@code gitsubrepo._gitstate()}/{@code basestate()}. */
    public static String revParseHead(File gitDir) throws IOException {
        return run(gitDir, Collections.emptyMap(), "rev-parse", "HEAD").trim();
    }

    /**
     * Mirrors {@code gitsubrepo.dirty(ignoreupdate=True)}: staged/unstaged changes to TRACKED
     * files only -- untracked files are ignored, matching real hg's own
     * {@code git diff-index --quiet HEAD} (preceded by {@code git update-index -q --refresh}).
     */
    public static boolean isDirty(File gitDir) throws IOException {
        run(gitDir, Collections.emptyMap(), "update-index", "-q", "--refresh");
        String out = run(gitDir, Collections.emptyMap(), "status", "--porcelain", "--untracked-files=no");
        return !out.trim().isEmpty();
    }

    /** {@code git cat-file -e <sha>} -- real hg's {@code gitsubrepo._githavelocally()}. */
    public static boolean hasLocally(File gitDir, String sha) {
        try {
            run(gitDir, Collections.emptyMap(), "cat-file", "-e", sha);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static void clone(File parentDir, String sourceUrl, File targetDir) throws IOException {
        parentDir.mkdirs();
        run(parentDir, Collections.emptyMap(), "clone", "-q", sourceUrl, targetDir.getAbsolutePath());
    }

    public static void fetch(File gitDir) throws IOException {
        run(gitDir, Collections.emptyMap(), "fetch", "-q");
    }

    public static void checkout(File gitDir, String sha) throws IOException {
        run(gitDir, Collections.emptyMap(), "checkout", "-q", sha);
    }

    /**
     * Mirrors {@code gitsubrepo.commit()}: {@code git commit -a -m <text> [--author <user>]},
     * with {@code GIT_AUTHOR_DATE} set (ISO-8601) when a commit timestamp is supplied, then
     * returns the new HEAD commit sha -- the value {@code CommitCommand} records in
     * {@code .hgsubstate}.
     */
    public static String commit(File gitDir, String message, String author, Long epochSeconds, Integer tzOffsetSeconds) throws IOException {
        List<String> args = new ArrayList<>();
        args.add("commit");
        args.add("-a");
        args.add("-m");
        args.add(message == null ? "" : message);
        if (author != null && !author.isEmpty()) {
            args.add("--author");
            args.add(author);
        }
        Map<String, String> env;
        if (epochSeconds != null) {
            ZoneOffset offset = ZoneOffset.ofTotalSeconds(tzOffsetSeconds != null ? -tzOffsetSeconds : 0);
            String iso = Instant.ofEpochSecond(epochSeconds).atOffset(offset)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            env = Collections.singletonMap("GIT_AUTHOR_DATE", iso);
        } else {
            env = Collections.emptyMap();
        }
        run(gitDir, env, args.toArray(new String[0]));
        return revParseHead(gitDir);
    }

    private static String run(File cwd, Map<String, String> extraEnv, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        Collections.addAll(cmd, args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd);
        pb.redirectErrorStream(true);
        pb.environment().putAll(extraEnv);
        Process process = pb.start();
        String output;
        try (InputStream is = process.getInputStream()) {
            output = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git " + String.join(" ", args) + " was interrupted", e);
        }
        if (exitCode != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed (exit " + exitCode + "): " + output.trim());
        }
        return output;
    }
}
