package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lfs.HgLfsFactory;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.match.Matcher;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code hg copy --at-rev REV --after SRC DEST} and {@code hg copy --forget --at-rev REV DEST} (also for {@code rename}): the copy is
 * recorded in (or removed from) a changeset that already exists. The changeset is replaced by a copy of itself whose file revision
 * of DEST carries (or lacks) the copy information (real hg: {@code new_ctx.markcopied(...)}, {@code tomemctx_for_amend}), the old one
 * is stripped, and the working copy parent, bookmarks and phases follow.
 *
 * <p>Locks as in {@code commands.copy}/{@code cmdutil.copy}: the working copy lock first, then the store lock around the checks and the
 * rewrite ({@code rewriteutil.precheck} has to run after the lock is taken).</p>
 */
final class CopyAtRev {

    private CopyAtRev() {
    }

    static CopyEngine.Result execute(HgRepository repo, boolean rename, List<String> patterns, String atRev, boolean after,
                                     boolean forget, List<String> include, List<String> exclude, String cwd)
            throws IOException, HgLockException {
        if (!forget && !after) {
            throw new HgValidationException("--at-rev requires --after");
        }
        if (repo.isTreemanifest() || repo.isSidedataCopies()) {
            throw new HgValidationException("--at-rev is not supported for treemanifest or changeset-centric copy repositories");
        }
        try (HgLock wlock = repo.lockWorkingCopy(); HgLock storeLock = repo.lockStore()) {
            return new Run(repo, patterns, atRev, after, forget, include, exclude, cwd == null ? "" : cwd).run();
        }
    }

    private static final class Run {
        private final HgRepository repo;
        private final List<String> patterns;
        private final String atRev;
        private final boolean after;
        private final boolean forget;
        private final List<String> include;
        private final List<String> exclude;
        private final String cwd;
        private final List<String> warnings = new ArrayList<>();
        private final List<String> status = new ArrayList<>();
        private Revlog changelog;
        private int rev;
        private ChangesetRewrite.Entry entry;
        private Map<String, String> manifest;
        private Map<String, String> p1Manifest = new java.util.HashMap<>();
        private byte[] p1Node;
        private byte[] p2Node;

        Run(HgRepository repo, List<String> patterns, String atRev, boolean after, boolean forget, List<String> include,
            List<String> exclude, String cwd) {
            this.repo = repo;
            this.patterns = patterns;
            this.atRev = atRev;
            this.after = after;
            this.forget = forget;
            this.include = include;
            this.exclude = exclude;
            this.cwd = cwd;
        }

        private String shortHex(byte[] node) {
            return NodeIdUtil.toHex(node).substring(0, 12);
        }

        private String pathTo(String abs) {
            return cwd.isEmpty() ? abs : java.nio.file.Paths.get(cwd).relativize(java.nio.file.Paths.get(abs)).toString();
        }

        private String canon(String name) throws HgValidationException {
            java.nio.file.Path joined = cwd.isEmpty() ? java.nio.file.Paths.get(name) : java.nio.file.Paths.get(cwd).resolve(name);
            String result = joined.normalize().toString().replace(File.separatorChar, '/');
            if (result.equals("..") || result.startsWith("../")) {
                throw new HgValidationException(name + " not under root '" + repo.getDirectory() + "'");
            }
            return result;
        }

        private Matcher matcher(String revisionHex, List<String> pats) throws IOException {
            FileSelection selection = new FileSelection().patterns(pats.toArray(String[]::new)).defaultKind("relpath").cwd(cwd);
            if (include != null && !include.isEmpty()) {
                selection.include(include.toArray(String[]::new));
            }
            if (exclude != null && !exclude.isEmpty()) {
                selection.exclude(exclude.toArray(String[]::new));
            }
            return selection.matcher(repo, revisionHex);
        }

