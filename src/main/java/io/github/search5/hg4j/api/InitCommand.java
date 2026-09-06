package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.SafeFileIO;

import java.io.File;
import java.io.IOException;
import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import io.github.search5.hg4j.errors.HgValidationException;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;

/**
 * Initializes a new Mercurial repository.
 *
 * <p>Beyond the always-on baseline requirements ({@code dotencode}/{@code fncache}/
 * {@code generaldelta}/{@code revlogv1}/{@code store}), this supports every requirement axis of
 * the backlog #39 requirement matrix ({@code exhaustive-interop-matrix-plan.md} §1-1): dirstate-v2,
 * changelog-v2 (+ sidedata-copies), treemanifest, and the three mutually-exclusive
 * storage-extensions (persistent-nodemap / fileindex-v1 / general-v2). Real hg's own
 * mutual-implication rules (verified live against {@code hg}/{@code hg-rust-7.2.4}, 2026-09-05) are
 * mirrored here:
 * <ul>
 *   <li>{@code fileindex-v1} implies {@code persistent-nodemap}, and its presence drops
 *   {@code fncache}/{@code dotencode} from requires (fileindex fully replaces the fncache-based
 *   store layout).</li>
 *   <li>{@code general-v2} implies both {@code fileindex-v1} and {@code persistent-nodemap}, and
 *   drops {@code revlogv1} (replaced end-to-end by {@code exp-revlogv2.2}).</li>
 *   <li>{@code sidedata-copies} implies {@code changelog-v2}.</li>
 *   <li>{@code treemanifest} is mutually exclusive with {@code fileindex-v1}/{@code general-v2},
 *   matching real hg's own {@code abort: cannot create repository with
 *   'format.use-fileindex-v1' and 'experimental.treemanifest' both enabled...} -- {@link #call()}
 *   throws {@link HgValidationException} for this combination rather than silently producing a
 *   store real hg itself refuses to create.</li>
 * </ul>
 * All of this deliberately keeps the single-file, non-{@code share-safe} {@code .hg/requires}
 * layout this class already used for its two original knobs (dirstate-v2/zstd) rather than
 * adopting real hg's modern default split across {@code .hg/requires} + {@code .hg/store/requires}
 * -- live-verified (2026-09-05, both native and via {@code hg-rust-7.2.4}) that real hg accepts
 * this older, still fully-supported layout (single {@code .hg/requires} with every token,
 * {@code format.use-share-safe=no}) identically to the modern split one for every one of these
 * requirement tokens, including {@code treemanifest} (produces the expected {@code meta/<dir>/
 * 00manifest.i} split) and {@code fileindex-v1} (produces the expected {@code fileindex}/
 * {@code fileindex-tree.*}/{@code fileindex-meta.*}/{@code fileindex-list.*} files on first
 * commit) -- so this class does not need to manage the share-safe split at all.
 */
public class InitCommand {
    private File directory;
    private boolean dirstateV2 = false;
    private boolean useZstd = false;
    private boolean changelogV2 = false;
    private boolean sidedataCopies = false;
    private boolean treemanifest = false;
    private boolean persistentNodemap = false;
    private boolean fileIndexV1 = false;
    private boolean generalV2 = false;

    public InitCommand setDirectory(File directory) {
        this.directory = directory;
        return this;
    }

    public InitCommand setDirstateV2(boolean dirstateV2) {
        this.dirstateV2 = dirstateV2;
        return this;
    }

    public InitCommand setUseZstd(boolean useZstd) {
        this.useZstd = useZstd;
        return this;
    }

    /** {@code format.exp-use-changelog-v2=...} -- changelog uses the docket-based revlog v2 format. */
    public InitCommand setChangelogV2(boolean changelogV2) {
        this.changelogV2 = changelogV2;
        return this;
    }

    /**
     * {@code format.exp-use-copies-side-data-changeset=yes} -- commits carry a {@code SD_FILES}
     * sidedata record. Implies {@link #setChangelogV2}, matching real hg.
     */
    public InitCommand setSidedataCopies(boolean sidedataCopies) {
        this.sidedataCopies = sidedataCopies;
        return this;
    }

    /** {@code experimental.treemanifest=1} -- manifests split recursively per-directory. */
    public InitCommand setTreemanifest(boolean treemanifest) {
        this.treemanifest = treemanifest;
        return this;
    }

    /** {@code format.use-persistent-nodemap=true} -- maintain a {@code <radix>.n} nodemap trie. */
    public InitCommand setPersistentNodemap(boolean persistentNodemap) {
        this.persistentNodemap = persistentNodemap;
        return this;
    }

    /**
     * {@code format.use-fileindex-v1=yes} -- store uses a radix-trie file index instead of
     * fncache. Implies {@link #setPersistentNodemap}, matching real hg.
     */
    public InitCommand setFileIndexV1(boolean fileIndexV1) {
        this.fileIndexV1 = fileIndexV1;
        return this;
    }

    /**
     * {@code experimental.revlogv2=enable-unstable-format-and-corrupt-my-data} -- manifests/
     * filelogs use the general revlog v2 format ({@code exp-revlogv2.2}). Implies both
     * {@link #setFileIndexV1} and {@link #setPersistentNodemap}, matching real hg.
     */
    public InitCommand setGeneralV2(boolean generalV2) {
        this.generalV2 = generalV2;
        return this;
    }

