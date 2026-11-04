package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.match.Matcher;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Real hg's {@code cmdutil.copy}: {@code hg copy} and {@code hg rename} for the working copy. The sources and the destination are
 * patterns (the destination is the last one); the messages are the ones real hg prints, split into warnings and status lines.
 *
 * <p>The caller holds the working copy lock (real hg: {@code with repo.wlock(), repo.dirstate.changing_files(repo)}) and writes the
 * dirstate this engine changed.</p>
 */
final class CopyEngine {

    /** @param failed real hg's exit status 1: at least one file was not copied */
    record Result(boolean failed, List<String> warnings, List<String> status) {
    }

    private record Source(String abs, String rel, boolean exact) {
    }

    private final HgRepository repository;
    private final boolean rename;
    private final List<String> patterns;
    private final boolean after;
    private final boolean force;
    private final boolean dryRun;
    private final boolean forget;
    private final List<String> include;
    private final List<String> exclude;
    private final String cwd;

    private final List<String> warnings = new ArrayList<>();
    private final List<String> status = new ArrayList<>();
    private final Map<String, String> targets = new HashMap<>();
    private final File root;
    private Dirstate dirstate;
    private Map<String, String> p1Manifest;
    private List<Map<String, String>> p1ParentManifests;

    CopyEngine(HgRepository repository, boolean rename, List<String> patterns, boolean after, boolean force, boolean dryRun,
               boolean forget, List<String> include, List<String> exclude, String cwd) {
        this.repository = repository;
        this.rename = rename;
        this.patterns = new ArrayList<>(patterns);
        this.after = after;
        this.force = force;
        this.dryRun = dryRun;
        this.forget = forget;
        this.include = include;
        this.exclude = exclude;
        this.cwd = cwd == null ? "" : cwd;
        this.root = repository.getDirectory();
    }

    /**
     * Real hg's {@code with repo.wlock(), repo.dirstate.changing_files(repo): return cmdutil.copy(...)}: the working copy lock is taken
     * first, the dirstate is changed and written at the end, and it is put back when something fails on the way.
     */
    static Result execute(HgRepository repository, boolean rename, List<String> patterns, boolean after, boolean force,
                          boolean dryRun, boolean forget, List<String> include, List<String> exclude, String cwd)
            throws IOException, io.github.search5.hg4j.errors.HgLockException, HgValidationException {
        return execute(repository, rename, patterns, after, force, dryRun, forget, include, exclude, cwd, null);
    }

    static Result execute(HgRepository repository, boolean rename, List<String> patterns, boolean after, boolean force,
                          boolean dryRun, boolean forget, List<String> include, List<String> exclude, String cwd, String atRev)
            throws IOException, io.github.search5.hg4j.errors.HgLockException, HgValidationException {
        if (atRev != null) {
            // commands.copy: with --at-rev the working copy lock is taken but the dirstate is not put in a changing context
            return CopyAtRev.execute(repository, rename, patterns, atRev, after, forget, include, exclude, cwd);
        }
        try (io.github.search5.hg4j.lib.HgLock wlock = repository.lockWorkingCopy()) {
            // commands.copy: `context = lambda repo: repo.dirstate.changing_files(repo)`; the dirstate is written when the context ends
            // well and every change is dropped when it ends with an exception, so nothing has to be put back by hand
            return repository.changingFiles(() -> {
                Dirstate dirstate = repository.getDirstate();
                Result result = new CopyEngine(repository, rename, patterns, after, force, dryRun, forget, include, exclude, cwd)
                        .run(dirstate);
                if (!dryRun) {
                    repository.writeDirstate(dirstate);
                }
                return result;
            });
        }
    }