        CopyEngine.Result run() throws IOException, HgLockException {
            changelog = repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
            rev = resolve(atRev);
            Revlog.IndexRecord record = changelog.getIndexRecord(rev);
            if (record.getParent1() >= 0 && record.getParent2() >= 0) {
                throw new HgValidationException("cannot mark/unmark copy in merge commit");
            }
            byte[] node = record.getNodeId();
            entry = ChangesetRewrite.read(changelog, rev);
            manifest = repo.getManifestAtCommit(node);
            p1Node = record.getParent1() >= 0 ? changelog.getIndexRecord(record.getParent1()).getNodeId() : new byte[20];
            p2Node = new byte[20];
            if (record.getParent1() >= 0) {
                p1Manifest = repo.getManifestAtCommit(p1Node);
            }
            List<String> pats = new ArrayList<>(patterns);
            if (!forget) {
                if (pats.isEmpty()) {
                    throw new HgValidationException("no source or destination specified");
                }
                if (pats.size() == 1) {
                    throw new HgValidationException("no destination specified");
                }
            } else if (pats.isEmpty()) {
                throw new HgValidationException("no files specified");
            }
            ChangesetRewrite.precheck(repo, changelog, List.of(rev), "uncopy");
            Map<String, String> changed;
            if (forget) {
                changed = unmark(pats, NodeIdUtil.toHex(node));
            } else {
                String dest = pats.remove(pats.size() - 1);
                changed = mark(pats, dest, node);
            }
            ChangesetRewrite.Entry rewritten = entry;
            // the files list of the new changeset: the old one, plus the files whose file revision changed
            java.util.Set<String> files = new java.util.TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
            files.addAll(entry.files);
            files.addAll(changed.keySet());
            rewritten.files = new ArrayList<>(files);
            Map<String, String> newManifest = new TreeMap<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
            newManifest.putAll(manifest);
            newManifest.putAll(changed);
            rewritten.manifestHex = appendManifest(record, rev, newManifest);
            rewritten.extra.put("amend_source", NodeIdUtil.toHex(node));
            byte[] newNode = ChangesetRewrite.append(changelog, rewritten, p1Node, p2Node);
            repo.clearRevlogCache();
            ChangesetRewrite.moveWorkingCopyParent(repo, node, newNode);
            Map<String, String> replacements = new LinkedHashMap<>();
            replacements.put(NodeIdUtil.toHex(node), NodeIdUtil.toHex(newNode));
            for (File backup : ChangesetRewrite.cleanupNodes(repo, replacements, forget ? "uncopy" : "copy")) {
                status.add("saved backup bundle to " + backup.getAbsolutePath());
            }
            return new CopyEngine.Result(false, warnings, status);
        }

        private int resolve(String revision) throws IOException {
            return ChangesetRewrite.revision(repo, changelog, revision);
        }

        /** {@code --after}: DEST of the changeset gets the copy information of SRC (a file of the parent). */
        private Map<String, String> mark(List<String> sources, String dest, byte[] node) throws IOException {
            String absDest = canon(dest);
            String prefix = absDest.isEmpty() ? "" : absDest + "/";
            for (String path : manifest.keySet()) {
                if (!prefix.isEmpty() && path.startsWith(prefix)) {
                    throw new HgValidationException(pathTo(absDest) + ": --at-rev does not support a directory as destination");
                }
            }
            if (!manifest.containsKey(absDest)) {
                throw new HgValidationException(pathTo(absDest) + ": copy destination does not exist in " + shortHex(node));
            }
            List<String> copylist = new ArrayList<>();
            for (String pat : sources) {
                Matcher m = matcher(p1Node == null || NodeIdUtil.isAllZero(p1Node) ? null : NodeIdUtil.toHex(p1Node), List.of(pat));
                for (String named : m.files()) {
                    boolean isFile = p1Manifest.containsKey(named);
                    boolean isDir = false;
                    for (String path : p1Manifest.keySet()) {
                        isDir |= path.startsWith(named + "/");
                    }
                    if (!named.isEmpty() && !isFile && !isDir) {
                        warnings.add(pathTo(named) + ": no such file in rev " + shortHex(p1Node));
                    }
                }
                for (String abs : new TreeMap<>(p1Manifest).keySet()) {
                    if (m.matches(abs)) {
                        copylist.add(abs);
                    }
                }
            }
            if (copylist.isEmpty()) {
                throw new HgValidationException("no files to copy");
            }
            if (copylist.size() != 1) {
                throw new HgValidationException("--at-rev requires a single source");
            }
            String src = copylist.get(0);
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("copy", src);
            meta.put("copyrev", p1Manifest.get(src).substring(0, 40));
            return Map.of(absDest, rewriteFileRevision(absDest, meta, false));
        }

