package io.github.search5.hg4j.api;

import io.github.search5.hg4j.gpg.GpgKeyring;
import io.github.search5.hg4j.gpg.HgSigResult;
import io.github.search5.hg4j.gpg.HgSigVerifier;
import io.github.search5.hg4j.lib.HgRepository;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code hg sigs}: lists the signed changesets, newest revision first, with each valid signer
 * (behaviour of {@code hgext/gpg.py}'s {@code sigs}).
 *
 * <p>Like the original it prints nothing for a signature that is not valid; instead of printing, the reasons
 * are collected in {@link Result#warnings()} with the same wording and the {@code <file>:<line>} prefix, so
 * the caller can show them (notes about the keyring itself stay in {@link GpgKeyring#getNotes()}). A line that is
 * not a signature line at all is an {@link IOException}
 * ({@code HgSigFormatException}) rather than being skipped.</p>
 */
public class SigsCommand {

    /** A valid signature of one changeset. */
    public record Signed(int revision, String nodeHex, HgSigResult signature, String source) {
    }

    /** @param signatures valid signatures, highest revision first; @param warnings what hg would have printed */
    public record Result(List<Signed> signatures, List<String> warnings) {
    }

    private final HgRepository repository;
    private GpgKeyring keyring;
    private Instant now;

    /**
     * Creates the command.
     * @param repository the repository to read signatures from; must not be {@code null}
     */
    public SigsCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Sets the keyring holding the public keys used to verify the signatures.
     * @param keyring the keyring; must be non-null before {@link #call()}
     * @return {@code this}
     */
    public SigsCommand setKeyring(GpgKeyring keyring) {
        this.keyring = keyring;
        return this;
    }

    /** The time expiry is judged against; default: now. */
    public SigsCommand setNow(Instant now) {
        this.now = now;
        return this;
    }

    /**
     * Verifies every signature found in the repository's signature files.
     * <p>
     * Signatures for changesets that are not in the changelog only produce a warning. Expiry is judged at the time given
     * by {@link #setNow} (default: now).
     * @return the valid signatures, highest revision first (file order within a revision), and the warnings
     * @throws IllegalStateException if no keyring was set
     * @throws IOException if reading the repository fails or a signature line is malformed
     */
    public Result call() throws IOException {
        if (keyring == null) {
            throw new IllegalStateException("A keyring must be set");
        }
        repository.refreshIfChangedOnDisk();
        Instant at = now != null ? now : Instant.now();
        List<String> warnings = new ArrayList<>();
        List<Signed> signed = new ArrayList<>();
        for (SignatureStore.Located located : SignatureStore.readAll(repository)) {
            int rev = SignatureStore.changelogRevision(repository, located.entry().getNodeHex());
            if (rev < 0) {
                warnings.add(located.where() + " node does not exist");
                continue;
            }
            HgSigResult result = HgSigVerifier.verify(located.entry(), keyring, at);
            SigWarnings.addFor(warnings, located.where(), result);
            if (result.isValid()) {
                signed.add(new Signed(rev, located.entry().getNodeHex(), result, located.where()));
            }
        }
        signed.sort(Comparator.comparingInt(Signed::revision).reversed()); // stable: file order within a revision
        return new Result(signed, warnings);
    }
}
