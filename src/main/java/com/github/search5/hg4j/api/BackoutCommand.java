package com.github.search5.hg4j.api;

import com.github.search5.hg4j.errors.HgLockException;
import com.github.search5.hg4j.errors.HgValidationException;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.treewalk.ManifestWalk;
import com.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Porcelain command corresponding to {@code hg backout REV} — creates a new commit that undoes
 * the changes introduced by {@code REV}, without altering history. Only single-parent changesets
 * are supported (matching real hg's default behavior — backing out a merge requires {@code
 * --parent} to disambiguate, which this command does not yet expose).
 */
public class BackoutCommand {
    private final HgRepository repository;
    private String revision;
    private String message;
    private String author = "hg4j";

    public BackoutCommand(HgRepository repository) {
        this.repository = repository;
    }

    public BackoutCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    public BackoutCommand setMessage(String message) {
        this.message = message;
        return this;
    }

    public BackoutCommand setAuthor(String author) {
        this.author = author;
        return this;
    }

    public byte[] call() throws IOException, HgLockException {
        if (revision == null || revision.isEmpty()) {
            throw new IllegalStateException("Revision to back out must be specified.");
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        byte[] targetNode = NodeIdUtil.resolveRevision(changelog, revision);
        if (targetNode == null) {
            throw new HgValidationException("Unknown revision: " + revision);
        }
        int targetRev = changelog.findRevision(targetNode);
        Revlog.IndexRecord targetRec = changelog.getIndexRecord(targetRev);
        if (targetRec.getParent2() != -1) {
            throw new HgValidationException(
                    "cannot backout a changeset with two parents (merge) — --parent selection is not supported yet");
        }
        int parentRev = targetRec.getParent1();

        Map<String, byte[]> targetFiles = manifestOf(targetRev);
        Map<String, byte[]> parentFiles = parentRev == -1 ? new HashMap<>() : manifestOf(parentRev);

        Set<String> allPaths = new HashSet<>();
        allPaths.addAll(targetFiles.keySet());
        allPaths.addAll(parentFiles.keySet());

        for (String path : allPaths) {
            byte[] targetFileNode = targetFiles.get(path);
            byte[] parentFileNode = parentFiles.get(path);
            File diskFile = new File(repository.getDirectory(), path);

            if (targetFileNode != null && parentFileNode != null) {
                if (!java.util.Arrays.equals(targetFileNode, parentFileNode)) {
                    // 대상 리비전에서 수정됨 → 그 이전(부모) 상태로 복원
                    writeFileContentByNode(path, parentFileNode);
                }
                // 동일하면 이 파일은 대상 리비전에서 변경되지 않았으므로 손대지 않는다.
            } else if (targetFileNode != null) {
                // 대상 리비전에서 새로 추가된 파일 → 되돌리려면 제거
                if (diskFile.exists()) {
                    new RemoveCommand(repository).setFile(path).setForce(true).call();
                }
            } else {
                // 대상 리비전에서 삭제된 파일(parentFileNode는 allPaths 유니온 구성상 null일
                // 수 없음) → 삭제 전 상태로 복원 후 add.
                writeFileContentByNode(path, parentFileNode);
                new AddCommand(repository).addFile(path).call();
            }
        }

        String shortHex = NodeIdUtil.toHex(targetNode).substring(0, 12);
        String commitMessage = (message != null && !message.isEmpty())
                ? message
                : "Backed out changeset " + shortHex;

        return new CommitCommand(repository)
                .setAuthor(author)
                .setMessage(commitMessage)
                .call();
    }

    private Map<String, byte[]> manifestOf(int changelogRev) throws IOException {
        Map<String, byte[]> result = new HashMap<>();
        if (changelogRev < 0) {
            return result;
        }
        ManifestWalk walk = new ManifestWalk(repository, String.valueOf(changelogRev));
        while (walk.next()) {
            ManifestWalk.Entry entry = walk.getEntry();
            result.put(entry.getPath(), entry.getNodeId());
        }
        return result;
    }

    private void writeFileContentByNode(String path, byte[] fileNode) throws IOException {
        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        if (!flIdx.exists()) {
            throw new HgValidationException("Filelog not found for path: " + path);
        }
        Revlog filelog = repository.getRevlog(flIdx, flDat);
        int rev = filelog.findRevision(fileNode);
        if (rev == -1) {
            throw new HgValidationException("File revision not found in filelog: " + path);
        }
        byte[] content = filelog.getRevisionContent(rev);
        File diskFile = new File(repository.getDirectory(), path);
        diskFile.getParentFile().mkdirs();
        Files.write(diskFile.toPath(), content);
    }
}