    Result run(Dirstate dirstate) throws IOException, HgValidationException {
        this.dirstate = dirstate;
        if (forget && dryRun) {
            throw new HgValidationException("cannot specify both --forget and --dry-run");
        }
        if (forget) {
            unmarkCopies();
            return new Result(false, warnings, status);
        }
        List<String> pats = new ArrayList<>(patterns);
        if (pats.isEmpty()) {
            throw new HgValidationException("no source or destination specified");
        }
        if (pats.size() == 1) {
            throw new HgValidationException("no destination specified");
        }
        String dest = pats.remove(pats.size() - 1);
        boolean destDirExists = new File(cwdDir(), dest).isDirectory() && !Files.isSymbolicLink(new File(cwdDir(), dest).toPath());
        if (!destDirExists) {
            if (pats.size() > 1 || patKind(pats.get(0)) != null) {
                throw new HgValidationException("with multiple sources, destination must be an existing directory");
            }
            if (dest.endsWith("/")) {
                throw new HgValidationException("destination " + dest + " is not a directory");
            }
        }
        List<Map.Entry<Function<String, String>, List<Source>>> copylist = new ArrayList<>();
        for (String pat : pats) {
            List<Source> srcs = walkPattern(pat);
            if (srcs.isEmpty()) {
                continue;
            }
            Function<String, String> tfn = after ? targetPathAfter(pat, dest, srcs, destDirExists)
                    : targetPath(pat, dest, destDirExists);
            copylist.add(Map.entry(tfn, srcs));
        }
        if (copylist.isEmpty()) {
            throw new HgValidationException(rename ? "no files to copy (maybe you meant to use --after --at-rev=.)" : "no files to copy");
        }
        int errors = 0;
        for (Map.Entry<Function<String, String>, List<Source>> item : copylist) {
            for (Source src : item.getValue()) {
                if (copyFile(src.abs(), src.rel(), item.getKey().apply(src.abs()), src.exact())) {
                    errors++;
                }
            }
        }
        return new Result(errors != 0, warnings, status);
    }

    // ------------------------------------------------------------------ paths

    private File cwdDir() {
        return cwd.isEmpty() ? root : new File(root, cwd);
    }

    /** Real hg's {@code pathutil.canonpath(root, cwd, name)}: a name relative to the current directory as a root-relative path. */
    private String canonPath(String name) throws HgValidationException {
        Path joined = cwd.isEmpty() ? Paths.get(name) : Paths.get(cwd).resolve(name);
        if (joined.isAbsolute()) {
            Path rootPath = root.toPath().toAbsolutePath().normalize();
            Path normalized = joined.normalize();
            if (!normalized.startsWith(rootPath)) {
                throw new HgValidationException(name + " not under root '" + root + "'");
            }
            joined = rootPath.relativize(normalized);
        }
        Path normalized = joined.normalize();
        String result = normalized.toString().replace(File.separatorChar, '/');
        if (result.equals("..") || result.startsWith("../")) {
            throw new HgValidationException(name + " not under root '" + root + "'");
        }
        return result;
    }

    /** Real hg's {@code repo.pathto(path, cwd)}: a root-relative path as it is written from the current directory. */
    private String pathTo(String abs) {
        if (cwd.isEmpty()) {
            return abs;
        }
        return Paths.get(cwd).relativize(Paths.get(abs)).toString().replace(File.separatorChar, '/');
    }

    private static final java.util.Set<String> KINDS = java.util.Set.of("re", "glob", "path", "filepath", "relglob", "relpath",
            "relre", "rootglob", "listfile", "listfile0", "set", "include", "subinclude", "rootfilesin");

    /** Real hg's {@code matchmod.patkind}. */
    private static String patKind(String pattern) {
        int colon = pattern.indexOf(':');
        if (colon > 0 && KINDS.contains(pattern.substring(0, colon))) {
            return pattern.substring(0, colon);
        }
        return null;
    }

    // ------------------------------------------------------------------ the working copy and its parent

    private boolean inCtx(String path) {
        Dirstate.Entry entry = dirstate.getEntries().get(path);
        return entry != null && entry.getState() != 'r';
    }