        /** {@code --forget}: the copy information of the matching files is removed. */
        private Map<String, String> unmark(List<String> pats, String nodeHex) throws IOException {
            Matcher m = matcher(nodeHex, pats);
            Map<String, String> changed = new TreeMap<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
            for (String abs : new TreeMap<>(manifest).keySet()) {
                if (!m.matches(abs)) {
                    continue;
                }
                if (copySource(abs) != null) {
                    changed.put(abs, rewriteFileRevision(abs, null, true));
                } else if (m.exact(abs)) {
                    warnings.add(pathTo(abs) + ": not unmarking as copy - file is not marked as copied");
                }
            }
            return changed;
        }

        private String copySource(String abs) throws IOException {
            Revlog filelog = filelog(abs);
            int fileRev = filelog.findRevision(NodeIdUtil.fromHex(manifest.get(abs).substring(0, 40)));
            return fileRev < 0 ? null : filelog.getRevisionMetadata(fileRev).get("copy");
        }

        private Revlog filelog(String abs) throws IOException {
            File index = CommitCommand.getFilelogIndex(repo.getStoreDir(), abs);
            File data = new File(index.getPath().substring(0, index.getPath().length() - 2) + ".d");
            return repo.getRevlog(index, data);
        }

        /** The same content as the changeset's file revision, with copy information {@code meta} (or none), as a new file revision. */
        private String rewriteFileRevision(String abs, Map<String, String> meta, boolean keepParent) throws IOException {
            String oldEntry = manifest.get(abs);
            String flags = oldEntry.substring(40);
            Revlog filelog = filelog(abs);
            int oldFileRev = filelog.findRevision(NodeIdUtil.fromHex(oldEntry.substring(0, 40)));
            byte[] content = HgLfsFactory.getInstance().readContent(repo, abs, filelog, oldFileRev);
            // filectx rules (localrepo._filecommit): a copy has the null node as first parent; otherwise the first parent is the
            // file revision of the parent changeset, if there is one
            byte[] fp1 = new byte[20];
            int fp1Rev = -1;
            if (meta == null && p1Manifest.containsKey(abs)) {
                fp1 = NodeIdUtil.fromHex(p1Manifest.get(abs).substring(0, 40));
                fp1Rev = filelog.findRevision(fp1);
            }
            byte[] candidate = NodeIdUtil.computeNodeId(Revlog.wrapMetadata(content, meta), fp1, new byte[20]);
            byte[] node = filelog.findRevision(candidate) >= 0 ? candidate
                    : filelog.appendRevision(content, meta, fp1Rev, -1, fp1, new byte[20], changelog.getRevisionCount());
            return NodeIdUtil.toHex(node) + flags;
        }

        private String appendManifest(Revlog.IndexRecord record, int changesetRev, Map<String, String> newManifest) throws IOException {
            Revlog manifestRevlog = repo.getManifestRevlog();
            ChangesetRewrite.Entry old = entry;
            int oldManifestRev = manifestRevlog.findRevision(NodeIdUtil.fromHex(old.manifestHex));
            Revlog.IndexRecord m = manifestRevlog.getIndexRecord(oldManifestRev);
            StringBuilder text = new StringBuilder();
            for (Map.Entry<String, String> e : newManifest.entrySet()) {
                text.append(e.getKey()).append('\0').append(e.getValue()).append('\n');
            }
            byte[] p1 = m.getParent1() >= 0 ? manifestRevlog.getIndexRecord(m.getParent1()).getNodeId() : new byte[20];
            byte[] p2 = m.getParent2() >= 0 ? manifestRevlog.getIndexRecord(m.getParent2()).getNodeId() : new byte[20];
            byte[] node = manifestRevlog.appendRevision(text.toString().getBytes(StandardCharsets.UTF_8), m.getParent1(), m.getParent2(),
                    p1, p2, changelog.getRevisionCount());
            return NodeIdUtil.toHex(node);
        }
    }
}
