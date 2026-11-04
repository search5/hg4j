package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.gpg.GpgKeyring;
import io.github.search5.hg4j.gpg.HgSigEntry;
import io.github.search5.hg4j.gpg.HgSigner;
import io.github.search5.hg4j.gpg.HgSigsFile;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import org.bouncycastle.openpgp.PGPSecretKey;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code hg sign}: signs changesets with a GPG key and records the signature in {@code .hgsigs} (committed)
 * or, with {@link #setLocal}, in {@code .hg/localsigs} (never committed). The format and the signed text are
 * those of Mercurial's {@code gpg} extension, so {@code hg sigcheck} accepts what this command writes.
 *
 * <h2>Differences from the original, on purpose</h2>
 * <ul>
 *   <li>{@code hg sign --force} crashes in the original with {@code UnboundLocalError} (the variable
 *       {@code msigs} is only defined without {@code --force}); {@link #setForce} works here.</li>
 *   <li>All the checks that can refuse are made before {@code .hgsigs} is touched (as in the original, where the signing and
 *       the check of {@code .hgsigs} come before the file is written).</li>
 *   <li>Like the original, a failure after {@code .hgsigs} was written (adding it, the commit) leaves the file as it is: it is not
 *       restored. The commit holds {@code .hgsigs} only ({@code match=.hgsigs}); other changes stay uncommitted.</li>
 * </ul>
 */
public class SignCommand {

    private static final String SIGS_FILE = ".hgsigs";

    private final HgRepository repository;
    private byte[] nodeId;
    private GpgKeyring keyring;
    private String keySpec;
    private char[] passphrase;
    private boolean local;
    private boolean commit = true;
    private boolean force;
    private String message;
    private String author = "hg4j <hg4j@google.com>";
    private Instant now;

    /**
     * Creates the command.
     * @param repository the repository to sign in; must not be {@code null}
     */
    public SignCommand(HgRepository repository) {
        this.repository = repository;
    }

    /** The changeset to sign (20-byte node). Default: the working directory's parent. */
    public SignCommand setNodeId(byte[] nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    /** The keys to sign with; must contain a secret key (see {@link GpgKeyring#withSecretKeys}). */
    public SignCommand setKeyring(GpgKeyring keyring) {
        this.keyring = keyring;
        return this;
    }

    /** Which signing key, like {@code hg sign --key}: part of a user id, or a key id / fingerprint (suffix). */
    public SignCommand setKeySpec(String keySpec) {
        this.keySpec = keySpec;
        return this;
    }

    /** The key's passphrase; null or empty for an unprotected key. */
    public SignCommand setPassphrase(char[] passphrase) {
        this.passphrase = passphrase;
        return this;
    }

    /** {@code --local}: write {@code .hg/localsigs}, which is not versioned and never committed. */
    public SignCommand setLocal(boolean local) {
        this.local = local;
        return this;
    }

    /** {@code --no-commit} is {@code setCommit(false)}: write and track {@code .hgsigs} but do not commit. */
    public SignCommand setCommit(boolean commit) {
        this.commit = commit;
        return this;
    }

    /** {@code --force}: sign even though {@code .hgsigs} has uncommitted changes. */
    public SignCommand setForce(boolean force) {
        this.force = force;
        return this;
    }

    /** The commit message; default one line {@code Added signature for changeset <short>} per signed changeset. */
    public SignCommand setMessage(String message) {
        this.message = message;
        return this;
    }

    /**
     * Sets the author recorded for the commit of {@code .hgsigs}.
     * @param author the author string, normally {@code Name <email>}; default {@code hg4j <hg4j@google.com>}.
     *         Unused with {@link #setLocal} or when {@link #setCommit} is {@code false}
     * @return {@code this}
     */
    public SignCommand setAuthor(String author) {
        this.author = author;
        return this;
    }

    /** The signature creation time; default: now. */
    public SignCommand setSigningTime(Instant now) {
        this.now = now;
        return this;
    }

    /** @return the signature lines that were recorded */
    public List<HgSigEntry> call() throws IOException, HgLockException {
        if (keyring == null) {
            throw new IllegalStateException("A keyring with the signing key must be set");
        }
        // `with repo.wlock(): return _dosign(...)`: only the working copy lock for the whole command; the commit takes the store lock
        // itself and gives it back (the signing, the write of .hgsigs and the add are under the working copy lock alone)
        try (io.github.search5.hg4j.lib.HgLock wlock = repository.lockWorkingCopy()) {
            repository.refreshIfChangedOnDisk();

            // ---- everything that can refuse is checked before anything is written
            PGPSecretKey key = keyring.findSigningKey(keySpec);
            if (key == null) {
                throw new HgValidationException("no usable signing key found"
                        + (keySpec == null || keySpec.isBlank() ? "" : " for '" + keySpec + "'")
                        + (keyring.getNotes().isEmpty() ? "" : " (" + String.join("; ", keyring.getNotes()) + ")"));
            }
            List<String> nodes = nodesToSign();
            boolean tracked = !local;
            if (tracked) {
                Status status = new StatusCommand(repository).call();
                // `repo.status(match=msigs, unknown=True, ignored=True)`: a .hgsigs that is changed, or not tracked, counts
                if (!force && (touched(status, SIGS_FILE) || status.getUntracked().contains(SIGS_FILE))) {
                    throw new HgValidationException("working copy of .hgsigs is changed \n(please commit .hgsigs manually)");
                }
            }

            Instant signedAt = now != null ? now : Instant.now();
            List<HgSigEntry> entries = new ArrayList<>();
            for (String node : nodes) {
                entries.add(HgSigner.sign(node, key, passphrase, signedAt));
            }
            byte[] lines = HgSigsFile.format(entries);

            if (local) {
                Files.write(new File(repository.getHgDir(), "localsigs").toPath(), lines,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return entries;
            }

            File sigsFile = new File(repository.getDirectory(), SIGS_FILE);
            Files.write(sigsFile.toPath(), lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (!repository.getDirstate().getEntries().containsKey(SIGS_FILE)) {
                new AddCommand(repository).addFile(SIGS_FILE).call();
            }
            if (commit) {
                new CommitCommand(repository).setAuthor(author).setMessage(commitMessage(nodes)).setPatterns(SIGS_FILE).call();
            }
            return entries;
        }
    }

    private List<String> nodesToSign() throws IOException {
        List<String> nodes = new ArrayList<>();
        if (nodeId != null) {
            nodes.add(new NodeId(nodeId).toHex());
        } else {
            NodeId p1 = repository.getDirstate().getParent1Node();
            NodeId p2 = repository.getDirstate().getParent2Node();
            if (p2 != null && !p2.isNull()) {
                throw new HgValidationException("uncommitted merge - please provide a specific revision");
            }
            if (p1 == null || p1.isNull()) {
                throw new HgValidationException("the repository has no changeset to sign");
            }
            nodes.add(p1.toHex());
        }
        for (String node : nodes) {
            if (SignatureStore.changelogRevision(repository, node) < 0) {
                throw new HgRevisionNotFoundException(node);
            }
        }
        return nodes;
    }

    private String commitMessage(List<String> nodes) {
        if (message != null && !message.isEmpty()) {
            return message;
        }
        StringBuilder sb = new StringBuilder();
        for (String node : nodes) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("Added signature for changeset ").append(node, 0, 12);
        }
        return sb.toString();
    }

    private static boolean touched(Status status, String file) {
        return status.getAdded().contains(file) || status.getModified().contains(file)
                || status.getRemoved().contains(file);
    }

    @SafeVarargs
    private static List<String> concat(java.util.Collection<String>... parts) {
        List<String> all = new ArrayList<>();
        for (java.util.Collection<String> p : parts) {
            all.addAll(p);
        }
        return all;
    }
}
