package io.github.search5.hg4j.api;

import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.errors.HgLockException;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import io.github.search5.hg4j.errors.HgMergeConflictException;
import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.treewalk.ManifestWalk;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Porcelain command to shelve and unshelve local working copy changes.
 * Supports saving modified, added, and removed files and restoring them with full dirstate fidelity.
 *
 * <p>Since 2026-09-04, {@link #performUnshelve} follows real hg's own {@code _dounshelve()}
 * algorithm (mercurial/shelve.py) instead of a simple diff-replay: the shelved changegroup is
 * first restored as a real (throwaway) commit on top of the parent it was originally shelved
 * from, that commit is rebased -- via {@link RebaseCommand}, reusing its 3-way-merge conflict
 * detection rather than reimplementing one here -- onto whatever the working directory's parent
 * actually is now (a no-op when nothing has landed there since the shelve), and the result is
 * finally "uncommitted" back onto the working copy as pending changes while every trace of the
 * throwaway commit(s) is erased (verified against real hg CLI, 2026-09-04: they are NOT left
 * behind even as hidden/obsolete revisions -- real hg builds them inside a transaction it then
 * aborts, which is why this class strips them outright rather than leaving an evolution marker
 * the way {@link RebaseCommand} normally would for a user-visible rebase). A conflict during that
 * rebase step pauses exactly like a real {@code hg rebase} would; see {@link #unshelveContinue()}
 * and {@link #unshelveAbort()}.
 */
public class ShelveCommand {

    private final HgRepository repository;
    private String name = "default";
    private boolean unshelve = false;

    public ShelveCommand(HgRepository repository) {
        this.repository = repository;
    }

    public ShelveCommand setName(String name) {
        if (name != null && !name.isEmpty()) {
            this.name = name;
        }
        return this;
    }

    public ShelveCommand setUnshelve(boolean unshelve) {
        this.unshelve = unshelve;
        return this;
    }

    public void call() throws IOException, HgLockException, HgMergeConflictException {
        File shelvedDir = new File(repository.getHgDir(), "shelved");
        shelvedDir.mkdirs();
        File stateFile = new File(shelvedDir, name + ".state");

        if (unshelve) {
            performUnshelve(stateFile);
        } else {
            performShelve(stateFile);
        }
    }

    private byte[] getBaselineContent(String path) throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");

        if (!clIdx.exists()) {
            return null;
        }

        Revlog changelog = repository.getRevlog(clIdx, clDat);
        byte[] p1 = repository.getDirstate().getParent1();
        int lastRev = changelog.findRevision(p1);
        if (lastRev == -1) {
            lastRev = changelog.getRevisionCount() - 1;
        }
        if (lastRev < 0) {
            return null;
        }

        ManifestWalk mw = new ManifestWalk(repository, String.valueOf(lastRev));
        while (mw.next()) {
            ManifestWalk.Entry entry = mw.getEntry();
            if (entry.getPath().equals(path)) {
                byte[] nodeId = entry.getNodeId();
                File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
                File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                if (!flIdx.exists()) {
                    return null;
                }
                Revlog filelog = repository.getRevlog(flIdx, flDat);
                int fileRev = NodeIdUtil.findRevisionByNodeId(filelog, nodeId);
                if (fileRev == -1) {
                    return null;
                }
                return filelog.getRevisionContent(fileRev);
            }
        }
        return null;
    }

    private String generateDiff(String path, char state, byte[] content, String parentHex) {
        StringBuilder sb = new StringBuilder();
        sb.append("diff -r ").append(parentHex).append(" ").append(path).append("\n");
        if (state == 'a') {
            sb.append("--- /dev/null\n");
            sb.append("+++ b/").append(path).append("\n");
            String text = new String(content, StandardCharsets.UTF_8);
            String[] lines = text.split("\r?\n", -1);
            int lineCount = lines.length;
            if (lineCount > 0 && lines[lineCount - 1].isEmpty()) {
                lineCount--;
            }
            sb.append("@@ -0,0 +1,").append(lineCount).append(" @@\n");
            for (int i = 0; i < lineCount; i++) {
                sb.append("+").append(lines[i]).append("\n");
            }
        } else if (state == 'r') {
            sb.append("--- a/").append(path).append("\n");
            sb.append("+++ /dev/null\n");
            byte[] baseBytes;
            try {
                baseBytes = getBaselineContent(path);
            } catch (Exception e) {
                baseBytes = new byte[0];
            }
            if (baseBytes == null) baseBytes = new byte[0];
            String text = new String(baseBytes, StandardCharsets.UTF_8);
            String[] lines = text.split("\r?\n", -1);
            int lineCount = lines.length;
            if (lineCount > 0 && lines[lineCount - 1].isEmpty()) {
                lineCount--;
            }
            sb.append("@@ -1,").append(lineCount).append(" +0,0 @@\n");
            for (int i = 0; i < lineCount; i++) {
                sb.append("-").append(lines[i]).append("\n");
            }
        } else { // 'm' or 'n'
            sb.append("--- a/").append(path).append("\n");
            sb.append("+++ b/").append(path).append("\n");
            byte[] baseBytes;
            try {
                baseBytes = getBaselineContent(path);
            } catch (Exception e) {
                baseBytes = new byte[0];
            }
            if (baseBytes == null) baseBytes = new byte[0];

            String baseText = new String(baseBytes, StandardCharsets.UTF_8);
            String newText = new String(content, StandardCharsets.UTF_8);
            String[] baseLines = baseText.split("\r?\n", -1);
            String[] newLines = newText.split("\r?\n", -1);

            int baseLen = baseLines.length;
            if (baseLen > 0 && baseLines[baseLen - 1].isEmpty()) baseLen--;
            int newLen = newLines.length;
            if (newLen > 0 && newLines[newLen - 1].isEmpty()) newLen--;

            sb.append("@@ -1,").append(baseLen).append(" +1,").append(newLen).append(" @@\n");
            for (int i = 0; i < baseLen; i++) {
                sb.append("-").append(baseLines[i]).append("\n");
            }
            for (int i = 0; i < newLen; i++) {
                sb.append("+").append(newLines[i]).append("\n");
            }
        }
        return sb.toString();
    }

    private void writeEntryChunk(DataOutputStream dos, ChangegroupParser.ChangeGroupEntry entry) throws IOException {
        int totalLen = 4 + 80 + entry.delta.length;
        dos.writeInt(totalLen);
        dos.write(entry.node);
        dos.write(entry.p1);
        dos.write(entry.p2);
        dos.write(entry.cs);
        dos.write(entry.delta);
    }

    private void writePathChunk(DataOutputStream dos, String path) throws IOException {
        byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
        int totalLen = 4 + pathBytes.length;
        dos.writeInt(totalLen);
        dos.write(pathBytes);
    }

    private void writeTerminalChunk(DataOutputStream dos) throws IOException {
        dos.writeInt(0);
    }

    private void performShelve(File stateFile) throws IOException, HgLockException {
        File shelvedDir = stateFile.getParentFile();
        File patchFile = new File(shelvedDir, name + ".patch");
        File hgBundleFile = new File(shelvedDir, name + ".hg");

        try (HgLock wlock = repository.lockWorkingCopy();
             HgLock storeLock = repository.lockStore()) {

            Dirstate dirstate = repository.getDirstate();
            Map<String, Dirstate.Entry> entries = dirstate.getEntries();

            List<ShelvedFile> shelvedFiles = new ArrayList<>();

            for (Map.Entry<String, Dirstate.Entry> item : entries.entrySet()) {
                String path = item.getKey();
                Dirstate.Entry entry = item.getValue();

                if (entry.getState() == 'm' || entry.getState() == 'a') {
                    File file = new File(repository.getDirectory(), path);
                    boolean isSym = Files.isSymbolicLink(file.toPath());
                    if (file.exists() || isSym) {
                        byte[] content;
                        if (isSym) {
                            content = Files.readSymbolicLink(file.toPath()).toString().getBytes(StandardCharsets.UTF_8);
                        } else {
                            content = Files.readAllBytes(file.toPath());
                        }
                        // Bug fix: dirstate's mode field for a symlink is written by
                        // AddCommand/CommitCommand as a plain 0644/0755 (derived from
                        // File.canExecute(), which follows the link), never the 0120000
                        // symlink sentinel that performUnshelve()/revertToLatestCommit()
                        // check for. Trusting entry.getMode() here silently shelved
                        // symlinks as plain files, so unshelve recreated them as a regular
                        // file containing the target path as text instead of a real
                        // symlink. Derive the mode from the on-disk link type we already
                        // detected instead.
                        int mode = isSym ? 0120000 : entry.getMode();
                        shelvedFiles.add(new ShelvedFile(path, entry.getState(), content, mode));
                    }
                } else if (entry.getState() == 'r') {
                    shelvedFiles.add(new ShelvedFile(path, 'r', new byte[0], entry.getMode()));
                } else if (entry.getState() == 'n') {
                    // Check if modified on disk without being added (uncommitted modification)
                    File file = new File(repository.getDirectory(), path);
                    boolean isSym = Files.isSymbolicLink(file.toPath());
                    if (file.exists() || isSym) {
                        long diskSize = isSym ? Files.readSymbolicLink(file.toPath()).toString().getBytes(StandardCharsets.UTF_8).length : file.length();
                        long diskTime = SafeFileIO.lastModifiedSeconds(file);
                        byte[] content = isSym
                                ? Files.readSymbolicLink(file.toPath()).toString().getBytes(StandardCharsets.UTF_8)
                                : Files.readAllBytes(file.toPath());
                        boolean modified;
                        if (entry.getSize() != diskSize || entry.getTime() != diskTime) {
                            modified = true;
                        } else {
                            // Racy-write guard: a same-second edit that happens to keep the
                            // exact same byte size is indistinguishable from "unchanged" by
                            // size/mtime alone (mirrors StatusCommand's racy-modified check),
                            // so fall back to comparing against the last committed content.
                            byte[] baseline = getBaselineContent(path);
                            modified = baseline == null || !Arrays.equals(content, baseline);
                        }
                        if (modified) {
                            // Same symlink-mode fix as above for the 'a'/'m' branch.
                            int mode = isSym ? 0120000 : entry.getMode();
                            shelvedFiles.add(new ShelvedFile(path, 'n', content, mode));
                        }
                    }
                }
            }

            if (shelvedFiles.isEmpty()) {
                return; // Nothing to shelve
            }

            // Get parent nodes for standard metadata
            byte[] p1 = dirstate.getParent1();
            byte[] p2 = dirstate.getParent2();
            String p1Hex = NodeIdUtil.toHex(p1);
            String p2Hex = NodeIdUtil.toHex(p2);

            // 1. Write standard .patch file
            StringBuilder patchSb = new StringBuilder();
            patchSb.append("# HG changeset patch\n");
            patchSb.append("# User hg4j <hg4j@example.com>\n");
            patchSb.append("# Date ").append(System.currentTimeMillis() / 1000).append(" 0\n");
            patchSb.append("# Parent ").append(p1Hex).append("\n");
            patchSb.append("shelve: ").append(name).append("\n\n");

            for (ShelvedFile sf : shelvedFiles) {
                patchSb.append(generateDiff(sf.path, sf.state, sf.content, p1Hex));
            }

            Files.writeString(patchFile.toPath(), patchSb.toString(), StandardCharsets.UTF_8);

            // 2. Commit temporary revision to capture exact working copy delta
            CommitCommand commitCmd = new CommitCommand(repository)
                    .setAuthor("hg4j <hg4j@example.com>")
                    .setMessage("[shelve] " + name)
                    .setSkipLockAndJournal(true);
            
            byte[] tempCommitNode = commitCmd.call();

            // 3. Construct and write native .hg binary bundle from the temporary commit
            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");

            Revlog cl = repository.getRevlog(clIdx, clDat);
            int tempRev = cl.findRevision(tempCommitNode);
            if (tempRev == -1) {
                throw new HgRevisionNotFoundException("Failed to resolve temporary shelve commit.");
            }

            ChangegroupParser.ChangegroupBundle bundle = new ChangegroupParser.ChangegroupBundle();
            bundle.changelogEntries = new ArrayList<>();
            bundle.manifestEntries = new ArrayList<>();
            bundle.fileGroups = new ArrayList<>();

            // Real base content for each of the changelog/manifest/filelog deltas below --
            // real hg's cg1 changegroup applier (mercurial/changegroup.py cg1unpacker, invoked
            // by real hg's own `hg unbundle`/`hg unshelve`) reconstructs each entry's content by
            // applying its delta against the LOCAL repository's content for the entry's declared
            // p1 node (or empty, only when p1 truly is the null node) -- NOT unconditionally
            // against empty. An earlier version of this method always encoded "delta against
            // empty" regardless of the declared (non-null) p1, which only round-trips correctly
            // under hg4j's own performUnshelve() (which mirrored that same non-standard
            // convention): real hg's own unbundle/unshelve machinery would instead prepend the
            // "delta" onto the true p1 content rather than replace it, corrupting every modified
            // (as opposed to newly-added) shelved file. Confirmed against real hg CLI
            // (2026-09-03): a `.hg` shelve bundle built the old way could not be applied by real
            // `hg unshelve` at all. Mirrors real hg's own `writebundle()`/`makechangegroup()`.
            int p1CommitRev = cl.findRevision(p1);
            byte[] p1CommitContent = (p1CommitRev != -1) ? cl.getRevisionContent(p1CommitRev) : new byte[0];

            // Changelog entry
            Revlog.IndexRecord clRec = cl.getIndexRecord(tempRev);
            ChangegroupParser.ChangeGroupEntry clEntry = new ChangegroupParser.ChangeGroupEntry();
            clEntry.node = clRec.getNodeId();
            clEntry.p1 = p1;
            clEntry.p2 = p2;
            clEntry.cs = clRec.getNodeId();
            byte[] rawClContent = cl.getRevisionContent(tempRev);
            clEntry.delta = Revlog.createSimpleDelta(p1CommitContent, rawClContent);
            bundle.changelogEntries.add(clEntry);

            // Manifest entry
            String clText = new String(rawClContent, StandardCharsets.UTF_8);
            byte[] mfNode = NodeIdUtil.fromHex(clText.split("\n")[0].trim().substring(0, 40));
            Revlog mf = repository.getManifestRevlog();
            int mfRev = mf.findRevision(mfNode);
            Revlog.IndexRecord mfRec = mf.getIndexRecord(mfRev);

            ChangegroupParser.ChangeGroupEntry mfEntry = new ChangegroupParser.ChangeGroupEntry();
            mfEntry.node = mfRec.getNodeId();
            byte[] prevMfNode = new byte[20];
            byte[] p1MfContent = new byte[0];
            if (p1CommitRev != -1) {
                String p1ClText = new String(p1CommitContent, StandardCharsets.UTF_8);
                prevMfNode = NodeIdUtil.fromHex(p1ClText.split("\n")[0].trim().substring(0, 40));
                int prevMfRev = mf.findRevision(prevMfNode);
                if (prevMfRev != -1) {
                    p1MfContent = mf.getRevisionContent(prevMfRev);
                }
            }
            mfEntry.p1 = prevMfNode;
            mfEntry.p2 = new byte[20];
            mfEntry.cs = clRec.getNodeId();
            byte[] rawMfContent = mf.getRevisionContent(mfRev);
            mfEntry.delta = Revlog.createSimpleDelta(p1MfContent, rawMfContent);
            bundle.manifestEntries.add(mfEntry);

            // FileGroups entries
            for (ShelvedFile sf : shelvedFiles) {
                if (sf.state == 'r') {
                    continue;
                }

                File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), sf.path);
                File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                Revlog fl = repository.getRevlog(flIdx, flDat);
                int flRev = fl.getRevisionCount() - 1;

                Revlog.IndexRecord flRec = fl.getIndexRecord(flRev);

                ChangegroupParser.FileGroup fg = new ChangegroupParser.FileGroup();
                fg.path = sf.path;
                fg.entries = new ArrayList<>();

                ChangegroupParser.ChangeGroupEntry flEntry = new ChangegroupParser.ChangeGroupEntry();
                flEntry.node = flRec.getNodeId();
                byte[] prevFlNode = new byte[20];
                byte[] flBaseContent = new byte[0];
                if (flRev > 0) {
                    prevFlNode = fl.getIndexRecord(flRev - 1).getNodeId();
                    flBaseContent = fl.getRevisionContent(flRev - 1);
                }
                flEntry.p1 = prevFlNode;
                flEntry.p2 = new byte[20];
                flEntry.cs = clRec.getNodeId();
                flEntry.delta = Revlog.createSimpleDelta(flBaseContent, sf.content);

                fg.entries.add(flEntry);
                bundle.fileGroups.add(fg);
            }

            // Serialize the raw (cg1-style) changegroup bytes, then wrap them in a minimal
            // uncompressed HG20/bundle2 envelope (Bundle2Parser.wrapChangegroupInBundle2) so the
            // resulting `.hg` file is byte-for-byte a real Mercurial bundle real hg's own
            // `exchange.readbundle()`/`bundle2.getunbundler()` can load (verified against real hg
            // CLI 7.2.2, 2026-09-03) -- a bare, header-less dump of the raw entries (as this method
            // wrote before) is not recognized as a bundle at all by real hg. Real hg itself only
            // omits the HG20 envelope (using a bare "HG10BZ"-prefixed bundle1 stream instead) for
            // repositories whose changegroup.safeversion() is "01"; hg4j does not model that legacy
            // format distinction, so it always uses the HG20 envelope with cg version "01" here,
            // which real hg's bundle2 CHANGEGROUP part handler accepts regardless of local repo
            // format (it only requires the part's own `version` parameter, matching
            // Bundle2Parser.wrapChangegroupInBundle2's own javadoc).
            java.io.ByteArrayOutputStream rawCg = new java.io.ByteArrayOutputStream();
            try (DataOutputStream dos = new DataOutputStream(rawCg)) {
                // Changelog group
                for (ChangegroupParser.ChangeGroupEntry entry : bundle.changelogEntries) {
                    writeEntryChunk(dos, entry);
                }
                writeTerminalChunk(dos);

                // Manifest group
                for (ChangegroupParser.ChangeGroupEntry entry : bundle.manifestEntries) {
                    writeEntryChunk(dos, entry);
                }
                writeTerminalChunk(dos);

                // File groups
                for (ChangegroupParser.FileGroup fg : bundle.fileGroups) {
                    writePathChunk(dos, fg.path);
                    for (ChangegroupParser.ChangeGroupEntry entry : fg.entries) {
                        writeEntryChunk(dos, entry);
                    }
                    writeTerminalChunk(dos);
                }
                writeTerminalChunk(dos);
            }
            byte[] wrappedBundle = io.github.search5.hg4j.bundle.Bundle2Parser.wrapChangegroupInBundle2(rawCg.toByteArray(), "01");
            Files.write(hgBundleFile.toPath(), wrappedBundle);

            // Real hg's own shelve also writes a minimal "<name>.shelve" info file (a single
            // `node=<hex>` line, via `scmutil.simplekeyvaluefile`) alongside the `.hg` bundle --
            // verified against a real hg 7.2.2 `hg shelve` (2026-09-03). hg4j's own unshelve does
            // not need this file when its own richer `<name>.state` file is present (see
            // performUnshelve()), but writing it unconditionally lets a real hg CLI recognize and
            // process this shelve too (`hg unshelve`/`hg shelve --list` both read it), and lets
            // hg4j's own unshelve fall back to it when reading a shelve `.state` never wrote.
            File shelveInfoFile = new File(shelvedDir, name + ".shelve");
            Files.writeString(shelveInfoFile.toPath(), "node=" + NodeIdUtil.toHex(tempCommitNode) + "\n", StandardCharsets.UTF_8);

            // 4. Clean and strip the temporary commit immediately (Repository remains pure)
            stripRevisionsFrom(tempRev);
            repository.clearRevlogCache();

            // 5. Write metadata .state file
            StringBuilder stateSb = new StringBuilder();
            stateSb.append("# HG shelve state\n");
            stateSb.append(name).append("\n");
            stateSb.append(p1Hex).append("\n");
            stateSb.append(p2Hex).append("\n");
            stateSb.append(shelvedFiles.size()).append("\n");
            for (ShelvedFile sf : shelvedFiles) {
                stateSb.append(sf.path).append(" ").append(sf.state).append(" ").append(sf.mode).append("\n");
            }

            Files.writeString(stateFile.toPath(), stateSb.toString(), StandardCharsets.UTF_8);

            // Revert changes in working directory to clean state
            revertToLatestCommit(dirstate, shelvedFiles);
        }
    }

    private void performUnshelve(File stateFile) throws IOException, HgLockException, HgMergeConflictException {
        File shelvedDir = stateFile.getParentFile();
        File patchFile = new File(shelvedDir, name + ".patch");
        File hgBundleFile = new File(shelvedDir, name + ".hg");
        File shelveInfoFile = new File(shelvedDir, name + ".shelve");

        if (!hgBundleFile.exists()) {
            throw new HgRepositoryNotFoundException("Shelve file not found: " + name);
        }
        boolean hasState = stateFile.exists();
        if (!hasState && !shelveInfoFile.exists()) {
            throw new HgRepositoryNotFoundException("Shelve file not found: " + name);
        }

        // 실제 hg가 만든 shelve interop (백로그 23): `.hg` 번들은 이제 항상 HG20/bundle2
        // 봉투(우리 자신이 썼든, 실제 hg가 BZ2 압축까지 얹어 썼든 -- Bundle2Parser는 둘 다
        // 지원)이므로, 먼저 그 봉투를 벗겨 원시 changegroup 바이트 + 실제 cg 버전 파라미터를
        // 얻은 뒤에야 ChangegroupParser로 파싱한다. 버전을 명시하지 않고 자동 감지에 맡기면
        // 안 된다 -- 실제 hg 7.2가 기본으로 만드는 shelve 번들은 cg02/03(현대 리포용
        // changegroup.safeversion() 결과)이라, "01"로 잘못 감지되면 헤더 크기가 달라 p1/p2
        // 필드를 완전히 엉뚱한 오프셋에서 읽게 된다(2026-09-03 발견 -- real hg shelve를
        // hg4j로 unshelve하면 p1이 항상 null-node로 읽혀 즉시 파라미터 불일치로 실패했다).
        // hg4j가 예전에 쓰던 봉투 없는 원시 포맷과의 하위 호환을 위해 "HG20" 매직이 없으면
        // 원시 cg1 파싱으로 폴백한다.
        ChangegroupParser.ChangegroupBundle bundle;
        byte[] bundleFileBytes = Files.readAllBytes(hgBundleFile.toPath());
        if (bundleFileBytes.length >= 4 && bundleFileBytes[0] == 'H' && bundleFileBytes[1] == 'G'
                && bundleFileBytes[2] == '2' && bundleFileBytes[3] == '0') {
            io.github.search5.hg4j.bundle.Bundle2Parser.ExtractedBundle2 ext =
                    io.github.search5.hg4j.bundle.Bundle2Parser.extractChangegroupDetailed(
                            new java.io.ByteArrayInputStream(bundleFileBytes));
            bundle = ChangegroupParser.parseBundle(new java.io.ByteArrayInputStream(ext.changegroupBytes), ext.cgVersion);
        } else {
            bundle = ChangegroupParser.parseBundle(new java.io.ByteArrayInputStream(bundleFileBytes));
        }
        // Real hg's own shelve bundle is not always a single-commit changegroup: `writebundle()`
        // bundles the outgoing set for `mutableancestors(shelvectx)` (the shelved commit AND any
        // of its still-draft ancestors not otherwise known) -- for the common case of shelving
        // straight after a plain local (draft-phase) commit, that outgoing set is the shelved
        // commit AND its parent, i.e. TWO changelog/manifest entries (and, for any file the
        // parent commit itself touched, two filelog entries for that path too). Confirmed against
        // real hg CLI (2026-09-03): `bundle.changelogEntries.get(0)` is that PARENT commit, not the
        // shelved one -- picking it unconditionally silently reconstructed the wrong manifest/file
        // content. The shelved commit's own node is always resolvable via the `.shelve` info file
        // (real hg's `{'node': hex(node)}`, which performShelve() now also writes); hg4j's own
        // bundles only ever contain a single entry, so this resolves to it either way.
        byte[] shelveTargetNode = resolveShelveTargetNode(shelveInfoFile, bundle);
        ChangegroupParser.ChangeGroupEntry clEntry = findEntryByNode(bundle.changelogEntries, shelveTargetNode);
        ChangegroupParser.ChangeGroupEntry mfEntry = findEntryByChangeset(bundle.manifestEntries, shelveTargetNode);
        if (clEntry == null || mfEntry == null) {
            throw new HgValidationException("Cannot unshelve: shelved commit not found in bundle: " + name);
        }

        String p1Hex;
        String p2Hex;
        Map<String, Character> fileStates = new HashMap<>();
        Map<String, Integer> fileModes = new HashMap<>();

        if (hasState) {
            List<String> stateLines = Files.readAllLines(stateFile.toPath(), StandardCharsets.UTF_8);
            String shelveName = stateLines.get(1).trim();
            p1Hex = stateLines.get(2).trim();
            p2Hex = stateLines.get(3).trim();

            if (!shelveName.equals(name)) {
                throw new HgValidationException("Cannot unshelve: Shelve name mismatch. State file has '" + shelveName
                    + "' but expected '" + name + "'");
            }

            int shelvedFilesCount = Integer.parseInt(stateLines.get(4).trim());

            for (int i = 0; i < shelvedFilesCount; i++) {
                String line = stateLines.get(5 + i).trim();
                String[] tokens = line.split(" ");
                if (tokens.length >= 3) {
                    int mode = Integer.parseInt(tokens[tokens.length - 1]);
                    char state = tokens[tokens.length - 2].charAt(0);
                    StringBuilder pathSb = new StringBuilder();
                    for (int t = 0; t <= tokens.length - 3; t++) {
                        if (t > 0) pathSb.append(" ");
                        pathSb.append(tokens[t]);
                    }
                    String path = pathSb.toString();
                    fileStates.put(path, state);
                    fileModes.put(path, mode);
                } else {
                    int space = line.lastIndexOf(' ');
                    String path = line.substring(0, space);
                    char state = line.substring(space + 1).charAt(0);
                    fileStates.put(path, state);
                }
            }
        } else {
            // No hg4j-native `.state` file -- this shelve was made by real hg (or by a version
            // of hg4j predating this fix). Derive p1/p2 straight from the changegroup's own
            // changelog entry (always present, regardless of who produced the bundle), and
            // derive the per-file add/modify/remove/mode info by diffing the shelved commit's
            // manifest (decoded from the bundle's manifest entry) against the current parent's
            // manifest -- exactly the information hg4j's own `.state` file would otherwise supply.
            p1Hex = NodeIdUtil.toHex(clEntry.p1);
            p2Hex = NodeIdUtil.toHex(clEntry.p2);

            byte[] baseMfContent = resolveBaseManifestContent(deltaBaseNode(mfEntry));
            byte[] shelveMfContent = Revlog.applyDelta(baseMfContent, mfEntry.delta);
            Map<String, String> shelveManifest = parseManifestText(shelveMfContent);
            Map<String, String> baseManifest = parseManifestText(baseMfContent);

            for (Map.Entry<String, String> e : shelveManifest.entrySet()) {
                String path = e.getKey();
                String hexAndFlag = e.getValue();
                String flag = hexAndFlag.substring(40);
                int mode = flag.contains("l") ? 0120000 : (flag.contains("x") ? 0755 : 0644);
                char state = baseManifest.containsKey(path) ? 'n' : 'a';
                fileStates.put(path, state);
                fileModes.put(path, mode);
            }
            for (String path : baseManifest.keySet()) {
                if (!shelveManifest.containsKey(path)) {
                    fileStates.put(path, 'r');
                }
            }
        }

        byte[] p1Node = NodeIdUtil.fromHex(p1Hex);
        byte[] p2Node = NodeIdUtil.fromHex(p2Hex);

        try (HgLock wlock = repository.lockWorkingCopy();
             HgLock storeLock = repository.lockStore()) {

            Dirstate dirstate = repository.getDirstate();

            // Real hg's `hg unshelve` refuses to start while another merge/graft/rebase etc. is
            // already mid-flight (`cmdutil.checkunfinished`); hg4j approximates that here with the
            // one kind of in-progress state it can actually detect from the dirstate alone -- an
            // unresolved second parent.
            byte[] currentP2 = dirstate.getParent2();
            if (currentP2 != null && !NodeIdUtil.isAllZero(currentP2)) {
                throw new HgValidationException("Cannot unshelve: an unresolved merge is already in progress");
            }

            // Real hg's unshelve (mercurial/shelve.py _commitworkingcopychanges) tolerates pending
            // working-copy changes by temporarily committing them too, then folding the shelved
            // changes on top of that temp commit as well. hg4j doesn't implement that
            // generalization yet -- require a clean working copy relative to its current parent
            // instead of silently clobbering unrelated pending changes in the diff-based restore
            // below.
            Status wdStatus = new StatusCommand(repository).call();
            if (!wdStatus.getAdded().isEmpty() || !wdStatus.getModified().isEmpty() || !wdStatus.getRemoved().isEmpty()) {
                throw new HgValidationException("Cannot unshelve: the working copy has pending uncommitted changes "
                        + "(commit or shelve them first -- hg4j does not yet support unshelving onto pending changes)");
            }

            // The TRUE current working-directory parent -- may differ from the shelve's own
            // original parent (p1Hex/p1Node above) if other work has landed since the shelve was
            // taken. This is real hg's `pctx`.
            byte[] originalWdParent = dirstate.getParent1();

            // Fail fast (before touching anything) if the current parent doesn't even resolve to
            // a real revision -- real hg would already have failed resolving `repo['.']` long
            // before reaching any shelve logic in that case. Checking this upfront, rather than
            // only discovering it later when the rebase step tries to target it, avoids building
            // (and then having to unwind) a throwaway restore commit against a working copy whose
            // own notion of "current parent" cannot be trusted in the first place.
            if (!NodeIdUtil.isAllZero(originalWdParent)) {
                File clIdxCheck = new File(repository.getStoreDir(), "00changelog.i");
                File clDatCheck = new File(repository.getStoreDir(), "00changelog.d");
                Revlog clCheck = repository.getRevlog(clIdxCheck, clDatCheck);
                if (NodeIdUtil.findRevisionByNodeId(clCheck, originalWdParent) == -1) {
                    throw new HgRevisionNotFoundException("Cannot unshelve: working directory parent revision not found: "
                            + NodeIdUtil.toHex(originalWdParent));
                }
            }

            // Step 1: recreate the shelved commit as a real (throwaway) commit on top of the
            // parent it was ORIGINALLY shelved from -- checkout that parent first so the shelved
            // bundle's per-file deltas (encoded against p1's own local filelog revisions) apply
            // cleanly, exactly like a fresh `hg update` to that commit followed by unpacking the
            // shelve's changegroup on top (real hg's own `_unshelverestorecommit`).
            checkoutFullClean(p1Node);
            dirstate = repository.getDirstate();
            if (!NodeIdUtil.isAllZero(p2Node)) {
                // Preserve the exact (p1, p2) pairing the shelve was taken from (e.g. a shelve
                // made mid-merge) -- checkoutFullClean() above only ever moves a single parent.
                dirstate.setParents(p1Node, p2Node);
                repository.writeDirstate(dirstate);
                dirstate = repository.getDirstate();
            }

            // Restore files from bundle (verbatim per-file bundle decode -- this is what builds
            // the throwaway restore commit's content, unchanged from before this class was
            // rewritten to use the real rebase-based algorithm).
            for (ChangegroupParser.FileGroup fg : bundle.fileGroups) {
                String path = fg.path;
                // As with the changelog/manifest groups above, a file this bundle's OTHER
                // (non-shelved) entries also touched can carry more than one entry here -- select
                // the one that actually belongs to the shelved commit (matching cs), and skip this
                // path entirely if the shelved commit itself never touched it.
                ChangegroupParser.ChangeGroupEntry flEntry = findEntryByChangeset(fg.entries, shelveTargetNode);
                if (flEntry == null) {
                    continue;
                }

                Character state = fileStates.get(path);
                if (state == null) state = 'm';

                // Real base content for this file -- resolved from the LOCAL filelog by the
                // entry's own DELTA BASE node (empty only when that node truly is null), matching
                // how performShelve() now encodes the delta (see its comment for why "always
                // apply against empty" was wrong for real-hg-produced/consumed bundles). This is
                // deliberately deltabase, NOT p1: a cg02+ entry's declared p1 (the true changelog
                // parentage) and its delta's actual reference point can differ -- confirmed against
                // a real `hg shelve` bundle (2026-09-03), whose single-file-modification entry had
                // deltabase = null (a full-text "snapshot" delta, i.e. start=0/end=0 covering
                // nothing) even though p1 correctly pointed at the file's real previous revision;
                // applying that delta against the (non-empty) p1 content instead of empty produced
                // a corrupted result with the old content spuriously appended at the end.
                byte[] baseContent = resolveBaseFileContent(path, deltaBaseNode(flEntry));
                byte[] content = Revlog.applyDelta(baseContent, flEntry.delta);

                File diskFile = new File(repository.getDirectory(), path);
                diskFile.getParentFile().mkdirs();
                if (diskFile.exists() || Files.isSymbolicLink(diskFile.toPath())) {
                    Files.delete(diskFile.toPath());
                }

                Integer savedMode = fileModes.get(path);
                int mode = 0644;
                if (savedMode != null) {
                    mode = savedMode;
                } else {
                    mode = diskFile.canExecute() ? 0755 : 0644;
                }

                if (mode == 0120000) {
                    String target = new String(content, StandardCharsets.UTF_8).trim();
                    try {
                        Files.createSymbolicLink(diskFile.toPath(), Path.of(target));
                    } catch (Exception e) {
                        Files.write(diskFile.toPath(), content);
                    }
                } else {
                    Files.write(diskFile.toPath(), content);
                    boolean exec = (mode == 0755);
                    diskFile.setExecutable(exec, false);
                }

                int size = content.length;
                long time = SafeFileIO.lastModifiedSeconds(diskFile);

                dirstate.addEntry(path, new Dirstate.Entry(state, mode, size, time));
            }

            // Restore deleted files
            for (Map.Entry<String, Character> entry : fileStates.entrySet()) {
                if (entry.getValue() == 'r') {
                    File diskFile = new File(repository.getDirectory(), entry.getKey());
                    Files.deleteIfExists(diskFile.toPath());
                    dirstate.addEntry(entry.getKey(), new Dirstate.Entry('r', 0, 0, 0));
                }
            }

            repository.writeDirstate(dirstate);

            CommitCommand commitCmd = new CommitCommand(repository)
                    .setAuthor("hg4j <hg4j@example.com>")
                    .setMessage("[unshelve] " + name)
                    .setSkipLockAndJournal(true);
            byte[] tempCommitNode = commitCmd.call();

            // Step 2: rebase the restored commit onto the TRUE current working-directory parent,
            // if anything has landed there since the shelve was taken -- a no-op (rebasing onto
            // the parent it's already on) otherwise. Reuses RebaseCommand's own cherry-pick + real
            // 3-way-merge conflict detection wholesale rather than reimplementing one here.
            byte[] finalNode;
            String currentP1HexNow = NodeIdUtil.toHex(originalWdParent);
            if (currentP1HexNow.equalsIgnoreCase(p1Hex)) {
                finalNode = tempCommitNode;
            } else {
                try {
                    finalNode = new RebaseCommand(repository)
                            .setSource(tempCommitNode)
                            .setTarget(originalWdParent)
                            .call();
                } catch (HgMergeConflictException e) {
                    // Paused: RebaseCommand has already persisted its own resumable state
                    // (.hg/rebasestate-hg4j). Remember just enough on our side (which shelve, and
                    // where the final result should land once it's done) so a fresh ShelveCommand
                    // instance can pick this back up later via unshelveContinue()/unshelveAbort(),
                    // then propagate -- mirroring real hg pausing exactly like `hg rebase` would.
                    writeUnshelveState(name, tempCommitNode, originalWdParent);
                    throw e;
                } catch (Exception e) {
                    // Anything else (e.g. a corrupted/invalid current parent1 that doesn't even
                    // resolve to a real revision): undo the throwaway restore commit so a failed
                    // unshelve attempt never leaves stray store state or a half-moved working copy
                    // behind.
                    rollbackFailedUnshelveAttempt(tempCommitNode, originalWdParent);
                    if (e instanceof IOException io) throw io;
                    if (e instanceof HgLockException lk) throw lk;
                    if (e instanceof RuntimeException re) throw re;
                    throw new IOException(e);
                }
            }

            // Step 3: "uncommit" the result back onto the working copy as pending changes, and
            // erase every trace of the throwaway commit(s).
            finishUnshelve(name, stateFile, hgBundleFile, shelveInfoFile, patchFile,
                    tempCommitNode, finalNode, originalWdParent);
        }
    }

    /**
     * Resumes an unshelve paused by {@link #performUnshelve} (via {@link #call()}) on a rebase
     * conflict, after the caller has resolved every unresolved file reported by
     * {@code hg resolve --list} and staged the resolution on disk -- mirrors real hg's
     * {@code hg unshelve --continue}. Delegates the actual resume to the paused
     * {@link RebaseCommand}'s own {@link RebaseCommand#continueRebase()} (a fresh instance, driven
     * purely by {@code .hg/rebasestate-hg4j}), then finishes the same uncommit+cleanup sequence
     * {@link #performUnshelve} itself would have on a conflict-free rebase.
     *
     * @throws HgValidationException    if no unshelve is in progress, or unresolved files remain
     * @throws HgMergeConflictException if resolving still leaves a further conflict (not expected
     *                                   for unshelve's single-revision rebase, but handled the same
     *                                   way {@link RebaseCommand} itself would)
     */
    public void unshelveContinue() throws IOException, HgLockException, HgMergeConflictException {
        File unshelveStateFile = unshelveStateFile();
        if (!unshelveStateFile.exists()) {
            throw new HgValidationException("no unshelve in progress");
        }

        try (HgLock wlock = repository.lockWorkingCopy();
             HgLock storeLock = repository.lockStore()) {

            UnshelveState state = readUnshelveState();

            byte[] finalNode = new RebaseCommand(repository).continueRebase();

            File shelvedDir = new File(repository.getHgDir(), "shelved");
            File stateFile = new File(shelvedDir, state.name + ".state");
            File hgBundleFile = new File(shelvedDir, state.name + ".hg");
            File shelveInfoFile = new File(shelvedDir, state.name + ".shelve");
            File patchFile = new File(shelvedDir, state.name + ".patch");

            finishUnshelve(state.name, stateFile, hgBundleFile, shelveInfoFile, patchFile,
                    state.tempCommitNode, finalNode, state.originalWdParent);
        }
    }

    /**
     * Aborts an in-progress (paused-on-conflict) unshelve, mirroring real hg's
     * {@code hg unshelve --abort}: discards the throwaway restore/rebase commit(s) this attempt
     * created, restores the working copy and dirstate to exactly their pre-unshelve state, and --
     * unlike a completed unshelve -- leaves the shelve itself untouched so a future unshelve
     * attempt can still use it.
     *
     * @throws HgValidationException if no unshelve is in progress
     */
    public void unshelveAbort() throws IOException, HgLockException {
        File unshelveStateFile = unshelveStateFile();
        if (!unshelveStateFile.exists()) {
            throw new HgValidationException("no unshelve in progress");
        }

        try (HgLock wlock = repository.lockWorkingCopy();
             HgLock storeLock = repository.lockStore()) {

            UnshelveState state = readUnshelveState();

            File rebaseStateFile = new File(repository.getHgDir(), "rebasestate-hg4j");
            if (rebaseStateFile.exists()) {
                new RebaseCommand(repository).abort();
            }

            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            Revlog cl = repository.getRevlog(clIdx, clDat);
            int tempRev = cl.findRevision(state.tempCommitNode);
            if (tempRev != -1) {
                stripRevisionsFrom(tempRev);
                repository.clearRevlogCache();
            }

            checkoutFullClean(state.originalWdParent);
            deleteUnshelveStateFile();
        }
    }

    /**
     * Diffs {@code finalNode}'s manifest (the restored, possibly-rebased shelve commit) against
     * {@code originalWdParent}'s (the TRUE working-directory parent, unaffected by whatever the
     * shelve's own original parent was) and materializes the difference onto the working copy as
     * pending, uncommitted changes -- real hg's own {@code cmdutil.revert(ui, repo, shelvectx)}
     * step (mercurial/shelve.py {@code mergefiles()}). Every trace of the throwaway commit(s) this
     * unshelve attempt created is then erased (see the class javadoc for why this strips outright
     * rather than leaving an evolution marker), and the shelve's own on-disk files plus hg4j's
     * private in-progress bookkeeping are cleaned up.
     */
    private void finishUnshelve(String shelveName, File stateFile, File hgBundleFile, File shelveInfoFile,
                                 File patchFile, byte[] tempCommitNode, byte[] finalNode, byte[] originalWdParent)
            throws IOException, HgLockException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog cl = repository.getRevlog(clIdx, clDat);
        Revlog mf = repository.getManifestRevlog();
        MergeCommand helper = new MergeCommand(repository);

        int finalRev = cl.findRevision(finalNode);
        if (finalRev == -1) {
            throw new HgRevisionNotFoundException("Unshelve: rebased/restored commit vanished before it could be finalized.");
        }
        Map<String, String> finalManifest = helper.loadManifestAtCommit(cl, mf, finalRev);

        int destRev = NodeIdUtil.isAllZero(originalWdParent) ? -1 : cl.findRevision(originalWdParent);
        Map<String, String> destManifest = destRev == -1 ? Collections.emptyMap() : helper.loadManifestAtCommit(cl, mf, destRev);

        // Gather every path's pending content/mode BEFORE stripping below -- once the throwaway
        // commit(s) are erased their filelog revisions are gone too.
        Set<String> allPaths = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        allPaths.addAll(finalManifest.keySet());
        allPaths.addAll(destManifest.keySet());

        Map<String, byte[]> pendingContent = new HashMap<>();
        Map<String, Integer> pendingMode = new HashMap<>();
        Map<String, Character> pendingState = new HashMap<>();
        for (String path : allPaths) {
            String hFinal = finalManifest.get(path);
            String hDest = destManifest.get(path);
            if (Objects.equals(hFinal, hDest)) {
                continue;
            }
            if (hFinal == null) {
                pendingState.put(path, 'r');
            } else {
                pendingContent.put(path, helper.getFileRevisionContent(path, hFinal));
                pendingMode.put(path, helper.getModeFromManifestHex(hFinal));
                pendingState.put(path, hDest == null ? 'a' : 'n');
            }
        }

        // Erase every trace of the throwaway restore/rebase commit(s) -- verified against real hg
        // CLI (2026-09-04): after `hg unshelve` completes, the temp commit(s) it built internally
        // are gone entirely, not left behind even as hidden/obsolete revisions (real hg builds
        // them inside a single transaction it then aborts -- mercurial/shelve.py
        // _finishunshelve -> _aborttransaction -- which physically erases them; this truncation-
        // based strip achieves the same end state).
        int tempRev = cl.findRevision(tempCommitNode);
        if (tempRev != -1) {
            stripRevisionsFrom(tempRev);
            repository.clearRevlogCache();
        }

        checkoutFullClean(originalWdParent);
        Dirstate dirstate = repository.getDirstate();
        for (Map.Entry<String, Character> e : pendingState.entrySet()) {
            String path = e.getKey();
            char state = e.getValue();
            File diskFile = new File(repository.getDirectory(), path);
            if (state == 'r') {
                if (diskFile.exists() || Files.isSymbolicLink(diskFile.toPath())) {
                    Files.delete(diskFile.toPath());
                }
                dirstate.addEntry(path, new Dirstate.Entry('r', 0, 0, 0));
                continue;
            }

            byte[] content = pendingContent.get(path);
            int mode = pendingMode.get(path);
            diskFile.getParentFile().mkdirs();
            if (diskFile.exists() || Files.isSymbolicLink(diskFile.toPath())) {
                Files.delete(diskFile.toPath());
            }
            if (mode == 0120000) {
                String target = new String(content, StandardCharsets.UTF_8).trim();
                try {
                    Files.createSymbolicLink(diskFile.toPath(), Path.of(target));
                } catch (Exception ex) {
                    Files.write(diskFile.toPath(), content);
                }
            } else {
                Files.write(diskFile.toPath(), content);
                diskFile.setExecutable(mode == 0755, false);
            }
            int size = content.length;
            // Real hg's own dirstate ambiguous-mtime sentinel (mercurial/dirstate.py; the 32-bit
            // "-1", i.e. 0xFFFFFFFF) rather than a freshly-stat'd real mtime: this entry's content
            // was just fabricated by unshelve itself (from the restored/rebased commit), not
            // genuinely re-typed by a user at this instant, so its on-disk mtime otherwise happens
            // to exactly match what's recorded here -- indistinguishable from "unmodified" by a
            // naive size+mtime dirstate check alone. Real hg writes this same sentinel after its
            // own internal working-copy rewrites for exactly this reason (confirmed live: a real
            // `hg shelve`'s own revert-to-parent step does the same, see StatusCommand's matching
            // AMBIGUOUS_TIME handling). Using a real mtime here instead worked only by the
            // coincidence of running fast enough to land inside whatever racy-write window the
            // READING tool (real hg's own `hg status`, or hg4j's StatusCommand) happens to apply --
            // genuinely flaky, and more likely to be missed the more work this method does before
            // reaching this write. The sentinel makes every reader always re-verify by content,
            // eliminating the race outright.
            long time = state == 'n' ? 0xFFFFFFFFL : SafeFileIO.lastModifiedSeconds(diskFile);
            dirstate.addEntry(path, new Dirstate.Entry(state, mode, size, time));
        }
        repository.writeDirstate(dirstate);

        Files.deleteIfExists(stateFile.toPath());
        Files.deleteIfExists(shelveInfoFile.toPath());
        Files.deleteIfExists(hgBundleFile.toPath());
        Files.deleteIfExists(patchFile.toPath());
        deleteUnshelveStateFile();
    }

    /**
     * Best-effort cleanup after an unshelve attempt fails for a reason OTHER than a rebase
     * conflict (which persists its own resumable state instead, see {@link #performUnshelve}):
     * strips the throwaway restore commit back out of the store and tries to move the working
     * copy back toward {@code originalWdParent}. Failures here are swallowed -- this is already
     * running from inside a failure path, and the original exception takes priority.
     */
    private void rollbackFailedUnshelveAttempt(byte[] tempCommitNode, byte[] originalWdParent) {
        try {
            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            Revlog cl = repository.getRevlog(clIdx, clDat);
            int tempRev = cl.findRevision(tempCommitNode);
            if (tempRev != -1) {
                stripRevisionsFrom(tempRev);
                repository.clearRevlogCache();
            }
        } catch (Exception ignored) {
        }
        try {
            checkoutFullClean(originalWdParent);
        } catch (Exception ignored) {
        }
    }

    /**
     * Moves the working copy and dirstate cleanly to {@code node} -- a full, union-of-paths
     * manifest diff against whatever is CURRENTLY tracked in the dirstate (deleting any path not
     * part of {@code node}'s manifest, writing/overwriting every path that is), or -- when {@code
     * node} is null/the null revision -- back to the empty, pre-first-commit state.
     *
     * <p>Deliberately does NOT delegate to {@link UpdateCommand} (which diffs against whatever the
     * CURRENT dirstate parent resolves to): by the time this runs a second time within a single
     * unshelve attempt (see {@link #finishUnshelve}), the dirstate's parent is the throwaway
     * restore/rebase commit that has *already been stripped* a few lines earlier, so it can no
     * longer be resolved to a revision at all -- {@link UpdateCommand} would treat that as "no
     * current manifest" and silently fail to delete paths the stripped commit had added. Diffing
     * against the dirstate's own tracked-paths set instead (mirrors {@link
     * RebaseCommand}'s own {@code restoreWorkingCopyCleanTo}) sidesteps that entirely.
     */
    private void checkoutFullClean(byte[] node) throws IOException {
        Map<String, String> targetManifest;
        if (node == null || NodeIdUtil.isAllZero(node)) {
            targetManifest = Collections.emptyMap();
        } else {
            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            Revlog changelog = repository.getRevlog(clIdx, clDat);
            int rev = NodeIdUtil.findRevisionByNodeId(changelog, node);
            if (rev == -1) {
                throw new HgRevisionNotFoundException("Unshelve: checkout target revision not found: " + NodeIdUtil.toHex(node));
            }
            targetManifest = new MergeCommand(repository).loadManifestAtCommit(changelog, repository.getManifestRevlog(), rev);
        }

        Dirstate dirstate = repository.getDirstate();
        Set<String> allPaths = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        allPaths.addAll(dirstate.getEntries().keySet());
        allPaths.addAll(targetManifest.keySet());

        MergeCommand helper = new MergeCommand(repository);
        for (String path : allPaths) {
            String hexFlag = targetManifest.get(path);
            File f = new File(repository.getDirectory(), path);
            if (hexFlag == null) {
                if (f.exists() || Files.isSymbolicLink(f.toPath())) {
                    Files.delete(f.toPath());
                }
                dirstate.removeEntry(path);
                continue;
            }

            byte[] content = helper.getFileRevisionContent(path, hexFlag);
            int mode = helper.getModeFromManifestHex(hexFlag);
            f.getParentFile().mkdirs();
            if (f.exists() || Files.isSymbolicLink(f.toPath())) {
                Files.delete(f.toPath());
            }
            if (mode == 0120000) {
                String target = new String(content, StandardCharsets.UTF_8).trim();
                try {
                    Files.createSymbolicLink(f.toPath(), Path.of(target));
                } catch (Exception ex) {
                    Files.write(f.toPath(), content);
                }
            } else {
                Files.write(f.toPath(), content);
                f.setExecutable(mode == 0755, false);
            }
            int size = content.length;
            long time = SafeFileIO.lastModifiedSeconds(f);
            dirstate.addEntry(path, new Dirstate.Entry('n', mode, size, time));
        }

        byte[] parent1 = (node == null) ? new byte[20] : node;
        dirstate.setParents(parent1, new byte[20]);
        repository.writeDirstate(dirstate);
    }

    /** On-disk (hg4j-private) record of a paused, resumable unshelve -- see {@link #unshelveStateFile()}. */
    private static final class UnshelveState {
        String name;
        byte[] tempCommitNode;
        byte[] originalWdParent;
    }

    private File unshelveStateFile() {
        return new File(repository.getHgDir(), "shelvedstate-hg4j");
    }

    private void writeUnshelveState(String shelveName, byte[] tempCommitNode, byte[] originalWdParent) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("name=").append(shelveName).append('\n');
        sb.append("tempCommitNode=").append(NodeIdUtil.toHex(tempCommitNode)).append('\n');
        sb.append("originalWdParent=").append(NodeIdUtil.toHex(originalWdParent)).append('\n');
        Files.writeString(unshelveStateFile().toPath(), sb.toString(), StandardCharsets.UTF_8);
    }

    private UnshelveState readUnshelveState() throws IOException {
        UnshelveState state = new UnshelveState();
        for (String line : Files.readAllLines(unshelveStateFile().toPath(), StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            if (eq == -1) continue;
            String key = line.substring(0, eq);
            String val = line.substring(eq + 1);
            switch (key) {
                case "name" -> state.name = val;
                case "tempCommitNode" -> state.tempCommitNode = NodeIdUtil.fromHex(val);
                case "originalWdParent" -> state.originalWdParent = NodeIdUtil.fromHex(val);
                default -> { /* forward-compatible: ignore unknown keys */ }
            }
        }
        return state;
    }

    private void deleteUnshelveStateFile() throws IOException {
        Files.deleteIfExists(unshelveStateFile().toPath());
    }

    /** Path -&gt; "&lt;40-hex-node&gt;&lt;flags&gt;" (flags: "" regular, "x" exec, "l" symlink), real hg manifest text layout. */
    private Map<String, String> parseManifestText(byte[] content) {
        Map<String, String> map = new HashMap<>();
        String text = new String(content, StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            if (line.isEmpty()) continue;
            int nul = line.indexOf('\0');
            if (nul == -1) continue;
            map.put(line.substring(0, nul), line.substring(nul + 1));
        }
        return map;
    }

    /**
     * Resolves which changelog node within {@code bundle} is the actual shelved commit (as
     * opposed to any other ancestor commit real hg's shelve bundle may also carry -- see the
     * comment at this method's call site). Reads it from {@code shelveInfoFile}'s {@code
     * node=<hex>} line (real hg's own {@code .shelve} format, which {@link #performShelve} also
     * always writes now); falls back to the bundle's own single/last changelog entry when that
     * file is absent (a shelve made by an hg4j build predating this fix, whose bundle only ever
     * contains that one entry anyway).
     */
    private byte[] resolveShelveTargetNode(File shelveInfoFile, ChangegroupParser.ChangegroupBundle bundle) throws IOException {
        if (shelveInfoFile.exists()) {
            for (String line : Files.readAllLines(shelveInfoFile.toPath(), StandardCharsets.UTF_8)) {
                if (line.startsWith("node=")) {
                    return NodeIdUtil.fromHex(line.substring("node=".length()).trim());
                }
            }
        }
        return bundle.changelogEntries.get(bundle.changelogEntries.size() - 1).node;
    }

    /**
     * The node a changegroup entry's {@code delta} is actually encoded against: {@code
     * deltabase} when present (cg02+, where it is an explicit, independent field that need not
     * equal {@code p1} -- e.g. real hg's own shelve bundle can send a full-text delta,
     * {@code deltabase == nullid}, for an entry whose {@code p1} is a real, non-null revision),
     * else {@code p1} (cg01, whose implicit convention -- this class's own writer included -- is
     * that a single-entry group's delta is against its own {@code p1}).
     */
    private static byte[] deltaBaseNode(ChangegroupParser.ChangeGroupEntry entry) {
        return entry.deltabase != null ? entry.deltabase : entry.p1;
    }

    private static ChangegroupParser.ChangeGroupEntry findEntryByNode(
            List<ChangegroupParser.ChangeGroupEntry> entries, byte[] node) {
        for (ChangegroupParser.ChangeGroupEntry e : entries) {
            if (Arrays.equals(e.node, node)) {
                return e;
            }
        }
        return null;
    }

    private static ChangegroupParser.ChangeGroupEntry findEntryByChangeset(
            List<ChangegroupParser.ChangeGroupEntry> entries, byte[] csNode) {
        for (ChangegroupParser.ChangeGroupEntry e : entries) {
            if (Arrays.equals(e.cs, csNode)) {
                return e;
            }
        }
        return null;
    }

    /** The local manifest revlog's content at {@code p1Node}, or empty when {@code p1Node} is null/absent. */
    private byte[] resolveBaseManifestContent(byte[] p1Node) throws IOException {
        if (p1Node == null || NodeIdUtil.isAllZero(p1Node)) {
            return new byte[0];
        }
        Revlog mf = repository.getManifestRevlog();
        int rev = mf.findRevision(p1Node);
        if (rev == -1) {
            return new byte[0];
        }
        return mf.getRevisionContent(rev);
    }

    /** The local filelog's content for {@code path} at {@code p1Node}, or empty when {@code p1Node} is null/absent. */
    private byte[] resolveBaseFileContent(String path, byte[] p1Node) throws IOException {
        if (p1Node == null || NodeIdUtil.isAllZero(p1Node)) {
            return new byte[0];
        }
        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        if (!flIdx.exists()) {
            return new byte[0];
        }
        Revlog fl = repository.getRevlog(flIdx, flDat);
        int rev = NodeIdUtil.findRevisionByNodeId(fl, p1Node);
        if (rev == -1) {
            return new byte[0];
        }
        return fl.getRevisionContent(rev);
    }

    private void revertToLatestCommit(Dirstate dirstate, List<ShelvedFile> shelvedFiles) throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");

        Revlog changelog = repository.getRevlog(clIdx, clDat);
        byte[] p1 = dirstate.getParent1();
        int lastRev = changelog.findRevision(p1);
        if (lastRev == -1) {
            lastRev = changelog.getRevisionCount() - 1;
        }

        if (lastRev < 0) {
            // No commits yet, just delete added/modified files
            for (ShelvedFile sf : shelvedFiles) {
                File diskFile = new File(repository.getDirectory(), sf.path);
                Files.deleteIfExists(diskFile.toPath());
                dirstate.removeEntry(sf.path);
            }
            repository.writeDirstate(dirstate);
            return;
        }

        byte[] clContent = changelog.getRevisionContent(lastRev);
        String clText = new String(clContent, StandardCharsets.UTF_8);
        byte[] mfNode = NodeIdUtil.fromHex(clText.split("\n")[0].trim().substring(0, 40));
        Map<String, String> manifestEntries = new HashMap<>();
        ManifestWalk mw = new ManifestWalk(repository, mfNode);
        while (mw.next()) {
            ManifestWalk.Entry entry = mw.getEntry();
            String flag = entry.isSymlink() ? "l" : (entry.isExecutable() ? "x" : "");
            manifestEntries.put(entry.getPath(), entry.getNodeIdHex() + flag);
        }

        for (ShelvedFile sf : shelvedFiles) {
            File diskFile = new File(repository.getDirectory(), sf.path);
            String manifestNodeWithFlags = manifestEntries.get(sf.path);

            if (manifestNodeWithFlags == null) {
                // File was not in latest commit (i.e. was added), so delete it
                if (diskFile.exists() || Files.isSymbolicLink(diskFile.toPath())) {
                    Files.delete(diskFile.toPath());
                }
                dirstate.removeEntry(sf.path);
            } else {
                // File was in latest commit, so restore it
                String hexNode = manifestNodeWithFlags.substring(0, 40);
                String flags = manifestNodeWithFlags.substring(40);

                File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), sf.path);
                File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                Revlog filelog = repository.getRevlog(flIdx, flDat);

                int fileRev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(hexNode));
                byte[] originalContent = filelog.getRevisionContent(fileRev);

                diskFile.getParentFile().mkdirs();
                if (diskFile.exists() || Files.isSymbolicLink(diskFile.toPath())) {
                    Files.delete(diskFile.toPath());
                }

                int mode = 0644;
                if (flags.contains("l")) {
                    mode = 0120000;
                    String target = new String(originalContent, StandardCharsets.UTF_8).trim();
                    try {
                        Files.createSymbolicLink(diskFile.toPath(), Path.of(target));
                    } catch (Exception e) {
                        Files.write(diskFile.toPath(), originalContent);
                    }
                } else {
                    Files.write(diskFile.toPath(), originalContent);
                    boolean exec = flags.contains("x");
                    diskFile.setExecutable(exec, false);
                    mode = exec ? 0755 : 0644;
                }

                int size = originalContent.length;
                long time = SafeFileIO.lastModifiedSeconds(diskFile);

                dirstate.addEntry(sf.path, new Dirstate.Entry('n', mode, size, time));
            }
        }

        repository.writeDirstate(dirstate);
    }

    private static class ShelvedFile {
        String path;
        char state;
        byte[] content;
        int mode;

        ShelvedFile(String path, char state, byte[] content, int mode) {
            this.path = path;
            this.state = state;
            this.content = content;
            this.mode = mode;
        }
    }

    private void stripRevisionsFrom(int startRev) throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");

        Revlog changelog = repository.getRevlog(clIdx, clDat);
        Revlog manifest = repository.getManifestRevlog();

        // Calculate truncate boundaries. Real hg's "inline" revlog format (the default for a
        // small revlog -- confirmed the common case for a real-hg-authored repo's
        // changelog/manifest/filelogs, e.g. ShelveRealHgInteropTest's realHgShelveCanBeUnshelvedByHg4j,
        // 2026-09-04) packs each revision's compressed DATA directly after its own 64-byte index
        // header, all within the single .i file -- there is no .d file at all until/unless the
        // revlog later crosses hg's inline-size threshold and gets rewritten as non-inline. A
        // plain `rev * 64` byte offset (correct only for a NON-inline revlog, whose .i file holds
        // nothing but fixed-size 64-byte headers) truncates mid-record for an inline one,
        // corrupting it -- this method previously always used that formula, which was never
        // exercised against a real-hg-authored (inline) revlog before this class started actually
        // creating and stripping a real temporary commit during unshelve. Use the revlog's own
        // recorded per-revision file offset (Revlog.getFileOffset(), tracked by RevlogIndex's own
        // reader precisely for this purpose) instead, whenever Revlog.isInline() says so.
        long clIdxSize;
        long clDatSize = clDat.exists() ? clDat.length() : 0L;
        if (changelog.isInline()) {
            clIdxSize = changelog.getFileOffset(startRev);
        } else {
            clIdxSize = (long) startRev * 64;
            clDatSize = (startRev > 0) ? changelog.getIndexRecord(startRev).getOffset() : 0;
        }

        // We also truncate manifest starting from the linkRev mapping to startRev
        int minMfRev = -1;
        for (int i = 0; i < manifest.getRevisionCount(); i++) {
            if (manifest.getIndexRecord(i).getLinkRev() >= startRev) {
                minMfRev = i;
                break;
            }
        }

        long mfIdxSize = mfIdx.exists() ? mfIdx.length() : 0L;
        long mfDatSize = mfDat.exists() ? mfDat.length() : 0L;
        if (minMfRev != -1) {
            if (manifest.isInline()) {
                mfIdxSize = manifest.getFileOffset(minMfRev);
            } else {
                mfIdxSize = (long) minMfRev * 64;
                mfDatSize = (minMfRev > 0) ? manifest.getIndexRecord(minMfRev).getOffset() : 0;
            }
        }

        // Truncate filelogs registered in fncache
        File fncacheFile = new File(repository.getStoreDir(), "fncache");
        if (fncacheFile.exists()) {
            List<String> fncachePaths = Files.readAllLines(fncacheFile.toPath(), StandardCharsets.UTF_8);
            for (String relPath : fncachePaths) {
                if (relPath.endsWith(".i")) {
                    File flIdx = new File(repository.getStoreDir(), relPath);
                    String datPath = relPath.substring(0, relPath.length() - 2) + ".d";
                    File flDat = new File(repository.getStoreDir(), datPath);

                    if (flIdx.exists()) {
                        try {
                            Revlog filelog = repository.getRevlog(flIdx, flDat);
                            int minFileRev = -1;
                            for (int i = 0; i < filelog.getRevisionCount(); i++) {
                                if (filelog.getIndexRecord(i).getLinkRev() >= startRev) {
                                    minFileRev = i;
                                    break;
                                }
                            }
                            if (minFileRev != -1) {
                                long flIdxSize;
                                long flDatSize = flDat.exists() ? flDat.length() : 0L;
                                if (filelog.isInline()) {
                                    flIdxSize = filelog.getFileOffset(minFileRev);
                                } else {
                                    flIdxSize = (long) minFileRev * 64;
                                    flDatSize = (minFileRev > 0) ? filelog.getIndexRecord(minFileRev).getOffset() : 0;
                                }
                                truncateFile(flIdx, flIdxSize);
                                truncateFile(flDat, flDatSize);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }

        // Perform truncate physically
        truncateFile(clIdx, clIdxSize);
        truncateFile(clDat, clDatSize);
        truncateFile(mfIdx, mfIdxSize);
        truncateFile(mfDat, mfDatSize);
    }

    private void truncateFile(File file, long size) throws IOException {
        if (!file.exists()) return;
        if (size == 0) {
            Files.deleteIfExists(file.toPath());
        } else {
            try (FileChannel outChan = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
                outChan.truncate(size);
                outChan.force(true);
            }
        }
    }
}
