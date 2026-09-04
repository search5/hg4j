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

    /**
     * Mirrors real hg's {@code gitsubrepo.merge()} (Mercurial 7.2, read live + reproduced with
     * real hg CLI + a real git subrepo, backlog 32 follow-up "gap B") for the deterministic
     * (non-interactive-default) case where a git subrepo's pinned revision <em>diverged</em>
     * between the two parents of an {@code hg merge} -- i.e. {@code subrepoutil.submerge()}
     * already determined both the local and remote {@code .hgsubstate} pins changed from their
     * common ancestor, and (per real hg's own {@code ui.promptchoice(msg, 0)} default, which is
     * "Merge") delegated to the subrepo's own {@code merge()}.
     *
     * <p>Real hg's algorithm, ported verbatim:
     * <pre>
     * base = git merge-base(revision, self._state[1])   # self._state[1] == localRev
     * if base == revision:
     *     self.get(state)                # "fast forward merge" -- literally checks out
     *                                     # revision even though it is an ancestor of local
     * elif base != self._state[1]:
     *     self._gitcommand(['merge', '--no-commit', revision])   # exit code IGNORED
     * # else (base == localRev, a genuine forward-only ff): real hg does nothing at all here
     * </pre>
     *
     * <p>Whatever this leaves the git working tree in (cleanly merged-but-uncommitted, or
     * conflicted with unresolved markers + {@code MERGE_HEAD} -- real hg discards the exit code
     * of {@code git merge --no-commit} exactly like every other {@code _gitcommand} call, so it
     * never even notices a conflict here) is picked up later by the already-implemented
     * dirty()/commit() machinery (backlog 32 gap #3) the next time the parent repo is committed:
     * a clean merge gets recursively {@code git commit -a}ed (or blocks the parent commit
     * without {@code --subrepos}, same as any other dirty git subrepo); an unresolved conflict
     * makes that {@code git commit -a} itself fail, which surfaces as an aborted parent commit.
     *
     * <p>Verified live (Mercurial 7.2 + git, 2026-09-04): two hg commits independently modified
     * a git subrepo from a common git ancestor (added {@code left.txt} vs {@code right.txt} --
     * no textual overlap, so the underlying {@code git merge --no-commit} itself resolved
     * cleanly); {@code hg merge} (non-interactively) printed the "subrepository ... diverged ...
     * (m)erge/(l)ocal/(r)emote" prompt, auto-picked "Merge", and left {@code .hgsubstate}
     * pointing at the OLD local pin (unchanged) while the git subrepo's working tree held a
     * real two-parent git merge staged (not yet committed); the following {@code hg commit -S}
     * then recorded a genuine two-parent git merge commit and updated {@code .hgsubstate} to
     * its sha, via the pre-existing gap #3 dirty-commit path -- exactly the sequence this method
     * (plus {@code MergeCommand#mergeSubrepoState}, which deliberately leaves the
     * {@code .hgsubstate} pin at the local value) reproduces.
     *
     * <p>This method does NOT record anything into {@code .hgsubstate} itself -- matching real
     * hg, where {@code subrepoutil.submerge()} always sets the recorded state to the LOCAL pin
     * for the "merge" and "local" prompt choices (the "remote" choice, not real hg's default, is
     * the only one that adopts the remote pin instead -- see the class-level {@code merge()}
     * quirk note above); the caller is responsible for that.
     */
    public static void mergeDiverged(File gitDir, String remoteRev, String localRev) throws IOException {
        if (!hasLocally(gitDir, remoteRev)) {
            fetch(gitDir);
        }
        String base = mergeBase(gitDir, remoteRev, localRev);
        if (base.equals(remoteRev)) {
            checkout(gitDir, remoteRev);
        } else if (!base.equals(localRev)) {
            mergeNoCommit(gitDir, remoteRev);
        }
        // else: base == localRev (a genuine forward-only fast-forward) -- real hg's own
        // gitsubrepo.merge() takes neither branch in this case and does nothing at all.
    }

    /** {@code git merge-base <rev1> <rev2>} -- real hg's {@code gitsubrepo.merge()} base lookup. */
    public static String mergeBase(File gitDir, String rev1, String rev2) throws IOException {
        return run(gitDir, Collections.emptyMap(), "merge-base", rev1, rev2).trim();
    }

    /**
     * {@code git merge --no-commit <revision>} -- real hg's {@code gitsubrepo.merge()} calls
     * this via {@code self._gitcommand(...)}, which (verified live and by reading
     * {@code _gitcommand}/{@code _gitdir}/{@code _gitnodir}) discards the process's exit code
     * unconditionally, so a conflicted merge is deliberately NOT treated as an error here
     * either -- the caller only cares about the resulting git working tree state (picked up
     * later by {@link #isDirty}/{@link #commit}), not this call's own success/failure.
     */
    public static void mergeNoCommit(File gitDir, String revision) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("merge");
        cmd.add("--no-commit");
        cmd.add(revision);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(gitDir);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (InputStream is = process.getInputStream()) {
            is.readAllBytes();
        }
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git merge --no-commit " + revision + " was interrupted", e);
        }
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
