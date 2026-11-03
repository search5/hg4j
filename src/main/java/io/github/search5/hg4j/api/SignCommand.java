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
 *   <li>All preconditions are checked BEFORE any file is touched, so a refused signing leaves the working
 *       copy as it was. The original appends to {@code .hgsigs} first and can leave it modified when it then
 *       aborts.</li>
 *   <li><b>Limitation:</b> the commit made here cannot be restricted to {@code .hgsigs} ({@link CommitCommand}
 *       has no path filter, real hg uses {@code match=.hgsigs}). So when other tracked files have uncommitted
 *       changes the command refuses to commit and tells you to commit them first or use
 *       {@link #setCommit(boolean) setCommit(false)}.</li>
 *   <li>If the commit fails after {@code .hgsigs} was written, the file is restored.</li>
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
            if (!force && touched(status, SIGS_FILE)) {
                throw new HgValidationException("working copy of .hgsigs is changed (please commit .hgsigs manually"
                        + " or use force)");
            }
            if (commit) {
                List<String> others = new ArrayList<>();
                for (String f : concat(status.getAdded(), status.getModified(), status.getRemoved())) {
                    if (!f.equals(SIGS_FILE)) {
                        others.add(f);
                    }
                }
                if (!others.isEmpty()) {
                    throw new HgValidationException("cannot commit the signature: other files have uncommitted"
                            + " changes (" + String.join(", ", others) + "); commit them first or sign without"
                            + " committing (setCommit(false))");
                }
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
        boolean existed = sigsFile.exists();
        byte[] before = existed ? Files.readAllBytes(sigsFile.toPath()) : null;
        Files.write(sigsFile.toPath(), lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try {
            new AddCommand(repository).addFile(SIGS_FILE).call();
            if (commit) {
                new CommitCommand(repository).setAuthor(author).setMessage(commitMessage(nodes)).call();
            }
        } catch (IOException | RuntimeException | HgLockException e) {
            restore(sigsFile, existed, before, e);
            throw e;
        }
        return entries;
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

    private void restore(File sigsFile, boolean existed, byte[] before, Throwable cause) {
        try {
            if (existed) {
                Files.write(sigsFile.toPath(), before);
            } else {
                new ForgetCommand(repository).setFile(SIGS_FILE).call();
                Files.deleteIfExists(sigsFile.toPath());
            }
        } catch (IOException | HgLockException restoreFailure) {
            cause.addSuppressed(restoreFailure);
        }
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
