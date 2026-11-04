package io.github.search5.hg4j.fileset;

import io.github.search5.hg4j.api.CatCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.StatusCommand;
import io.github.search5.hg4j.api.Status;
import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.match.Matcher;
import io.github.search5.hg4j.match.Matchers;
import io.github.search5.hg4j.merge.MergeState;
import io.github.search5.hg4j.revset.HgRevsetEngine;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A {@link FilesetContext} over a repository: the working directory ({@code revision == null}), one revision, or the empty
 * context of the null revision ({@code revision} {@code "null"}), which is the parent of the first revision.
 */
public class RepositoryFilesetContext implements FilesetContext {

    /** The revision of the empty context before the first changeset. */
    public static final String NULL_REVISION = "null";

    private final HgRepository repository;
    private final String revision;
    private Map<String, String> manifest; // path -> node hex plus flag characters (revisions)
    private Set<String> tracked;
    private Integer revNumber;

    public RepositoryFilesetContext(HgRepository repository, String revision) {
        this.repository = repository;
        this.revision = revision;
    }

    public HgRepository repository() {
        return repository;
    }

    /** The revision, or {@code null} for the working directory. */
    public String revision() {
        return revision;
    }

    @Override
    public File root() {
        return repository.getDirectory();
    }

    @Override
    public Set<String> extensions() {
        Set<String> extensions = new HashSet<>();
        if (io.github.search5.hg4j.lfs.HgLfsConfig.isExtensionEnabled(repository)) {
            extensions.add("lfs");
        }
        return extensions;
    }

    @Override
    public boolean isWorkingDirectory() {
        return revision == null;
    }

    private boolean isNull() {
        return NULL_REVISION.equals(revision);
    }

    private Revlog changelog() throws IOException {
        return io.github.search5.hg4j.transport.wireprotov1.RepoSupport.changelog(repository);
    }

    /** The changeset number of a revision context. */
    private int rev() throws IOException {
        if (revNumber == null) {
            byte[] node = NodeIdUtil.resolveRevision(changelog(), revision);
            if (node == null) {
                throw new io.github.search5.hg4j.errors.HgRevisionNotFoundException("unknown revision '" + revision + "'");
            }
            revNumber = changelog().findRevision(node);
        }
        return revNumber;
    }

    /** The manifest of a revision context: path to node hex followed by the flag characters. */
    private synchronized Map<String, String> manifest() throws IOException {
        if (manifest == null) {
            if (isNull()) {
                manifest = Map.of();
            } else {
                manifest = repository.getManifestAtCommit(changelog().getIndexRecord(rev()).getNodeId());
            }
        }
        return manifest;
    }

    // ---------------------------------------------------------------- files

    /** All files tracked in this context. */
    public synchronized Set<String> trackedFiles() throws IOException {
        if (tracked == null) {
            if (isWorkingDirectory()) {
                tracked = new HashSet<>();
                for (Map.Entry<String, Dirstate.Entry> e : repository.getDirstate().getEntries().entrySet()) {
                    if (e.getValue().getState() != 'r') {
                        tracked.add(e.getKey());
                    }
                }
            } else {
                tracked = new HashSet<>(manifest().keySet());
            }
        }
        return tracked;
    }

    @Override
    public boolean contains(String path) throws IOException {
        return trackedFiles().contains(path);
    }

    private Path disk(String path) {
        return new File(repository.getDirectory(), path).toPath();
    }