    private void loadParentManifests() throws IOException, HgValidationException {
        if (p1Manifest != null) {
            return;
        }
        p1Manifest = new HashMap<>();
        p1ParentManifests = new ArrayList<>();
        byte[] p1 = dirstate.getParent1();
        if (p1 == null || NodeIdUtil.isAllZero(p1)) {
            return;
        }
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);
        int rev = changelog.findRevision(p1);
        if (rev < 0) {
            return;
        }
        p1Manifest = repository.getManifestAtCommit(p1);
        Revlog.IndexRecord record = changelog.getIndexRecord(rev);
        for (int parent : new int[] {record.getParent1(), record.getParent2()}) {
            if (parent >= 0) {
                p1ParentManifests.add(repository.getManifestAtCommit(changelog.getIndexRecord(parent).getNodeId()));
            }
        }
    }

    private boolean inPctx(String path) throws IOException, HgValidationException {
        loadParentManifests();
        return p1Manifest.containsKey(path);
    }

    private Matcher matcher(String... pats) throws IOException, HgValidationException {
        FileSelection selection = new FileSelection().patterns(pats).defaultKind("relpath").cwd(cwd);
        if (include != null && !include.isEmpty()) {
            selection.include(include.toArray(String[]::new));
        }
        if (exclude != null && !exclude.isEmpty()) {
            selection.exclude(exclude.toArray(String[]::new));
        }
        return selection.matcher(repository, null);
    }

    /** {@code ctx.walk(match)} of the working copy: the files in the dirstate, and the named files that are only on disk. */
    private List<String> walk(Matcher matcher) {
        TreeSet<String> found = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        for (String path : dirstate.getEntries().keySet()) {
            if (matcher.matches(path)) {
                found.add(path);
            }
        }
        for (String named : matcher.files()) {
            File file = new File(root, named);
            if (!named.isEmpty() && (file.isFile() || Files.isSymbolicLink(file.toPath())) && matcher.matches(named)) {
                found.add(named);
            }
        }
        return new ArrayList<>(found);
    }

    // ------------------------------------------------------------------ --forget

    /** The {@code forget} branch of {@code cmdutil.copy} for the working copy: copy records in the dirstate are dropped. */
    private void unmarkCopies() throws IOException, HgValidationException {
        List<String> pats = new ArrayList<>(patterns);
        Matcher matcher = matcher(pats.toArray(String[]::new));
        for (String f : walk(matcher)) {
            if (dirstate.getCopyMap().containsKey(f)) {
                dirstate.getCopyMap().remove(f);
            } else if (matcher.exact(f)) {
                warnings.add(pathTo(f) + ": not unmarking as copy - file is not marked as copied");
            }
        }
    }

    // ------------------------------------------------------------------ sources

    private List<Source> walkPattern(String pat) throws IOException, HgValidationException {
        Matcher matcher = matcher(pat);
        List<Source> sources = new ArrayList<>();
        for (String abs : walk(matcher)) {
            String rel = pathTo(abs);
            boolean exact = matcher.exact(abs);
            if (!inCtx(abs)) {
                if (inPctx(abs)) {
                    if (!after) {
                        if (exact) {
                            warnings.add(rel + ": not copying - file has been marked for remove");
                        }
                        continue;
                    }
                } else {
                    if (exact) {
                        warnings.add(rel + ": not copying - file is not managed");
                    }
                    continue;
                }
            }
            sources.add(new Source(abs, rel, exact));
        }
        return sources;
    }

    private Function<String, String> targetPath(String pat, String dest, boolean destDirExists) throws HgValidationException {
        if (new File(cwdDir(), pat).isDirectory()) {
            String absPrefix = canonPath(pat);
            int stripLen;
            if (destDirExists) {
                int slash = absPrefix.lastIndexOf('/');
                stripLen = slash < 0 ? 0 : slash;
            } else {
                stripLen = absPrefix.length();
            }
            if (stripLen > 0) {
                stripLen += 1;
            }
            final int strip = stripLen;
            return p -> joinPath(dest, p.substring(Math.min(strip, p.length())));
        } else if (destDirExists) {
            return p -> joinPath(dest, baseName(p));
        }
        return p -> dest;
    }

    private Function<String, String> targetPathAfter(String pat, String dest, List<Source> srcs, boolean destDirExists) throws HgValidationException {
        if (patKind(pat) != null) {
            return p -> joinPath(dest, baseName(p));
        }
        String absPrefix = canonPath(pat);
        if (absPrefix.length() < srcs.get(0).abs().length()) {
            int stripLen = absPrefix.length();
            if (stripLen > 0) {
                stripLen += 1;
            }
            String lastName = absPrefix.substring(absPrefix.lastIndexOf('/') + 1);
            if (new File(cwdDir(), joinPath(dest, lastName)).isDirectory()) {
                int score = evalPath(dest, srcs, stripLen);
                int slash = absPrefix.lastIndexOf('/');
                int stripLen1 = slash < 0 ? 0 : slash;
                if (stripLen1 > 0) {
                    stripLen1 += 1;
                }
                if (evalPath(dest, srcs, stripLen1) > score) {
                    stripLen = stripLen1;
                }
            }
            final int strip = stripLen;
            return p -> joinPath(dest, p.substring(Math.min(strip, p.length())));
        }
        if (destDirExists) {
            return p -> joinPath(dest, baseName(p));
        }
        return p -> dest;
    }

    private int evalPath(String dest, List<Source> srcs, int stripLen) {
        int score = 0;
        for (Source s : srcs) {
            String target = joinPath(dest, s.abs().substring(Math.min(stripLen, s.abs().length())));
            File file = new File(cwdDir(), target);
            if (file.exists() || Files.isSymbolicLink(file.toPath())) {
                score++;
            }
        }
        return score;
    }

    private static String baseName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** Python's {@code os.path.join}: an absolute second part wins, an empty first part is dropped. */
    private static String joinPath(String a, String b) {
        if (b.startsWith("/") || a.isEmpty()) {
            return b;
        }
        return a.endsWith("/") ? a + b : a + "/" + b;
    }

    // ------------------------------------------------------------------ one file

    /** @return true when the file was not copied (real hg: "report a failure") */
    private boolean copyFile(String absSrc, String relSrc, String otarget, boolean exact) throws IOException, HgValidationException {
        String absTarget = canonPath(otarget);
        String relTarget = pathTo(absTarget);
        File target = new File(root, absTarget);
        File src = new File(root, absSrc);
        Dirstate.Entry entry = dirstate.getEntries().get(absTarget);
        boolean alreadyCommitted = entry != null && entry.getState() != 'r' && entry.getState() != 'a';
        String prevSrc = targets.get(absTarget);
        if (prevSrc != null) {
            warnings.add(relTarget + ": not overwriting - " + pathTo(absSrc) + " collides with " + pathTo(prevSrc));
            return true;
        }
        boolean exists = target.exists() || Files.isSymbolicLink(target.toPath());
        if ((!after && exists) || (after && alreadyCommitted)) {
            if (!force) {
                String msg;
                String hint;
                if (alreadyCommitted) {
                    msg = relTarget + ": not overwriting - file already committed";
                    loadParentManifests();
                    boolean looksLikeCopyInPctx = p1Manifest.containsKey(absTarget);
                    if (looksLikeCopyInPctx) {
                        boolean any = false;
                        for (Map<String, String> grand : p1ParentManifests) {
                            any |= grand.containsKey(absSrc) && !grand.containsKey(absTarget);
                        }
                        looksLikeCopyInPctx = any;
                    }
                    if (looksLikeCopyInPctx) {
                        hint = rename ? "('hg rename --at-rev .' to record the rename in the parent of the working copy)"
                                : "('hg copy --at-rev .' to record the copy in the parent of the working copy)";
                    } else {
                        String flags = after ? "--after --force" : "--force";
                        hint = rename ? "('hg rename " + flags + "' to replace the file by recording a rename)"
                                : "('hg copy " + flags + "' to replace the file by recording a copy)";
                    }
                } else {
                    msg = relTarget + ": not overwriting - file exists";
                    hint = rename ? "('hg rename --after' to record the rename)" : "('hg copy --after' to record the copy)";
                }
                warnings.add(msg);
                warnings.add(hint);
                return true;
            }
        }
        boolean srcExists = true;
        if (after) {
            if (!exists) {
                warnings.add(relSrc + ": not recording " + (rename ? "move" : "copy") + " - " + relTarget + " does not exist");
                return true;
            }
        } else if (!dryRun) {
            try {
                if (exists) {
                    Files.delete(target.toPath());
                }
                File targetDir = target.getParentFile();
                if (targetDir != null && !targetDir.isDirectory()) {
                    Files.createDirectories(targetDir.toPath());
                }
                copyFileData(src, target);
            } catch (NoSuchFileException missing) {
                warnings.add(relSrc + ": deleted in working directory");
                srcExists = false;
            } catch (IOException failure) {
                warnings.add(relSrc + ": cannot copy - " + failure.getMessage());
                return true;
            }
        }
        if (!exact) {
            status.add((rename ? "moving " : "copying ") + relSrc + " to " + relTarget);
        }
        targets.put(absTarget, absSrc);
        dirstateCopy(absSrc, absTarget);
        if (rename && !dryRun) {
            if (!after && srcExists) {
                Files.deleteIfExists(src.toPath());
            }
            forgetFile(absSrc);
        }
        return false;
    }

    /** Real hg's {@code util.copyfile(src, dest, copystat=rename)}. */
    private void copyFileData(File src, File dest) throws IOException, HgValidationException {
        if (Files.isSymbolicLink(src.toPath())) {
            Files.createSymbolicLink(dest.toPath(), Files.readSymbolicLink(src.toPath()));
            return;
        }
        if (rename) {
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        } else {
            Files.copy(src.toPath(), dest.toPath());
            try {
                Files.setPosixFilePermissions(dest.toPath(), Files.getPosixFilePermissions(src.toPath()));
            } catch (UnsupportedOperationException ignored) {
                // a file system without permission bits
            }
        }
    }

    // ------------------------------------------------------------------ the dirstate

    /** Real hg's {@code dirstate.set_tracked}: an added file stays added, a file that is in the parent becomes "needs checking". */
    private void setTracked(String path) throws IOException, HgValidationException {
        Dirstate.Entry entry = dirstate.getEntries().get(path);
        if (entry != null && entry.getState() != 'r') {
            return;
        }
        if (inPctx(path)) {
            dirstate.addEntry(path, new Dirstate.Entry('n', 0, -1, Dirstate.Entry.AMBIGUOUS_TIME));
            return;
        }
        File file = new File(root, path);
        boolean symlink = Files.isSymbolicLink(file.toPath());
        int mode = !symlink && file.canExecute() ? 0755 : 0644;
        int size = symlink ? Files.readSymbolicLink(file.toPath()).toString().getBytes().length : (int) file.length();
        dirstate.addEntry(path, new Dirstate.Entry('a', mode, size, SafeFileIO.lastModifiedSeconds(file)));
    }

    /** Real hg's {@code dirstate.set_untracked}: an added file is dropped, a committed one becomes removed. */
    private void forgetFile(String path) throws IOException, HgValidationException {
        Dirstate.Entry entry = dirstate.getEntries().get(path);
        if (entry == null || entry.getState() == 'r') {
            return;
        }
        dirstate.getCopyMap().remove(path);
        if (entry.getState() == 'a') {
            dirstate.removeEntry(path);
        } else {
            dirstate.addEntry(path, new Dirstate.Entry('r', 0, 0, 0));
        }
    }

    /** Real hg's {@code scmutil.dirstatecopy}. */
    private void dirstateCopy(String src, String dst) throws IOException, HgValidationException {
        String copied = dirstate.getCopyMap().get(src);
        String origSrc = copied != null ? copied : src;
        Dirstate.Entry dstEntry = dirstate.getEntries().get(dst);
        boolean dstTracked = dstEntry != null && dstEntry.getState() != 'r';
        if (dst.equals(origSrc)) { // copying back a copy?
            if ((dstEntry != null && dstEntry.getState() == 'a' || !dstTracked) && !dryRun) {
                setTracked(dst);
            }
        } else {
            Dirstate.Entry origEntry = dirstate.getEntries().get(origSrc);
            if (origEntry != null && origEntry.getState() == 'a' && origSrc.equals(src)) {
                warnings.add(pathTo(origSrc) + " has not been committed yet, so no copy data will be stored for " + pathTo(dst) + ".");
                if (!dstTracked && !dryRun) {
                    setTracked(dst);
                }
            } else if (!dryRun) {
                File file = new File(root, dst);
                if (!file.exists() && !Files.isSymbolicLink(file.toPath())) {
                    warnings.add(pathTo(dst) + " does not exist!");
                } else if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath())) {
                    warnings.add("copy failed: " + pathTo(dst) + " is not a file or a symbolic link");
                } else {
                    setTracked(dst);
                    dirstate.addCopy(dst, origSrc);
                }
            }
        }
    }
}
