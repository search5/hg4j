package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.Arrays;
import java.util.stream.Stream;

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
                verifyRevlogLegacyLabel(changelog, "changelog", errors);
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
                verifyRevlogLegacyLabel(manifest, "manifest", errors);
            } else if (clCount > 0) {
                errors.add("manifest index not found but changelog has " + clCount + " revision(s)");
            }

            // 2026-09-05 (backlog #39 wave 5): treemanifest (`experimental.treemanifest=1`) splits
            // the manifest into a root `00manifest.i` plus one submanifest revlog per subdirectory
            // under `meta/<dir>/00manifest.i` (real hg's `manifestrevlog.dirlog()`, confirmed via
            // `hg-rust-7.2.4`: e.g. `meta/sub/00manifest.i`, `meta/sub/deep/00manifest.i`). Before
            // this fix, VerifyCommand never looked under `meta/` at all, so a corrupted submanifest
            // revlog in a treemanifest repository would silently report zero errors -- a genuine
            // false negative. Every submanifest found is verified exactly like the root manifest,
            // labeled by its own store-relative path so the error names the actual broken file.
            File metaDir = new File(repository.getStoreDir(), "meta");
            if (metaDir.isDirectory()) {
                for (String rel : findFilesByName(metaDir.toPath(), "00manifest.i")) {
                    verifyRevlogAtPath(rel, errors);
                }
            }

            // 3. Verify all filelogs.
            //
            // 2026-09-01 이전에는 이 클래스의 Javadoc이 "changelog, manifest, and all
            // filelogs"를 검사한다고 주장했지만 실제로는 filelog를 전혀 안 봐서, 파일
            // 콘텐츠가 손상돼도 "정상"으로 보고하는 거짓 양성 위험이 있었다.
            //
            // 2026-09-05 (backlog #39 wave 5): discovery used to read ONLY `fncache` -- but real
            // hg's `fileindex-v1`/`general-v2` storage extensions (confirmed via
            // `hg-rust-7.2.4`: `format.use-fileindex-v1=yes` / `experimental.revlogv2=...`) do NOT
            // write an `fncache` file at all (they track files via `fileindex`/`fileindex-list.*`/
            // `fileindex-tree.*` instead), so the whole filelog check used to be silently skipped
            // for every repository using either extension -- a false negative masking real
            // corruption. Discovery now unions fncache's own entries (kept so a dangling fncache
            // entry -- listed but missing on disk -- is still reported exactly as before) with a
            // direct walk of the `data/` directory tree for every `*.i` file, which is present on
            // disk regardless of which discovery mechanism (fncache vs fileindex) the store format
            // layers on top.
            Set<String> filelogPaths = new TreeSet<>();
            File fncacheFile = new File(repository.getStoreDir(), "fncache");
            if (fncacheFile.exists()) {
                List<String> fncacheLines = Files.readAllLines(fncacheFile.toPath(), StandardCharsets.UTF_8);
                for (String line : fncacheLines) {
                    String rel = line.trim();
                    if (rel.isEmpty() || !rel.endsWith(".i") || !rel.startsWith("data/")) {
                        continue;
                    }
                    File flIdx = new File(repository.getStoreDir(), rel);
                    if (!flIdx.exists()) {
                        errors.add("filelog index not found for fncache entry: " + rel);
                        continue;
                    }
                    filelogPaths.add(rel);
                }
            }
            File dataDir = new File(repository.getStoreDir(), "data");
            if (dataDir.isDirectory()) {
                filelogPaths.addAll(findFilesByName(dataDir.toPath(), null));
            }
            for (String rel : filelogPaths) {
                verifyRevlogAtPath(rel, errors);
            }

        } catch (Exception e) {
            errors.add("critical repository read failure: " + e.getMessage());
        }

        return errors;
    }

    /**
     * Recursively collects every regular file under {@code root} (inclusive), returned as
     * store-root-relative paths using {@code /} separators (so they read the same on every OS and
     * match real hg's own path conventions), sorted for deterministic error ordering. When {@code
     * requiredName} is non-null, only files whose name equals it are returned (used for {@code
     * 00manifest.i} submanifest discovery under {@code meta/}); when {@code null}, only files
     * ending in {@code .i} are returned (used for filelog discovery under {@code data/}).
     */
    private List<String> findFilesByName(Path root, String requiredName) throws IOException {
        Path storeRoot = repository.getStoreDir().toPath();
        List<String> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> requiredName == null ? p.getFileName().toString().endsWith(".i")
                            : p.getFileName().toString().equals(requiredName))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(p -> result.add(storeRoot.relativize(p).toString().replace(File.separatorChar, '/')));
        }
        return result;
    }

    /** Opens the revlog at store-relative path {@code rel} (an {@code .i} file) and verifies it,
     * labeling any errors with {@code rel} -- shared by both the {@code meta/} submanifest walk
     * and the {@code data/} filelog walk. */
    private void verifyRevlogAtPath(String rel, List<String> errors) {
        File idx = new File(repository.getStoreDir(), rel);
        File dat = new File(idx.getParentFile(), idx.getName().substring(0, idx.getName().length() - 2) + ".d");
        if (!idx.exists()) {
            errors.add("index not found: " + rel);
            return;
        }
        try {
            Revlog revlog = repository.getRevlog(idx, dat);
            for (int r = 0; r < revlog.getRevisionCount(); r++) {
                verifyRevision(revlog, r, errors, rel + "@" + r + ": integrity mismatch", rel + "@" + r + ": failed to read revision");
            }
        } catch (Exception e) {
            errors.add("failed to open filelog " + rel + ": " + e.getMessage());
        }
    }

    /** Verifies every revision of {@code revlog}, using the pre-2026-09-05 message wording
     * ({@code "<label> integrity mismatch at revision <r> ..."} / {@code "failed to read <label>
     * revision <r>: ..."}) for the changelog and root manifest, which existing tests assert on
     * verbatim. */
    private void verifyRevlogLegacyLabel(Revlog revlog, String label, List<String> errors) {
        for (int r = 0; r < revlog.getRevisionCount(); r++) {
            verifyRevision(revlog, r, errors,
                    label + " integrity mismatch at revision " + r,
                    "failed to read " + label + " revision " + r);
        }
    }

    /** Recomputes revision {@code r}'s node id from its raw content + parents and compares it
     * against the recorded node id, appending {@code mismatchPrefix} (with the expected/computed
     * hex appended) on a hash mismatch, or {@code readFailurePrefix} (with the exception message
     * appended) if the revision cannot even be read. */
    private void verifyRevision(Revlog revlog, int r, List<String> errors,
            String mismatchPrefix, String readFailurePrefix) {
        Revlog.IndexRecord rec = revlog.getIndexRecord(r);
        byte[] expectedNode = rec.getNodeId();
        try {
            byte[] content = revlog.getRawRevisionContent(r);
            byte[] p1 = (rec.getParent1() != -1) ? revlog.getIndexRecord(rec.getParent1()).getNodeId() : new byte[20];
            byte[] p2 = (rec.getParent2() != -1) ? revlog.getIndexRecord(rec.getParent2()).getNodeId() : new byte[20];

            byte[] computed = NodeIdUtil.computeNodeId(content, p1, p2);
            byte[] computed20 = Arrays.copyOf(computed, 20);
            if (!Arrays.equals(expectedNode, computed20)) {
                errors.add(mismatchPrefix + " (expected: " + NodeIdUtil.toHex(expectedNode)
                        + ", computed: " + NodeIdUtil.toHex(computed20) + ")");
            }
        } catch (Exception e) {
            errors.add(readFailurePrefix + ": " + e.getMessage());
        }
    }
}