    @Override
    public String flags(String path) throws IOException {
        if (isWorkingDirectory()) {
            Path p = disk(path);
            if (Files.isSymbolicLink(p)) {
                return "l";
            }
            if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(p)) {
                return "x";
            }
            return "";
        }
        String entry = manifest().get(path);
        if (entry == null) {
            return "";
        }
        String flags = entry.length() > 40 ? entry.substring(40) : "";
        return flags.contains("l") ? "l" : flags.contains("x") ? "x" : "";
    }

    @Override
    public byte[] data(String path) throws IOException {
        if (isWorkingDirectory()) {
            Path p = disk(path);
            if (Files.isSymbolicLink(p)) {
                return Files.readSymbolicLink(p).toString().getBytes(StandardCharsets.UTF_8);
            }
            return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
        }
        if (!manifest().containsKey(path)) {
            return null;
        }
        return new CatCommand(repository).setFile(path).setRevision(revision).call();
    }

    @Override
    public long size(String path) throws IOException {
        if (isWorkingDirectory()) {
            Path p = disk(path);
            if (Files.isSymbolicLink(p)) {
                return Files.readSymbolicLink(p).toString().getBytes(StandardCharsets.UTF_8).length;
            }
            return Files.isRegularFile(p) ? Files.size(p) : -1;
        }
        byte[] data = data(path);
        return data == null ? -1 : data.length;
    }

    @Override
    public String copySource(String path) throws IOException {
        if (isWorkingDirectory()) {
            return repository.getDirstate().getCopyMap().get(path);
        }
        String entry = manifest().get(path);
        if (entry == null) {
            return null;
        }
        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        if (!flIdx.exists()) {
            return null;
        }
        Revlog filelog = repository.getRevlog(flIdx, flDat);
        int fileRev = filelog.findRevision(NodeIdUtil.fromHex(entry.substring(0, 40)));
        if (fileRev < 0 || filelog.getIndexRecord(fileRev).getParent1() >= 0) {
            return null; // a copy is recorded only on a revision without first parent
        }
        String copy = filelog.getRevisionMetadata(fileRev).get("copy");
        return copy == null || copy.isEmpty() ? null : copy;
    }

    // ---------------------------------------------------------------- relatives

    @Override
    public FilesetContext p1() throws IOException {
        if (isNull()) {
            return this;
        }
        if (isWorkingDirectory()) {
            byte[] parent = repository.getDirstate().getParent1();
            return NodeIdUtil.isAllZero(parent) ? new RepositoryFilesetContext(repository, NULL_REVISION)
                    : new RepositoryFilesetContext(repository, NodeIdUtil.toHex(parent));
        }
        int p1 = changelog().getIndexRecord(rev()).getParent1();
        return p1 < 0 ? new RepositoryFilesetContext(repository, NULL_REVISION)
                : new RepositoryFilesetContext(repository, NodeIdUtil.toHex(changelog().getIndexRecord(p1).getNodeId()));
    }

    /**
     * {@code scmutil.revrange} for the specs this implementation can read: a revision (number, hash, {@code .}, {@code tip}, a tag,
     * bookmark or branch name), a range {@code a:b} of two revisions (either may be left out), and what
     * {@link HgRevsetEngine} understands ({@code all()}, {@code none()}, {@code branch(x)}, ...). Anything else is refused.
     */
    @Override
    public List<FilesetContext> revisions(String revset) throws IOException {
        String spec = revset.strip();
        List<Integer> revs;
        int colon = spec.indexOf(':');
        if (colon >= 0 && spec.indexOf('(') < 0 && spec.indexOf(':', colon + 1) < 0) {
            String left = spec.substring(0, colon).strip();
            String right = spec.substring(colon + 1).strip();
            int from = left.isEmpty() ? 0 : singleRevision(left);
            int to = right.isEmpty() ? changelog().getRevisionCount() - 1 : singleRevision(right);
            revs = new ArrayList<>();
            if (from <= to) {
                for (int r = from; r <= to; r++) {
                    revs.add(r);
                }
            } else {
                for (int r = from; r >= to; r--) {
                    revs.add(r);
                }
            }
        } else if (spec.indexOf('(') < 0) {
            revs = List.of(singleRevision(spec));
        } else {
            revs = new HgRevsetEngine(repository).query(spec);
        }
        List<FilesetContext> contexts = new ArrayList<>();
        for (int r : revs) {
            contexts.add(new RepositoryFilesetContext(repository, NodeIdUtil.toHex(changelog().getIndexRecord(r).getNodeId())));
        }
        return contexts;
    }

    private int singleRevision(String spec) throws IOException {
        if (spec.equals(".")) {
            byte[] parent = repository.getDirstate().getParent1();
            if (!NodeIdUtil.isAllZero(parent)) {
                return changelog().findRevision(parent);
            }
        }
        byte[] node = changelog().getRevisionCount() == 0 ? null : NodeIdUtil.resolveRevision(changelog(), spec);
        int rev = node == null ? -1 : changelog().findRevision(node);
        if (rev < 0) {
            throw new io.github.search5.hg4j.errors.HgValidationException("unknown revision '" + spec + "'");
        }
        return rev;
    }

    // ---------------------------------------------------------------- status

    @Override
    public FileStatus status(FilesetContext target, Matcher match, boolean listIgnored, boolean listClean, boolean listUnknown)
            throws IOException {
        RepositoryFilesetContext other = (RepositoryFilesetContext) target;
        Set<String> modified = new TreeSet<>();
        Set<String> added = new TreeSet<>();
        Set<String> removed = new TreeSet<>();
        Set<String> deleted = new TreeSet<>();
        Set<String> unknown = new TreeSet<>();
        Set<String> ignored = new TreeSet<>();
        Set<String> clean = new TreeSet<>();
        if (other.isWorkingDirectory()) {
            workingStatus(other, modified, added, removed, deleted, unknown, ignored, clean, listIgnored, listUnknown);
        } else {
            Map<String, String> from = isNull() ? Map.of() : manifest();
            Map<String, String> to = other.isNull() ? Map.of() : other.manifest();
            for (Map.Entry<String, String> e : to.entrySet()) {
                String before = from.get(e.getKey());
                if (before == null) {
                    added.add(e.getKey());
                } else if (!before.equals(e.getValue())) {
                    modified.add(e.getKey());
                } else {
                    clean.add(e.getKey());
                }
            }
            for (String path : from.keySet()) {
                if (!to.containsKey(path)) {
                    removed.add(path);
                }
            }
        }
        FileStatus all = new FileStatus(modified, added, removed, deleted, unknown, ignored, listClean ? clean : Set.of());
        return filter(all, match);
    }

    private static FileStatus filter(FileStatus s, Matcher match) {
        if (match == null || match.always()) {
            return s;
        }
        return new FileStatus(keep(s.modified(), match), keep(s.added(), match), keep(s.removed(), match), keep(s.deleted(), match),
                keep(s.unknown(), match), keep(s.ignored(), match), keep(s.clean(), match));
    }

    private static Set<String> keep(Set<String> files, Matcher match) {
        Set<String> out = new LinkedHashSet<>();
        for (String f : files) {
            if (match.matches(f)) {
                out.add(f);
            }
        }
        return out;
    }

    /** {@code dirstate.status}: the working directory against its first parent. */
    private void workingStatus(RepositoryFilesetContext wd, Set<String> modified, Set<String> added, Set<String> removed,
                               Set<String> deleted, Set<String> unknown, Set<String> ignored, Set<String> clean,
                               boolean listIgnored, boolean listUnknown) throws IOException {
        Status st = new StatusCommand(repository).call();
        Map<String, Dirstate.Entry> entries = repository.getDirstate().getEntries();
        for (String path : st.getAdded()) {
            (Files.exists(disk(path), LinkOption.NOFOLLOW_LINKS) ? added : deleted).add(path);
        }
        modified.addAll(st.getModified());
        clean.addAll(st.getClean());
        for (String path : st.getRemoved()) {
            Dirstate.Entry e = entries.get(path);
            if (e != null && e.getState() == 'r') {
                removed.add(path);
            } else {
                deleted.add(path); // tracked and gone from the disk without `hg rm`
            }
        }
        if (listUnknown || listIgnored) {
            Matcher ignore = ignoreMatcher();
            Set<String> subrepos = repository.loadSubrepoPaths();
            walk(repository.getDirectory().toPath(), "", entries, ignore, subrepos, unknown, ignored);
            if (!listUnknown) {
                unknown.clear();
            }
            if (!listIgnored) {
                ignored.clear();
            }
        }
    }

    private void walk(Path dir, String prefix, Map<String, Dirstate.Entry> tracked, Matcher ignore, Set<String> subrepos,
                      Set<String> unknown, Set<String> ignored) throws IOException {
        File[] children = dir.toFile().listFiles();
        if (children == null) {
            return;
        }
        java.util.Arrays.sort(children);
        for (File child : children) {
            String name = child.getName();
            String path = prefix.isEmpty() ? name : prefix + "/" + name;
            if (name.equals(".hg")) {
                continue;
            }
            boolean symlink = Files.isSymbolicLink(child.toPath());
            if (child.isDirectory() && !symlink) {
                if (subrepos.contains(path) || new File(child, ".hg").isDirectory()) {
                    continue; // a subrepository or a nested repository is opaque
                }
                walk(child.toPath(), path, tracked, ignore, subrepos, unknown, ignored);
            } else if (!tracked.containsKey(path)) {
                (ignore.matches(path) || ignoredByParent(ignore, path) ? ignored : unknown).add(path);
            }
        }
    }

    private static boolean ignoredByParent(Matcher ignore, String path) {
        for (int i = path.indexOf('/'); i > 0; i = path.indexOf('/', i + 1)) {
            if (ignore.matches(path.substring(0, i))) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- the rest

    @Override
    public String mergeState(String path) throws IOException {
        if (!isWorkingDirectory()) {
            return null;
        }
        MergeState ms = MergeState.read(new File(repository.getHgDir(), "merge/state2"));
        List<String> fields = ms.state.get(path);
        return fields == null || fields.isEmpty() ? null : fields.get(0);
    }

    @Override
    public Matcher ignoreMatcher() throws IOException {
        List<String> files = new ArrayList<>();
        if (new File(repository.getDirectory(), ".hgignore").isFile()) {
            files.add(".hgignore");
        }
        for (Map.Entry<String, String> e : repository.getConfig().getSection("ui").entrySet()) {
            if (e.getKey().equals("ignore") || e.getKey().startsWith("ignore.")) {
                files.add(e.getValue());
            }
        }
        if (files.isEmpty()) {
            return Matchers.never();
        }
        List<String> patterns = new ArrayList<>();
        files.forEach(f -> patterns.add("include:" + f));
        return Matchers.match(repository.getDirectory(), "", List.of(), patterns, List.of(), "glob", null, null);
    }

    @Override
    public Set<String> subrepos() throws IOException {
        if (isWorkingDirectory()) {
            return new TreeSet<>(repository.loadSubrepoPaths());
        }
        byte[] hgsub = data(".hgsub");
        Set<String> paths = new TreeSet<>();
        if (hgsub != null) {
            for (String line : new String(hgsub, StandardCharsets.UTF_8).split("\n")) {
                String t = line.strip();
                int eq = t.indexOf('=');
                if (!t.isEmpty() && !t.startsWith("#") && !t.startsWith("[") && eq > 0) {
                    paths.add(t.substring(0, eq).strip());
                }
            }
        }
        return paths;
    }

    @Override
    public Set<String> lfsFiles() throws IOException {
        if (isWorkingDirectory() || isNull()) {
            return Set.of();
        }
        return new HashSet<>(io.github.search5.hg4j.lfs.HgLfsFiles.inManifest(repository, rev()).keySet());
    }
}
