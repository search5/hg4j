package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

/**
 * Porcelain command to verify the integrity of the Mercurial repository.
 * Emulates 'hg verify' by checking node id hash consistency in changelog, manifest, and all filelogs.
 */
public final class VerifyCommand {
    private final HgRepository repository;

    public VerifyCommand(HgRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("Repository cannot be null");
        }
        this.repository = repository;
    }

    /**
     * Executes the verification checks.
     *
     * @return List of error messages, empty if repository is 100% healthy
     */
    public List<String> call() {
        List<String> errors = new ArrayList<>();
        try {
            // 1. Verify Changelog
            //
            // 2026-09-01: a freshly-`hg init`'d repository (or one cloned but not yet pulled)
            // legitimately has no 00changelog.i at all until the first commit -- confirmed
            // against real `hg verify` (v7.2), which reports "checked 0 changesets" and exits 0
            // on such a repository, never an error. Treating the absence of the file as an
            // integrity error was a false positive; it is now simply an empty changelog (0
            // revisions), matching real hg. This does mean that the separate, much rarer case of
            // someone deleting 00changelog.i from a repository that *did* have history is no
            // longer flagged here -- but this simplified verifier has never cross-checked
            // changelog/manifest/filelog linkage (unlike real hg's verify), so it could not have
            // reliably reported *what* was lost in that scenario anyway.
            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            int clCount = 0;
            if (clIdx.exists()) {
                Revlog changelog = repository.getRevlog(clIdx, clDat);
                clCount = changelog.getRevisionCount();
                for (int r = 0; r < clCount; r++) {
                    Revlog.IndexRecord rec = changelog.getIndexRecord(r);
                    byte[] expectedNode = rec.getNodeId();

                    try {
                        byte[] content = changelog.getRawRevisionContent(r);
                        byte[] p1 = (rec.getParent1() != -1) ? changelog.getIndexRecord(rec.getParent1()).getNodeId() : new byte[20];
                        byte[] p2 = (rec.getParent2() != -1) ? changelog.getIndexRecord(rec.getParent2()).getNodeId() : new byte[20];

                        byte[] computed = NodeIdUtil.computeNodeId(content, p1, p2);
                        byte[] computed20 = Arrays.copyOf(computed, 20);
                        if (!Arrays.equals(expectedNode, computed20)) {
                            errors.add("changelog integrity mismatch at revision " + r + " (expected: " + NodeIdUtil.toHex(expectedNode) + ", computed: " + NodeIdUtil.toHex(computed20) + ")");
                        }
                    } catch (Exception e) {
                        errors.add("failed to read changelog revision " + r + ": " + e.getMessage());
                    }
                }
            }

            // 2. Verify Manifest
            //
            // 2026-09-01: real `hg verify` treats a missing 00manifest.i as an integrity error
            // ("0: empty or missing manifest", exit 1) whenever the changelog already has
            // revisions -- confirmed by deleting 00manifest.i from a real one-commit repository
            // and re-running real hg. Previously this method silently skipped the manifest check
            // whenever the file was absent, which was only correct for the (clCount == 0)
            // never-committed case; it is now reported as an error for the non-empty case too.
            File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
            File mfDat = new File(repository.getStoreDir(), "00manifest.d");
            if (mfIdx.exists()) {
                Revlog manifest = repository.getRevlog(mfIdx, mfDat);
                int mfCount = manifest.getRevisionCount();
                for (int r = 0; r < mfCount; r++) {
                    Revlog.IndexRecord rec = manifest.getIndexRecord(r);
                    byte[] expectedNode = rec.getNodeId();

                    try {
                        byte[] content = manifest.getRawRevisionContent(r);
                        byte[] p1 = (rec.getParent1() != -1) ? manifest.getIndexRecord(rec.getParent1()).getNodeId() : new byte[20];
                        byte[] p2 = (rec.getParent2() != -1) ? manifest.getIndexRecord(rec.getParent2()).getNodeId() : new byte[20];

                        byte[] computed = NodeIdUtil.computeNodeId(content, p1, p2);
                        byte[] computed20 = Arrays.copyOf(computed, 20);
                        if (!Arrays.equals(expectedNode, computed20)) {
                            errors.add("manifest integrity mismatch at revision " + r + " (expected: " + NodeIdUtil.toHex(expectedNode) + ", computed: " + NodeIdUtil.toHex(computed20) + ")");
                        }
                    } catch (Exception e) {
                        errors.add("failed to read manifest revision " + r + ": " + e.getMessage());
                    }
                }
            } else if (clCount > 0) {
                errors.add("manifest index not found but changelog has " + clCount + " revision(s)");
            }

            // 3. Verify all filelogs (fncache에 등록된 모든 파일 revlog).
            // 2026-09-01 이전에는 이 클래스의 Javadoc이 "changelog, manifest, and all
            // filelogs"를 검사한다고 주장했지만 실제로는 filelog를 전혀 안 봐서, 파일
            // 콘텐츠가 손상돼도 "정상"으로 보고하는 거짓 양성 위험이 있었다.
            File fncacheFile = new File(repository.getStoreDir(), "fncache");
            if (fncacheFile.exists()) {
                List<String> fncacheLines = Files.readAllLines(fncacheFile.toPath(), StandardCharsets.UTF_8);
                for (String line : fncacheLines) {
                    String rel = line.trim();
                    if (rel.isEmpty() || !rel.endsWith(".i")) {
                        continue;
                    }
                    File flIdx = new File(repository.getStoreDir(), rel);
                    File flDat = new File(flIdx.getParentFile(), flIdx.getName().substring(0, flIdx.getName().length() - 2) + ".d");
                    if (!flIdx.exists()) {
                        errors.add("filelog index not found for fncache entry: " + rel);
                        continue;
                    }
                    try {
                        Revlog filelog = repository.getRevlog(flIdx, flDat);
                        int flCount = filelog.getRevisionCount();
                        for (int r = 0; r < flCount; r++) {
                            Revlog.IndexRecord rec = filelog.getIndexRecord(r);
                            byte[] expectedNode = rec.getNodeId();
                            try {
                                byte[] content = filelog.getRawRevisionContent(r);
                                byte[] p1 = (rec.getParent1() != -1) ? filelog.getIndexRecord(rec.getParent1()).getNodeId() : new byte[20];
                                byte[] p2 = (rec.getParent2() != -1) ? filelog.getIndexRecord(rec.getParent2()).getNodeId() : new byte[20];
                                byte[] computed = NodeIdUtil.computeNodeId(content, p1, p2);
                                byte[] computed20 = Arrays.copyOf(computed, 20);
                                if (!Arrays.equals(expectedNode, computed20)) {
                                    errors.add(rel + "@" + r + ": integrity mismatch (expected: "
                                            + NodeIdUtil.toHex(expectedNode) + ", computed: " + NodeIdUtil.toHex(computed20) + ")");
                                }
                            } catch (Exception e) {
                                errors.add(rel + "@" + r + ": failed to read revision: " + e.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        errors.add("failed to open filelog " + rel + ": " + e.getMessage());
                    }
                }
            }

        } catch (Exception e) {
            errors.add("critical repository read failure: " + e.getMessage());
        }

        return errors;
    }
}
