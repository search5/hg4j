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
import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.treewalk.ManifestWalk;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Porcelain command to shelve and unshelve local working copy changes.
 * Supports saving modified, added, and removed files and restoring them with full dirstate fidelity.
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

    public void call() throws IOException, HgLockException {
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

    private void performUnshelve(File stateFile) throws IOException, HgLockException {
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

        try (HgLock wlock = repository.lockWorkingCopy();
             HgLock storeLock = repository.lockStore()) {

            Dirstate dirstate = repository.getDirstate();

            // Validate parent hash consistency (W1)
            String currentP1Hex = NodeIdUtil.toHex(dirstate.getParent1());
            if (!currentP1Hex.equalsIgnoreCase(p1Hex)) {
                throw new HgValidationException("Cannot unshelve: Working directory parent (" + currentP1Hex
                    + ") does not match shelved parent (" + p1Hex + ")");
            }
            String currentP2Hex = NodeIdUtil.toHex(dirstate.getParent2());
            if (!currentP2Hex.equalsIgnoreCase(p2Hex)) {
                throw new HgValidationException("Cannot unshelve: Working directory parent2 (" + currentP2Hex
                    + ") does not match shelved parent2 (" + p2Hex + ")");
            }

            // Restore files from bundle
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

            Files.deleteIfExists(stateFile.toPath());
            Files.deleteIfExists(shelveInfoFile.toPath());
            Files.deleteIfExists(hgBundleFile.toPath());
            Files.deleteIfExists(patchFile.toPath());
        }
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

        // Calculate truncate boundaries
        long clIdxSize = (long) startRev * 64;
        long clDatSize = 0;
        if (startRev > 0) {
            clDatSize = changelog.getIndexRecord(startRev).getOffset();
        }

        // We also truncate manifest starting from the linkRev mapping to startRev
        int minMfRev = -1;
        for (int i = 0; i < manifest.getRevisionCount(); i++) {
            if (manifest.getIndexRecord(i).getLinkRev() >= startRev) {
                minMfRev = i;
                break;
            }
        }

        long mfIdxSize = manifest.getRevisionCount() * 64L;
        long mfDatSize = mfDat.exists() ? mfDat.length() : 0L;
        if (minMfRev != -1) {
            mfIdxSize = (long) minMfRev * 64;
            if (minMfRev > 0) {
                mfDatSize = manifest.getIndexRecord(minMfRev).getOffset();
            } else {
                mfDatSize = 0;
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
                                long flIdxSize = (long) minFileRev * 64;
                                long flDatSize = 0;
                                if (minFileRev > 0) {
                                    flDatSize = filelog.getIndexRecord(minFileRev).getOffset();
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