    public HgRepository call() throws IOException {
        if (directory == null) {
            throw new IllegalStateException("Repository directory must be specified.");
        }

        // Try to create the directory if it doesn't exist
        if (!directory.exists()) {
            if (!directory.mkdirs()) {
                throw new HgRepositoryNotFoundException("Failed to create repository directory: " + directory);
            }
        } else if (!directory.isDirectory()) {
            throw new HgRepositoryNotFoundException("Path exists and is not a directory: " + directory);
        }

        File hgDir = new File(directory, ".hg");
        if (hgDir.exists() && !hgDir.isDirectory()) {
            throw new HgRepositoryNotFoundException("Path exists and is not a directory: " + hgDir);
        }
        if (!hgDir.exists()) {
            if (!hgDir.mkdir()) {
                throw new HgRepositoryNotFoundException("Failed to create .hg directory in: " + directory);
            }
        }

        File storeDir = new File(hgDir, "store");
        if (storeDir.exists() && !storeDir.isDirectory()) {
            throw new HgRepositoryNotFoundException("Path exists and is not a directory: " + storeDir);
        }
        if (!storeDir.exists()) {
            if (!storeDir.mkdir()) {
                throw new HgRepositoryNotFoundException("Failed to create .hg/store directory");
            }
        }

        // Mutual-implication rules mirror real hg exactly (live-verified against hg-rust-7.2.4,
        // 2026-09-05): general-v2 implies fileindex-v1 (which itself implies persistent-nodemap),
        // and sidedata-copies implies changelog-v2.
        boolean effectiveFileIndexV1 = fileIndexV1 || generalV2;
        boolean effectivePersistentNodemap = persistentNodemap || effectiveFileIndexV1;
        boolean effectiveChangelogV2 = changelogV2 || sidedataCopies;

        if (treemanifest && effectiveFileIndexV1) {
            // Matches real hg's own abort message (mercurial/localrepo.py) byte-for-byte modulo
            // the specific requirement name that triggered it.
            String culprit = generalV2 ? "experimental.revlogv2" : "format.use-fileindex-v1";
            throw new HgValidationException("cannot create repository with '" + culprit
                    + "' and 'experimental.treemanifest' both enabled since they are incompatible with each other");
        }

        File requiresFile = new File(hgDir, "requires");
        List<String> requirements = new ArrayList<>();
        // general-v2 (exp-revlogv2.2) fully replaces the revlogv1 format for manifests/filelogs;
        // fileindex-v1 fully replaces the fncache/dotencode-based store layout -- both confirmed
        // by diffing real hg's own generated requires files for these combinations.
        if (!effectiveFileIndexV1) {
            requirements.add("dotencode");
            requirements.add("fncache");
        }
        requirements.add("generaldelta");
        if (!generalV2) {
            requirements.add("revlogv1");
        }
        requirements.add("store");
        if (dirstateV2) {
            requirements.add("dirstate-v2");
        }
        if (useZstd) {
            requirements.add("revlog-compression-zstd");
        }
        if (effectiveChangelogV2) {
            requirements.add("exp-changelog-v2");
        }
        if (sidedataCopies) {
            requirements.add("exp-copies-sidedata-changeset");
        }
        if (treemanifest) {
            requirements.add("treemanifest");
        }
        if (effectivePersistentNodemap) {
            requirements.add("persistent-nodemap");
        }
        if (effectiveFileIndexV1) {
            requirements.add("fileindex-v1");
        }
        if (generalV2) {
            requirements.add("exp-revlogv2.2");
        }

        try {
            SafeFileIO.writeLinesAtomic(requiresFile, requirements);
        } catch (IOException e) {
            throw new HgRepositoryNotFoundException("Failed to write .hg/requires file", e);
        }

        // Legacy-client compatibility guard real hg always writes alongside a "store"-format
        // repository (present since Mercurial's "store" requirement was introduced, entirely
        // independent of share-safe -- confirmed present even with `format.use-share-safe=no`):
        // a pre-store-aware hg client that ignores `requires` and reads .hg/00changelog.i directly
        // gets a small self-describing dummy revlog instead of silently misreading a location that
        // now holds nothing meaningful. Not required for any modern client (hg4j included, which
        // always reads the real changelog from the store dir) to function -- real hg itself still
        // opens/verifies/commits into repositories with this file deleted -- but omitting it was a
        // real, if low-severity, fidelity gap versus what `hg init` itself always produces.
        // Exact bytes confirmed against a real `hg init` (2026-09-05): 0x00 0x00 0xff 0xff followed
        // by the ASCII text "dummy changelog to prevent using the old repo layout" (57 bytes total).
        File legacyGuard = new File(hgDir, "00changelog.i");
        if (!legacyGuard.exists()) {
            byte[] suffix = " dummy changelog to prevent using the old repo layout"
                    .getBytes(StandardCharsets.US_ASCII);
            byte[] guardContent = new byte[4 + suffix.length];
            guardContent[0] = 0x00;
            guardContent[1] = 0x00;
            guardContent[2] = (byte) 0xff;
            guardContent[3] = (byte) 0xff;
            System.arraycopy(suffix, 0, guardContent, 4, suffix.length);
            try {
                SafeFileIO.writeAtomic(legacyGuard, guardContent);
            } catch (IOException e) {
                throw new HgRepositoryNotFoundException("Failed to write .hg/00changelog.i compatibility guard", e);
            }
        }

        return new HgRepository(directory);
    }
}
