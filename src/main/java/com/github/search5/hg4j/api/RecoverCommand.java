package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;

import java.io.File;
import java.util.List;

/**
 * Porcelain command equivalent to {@code hg recover}: explicitly checks for -- and, if found,
 * rolls back -- an interrupted transaction left behind by a crashed or killed mutating command
 * (a leftover {@code .hg/store/journal}), without waiting for the next lock-acquiring command to
 * trigger the same cleanup automatically via {@link HgRepository#checkAndPerformAutoRollback()}.
 *
 * <p>Real hg semantics verified against {@code hg} v7.2 on scratch repositories (2026-09-02):
 * <ul>
 *   <li>Nothing to recover: real hg prints {@code no interrupted transaction available} and
 *       exits 1.</li>
 *   <li>An interrupted transaction is present: real hg prints
 *       {@code rolling back interrupted transaction} (plus a note that the verify step was
 *       skipped, unless {@code --verify} was given) and exits 0 once the rollback completes.</li>
 *   <li>{@code hg recover --verify} performs the same rollback and then additionally runs
 *       {@code hg verify}, folding its result into recover's own exit code.</li>
 * </ul>
 * This command mirrors that distinction through {@link RecoverResult} rather than throwing for
 * the "nothing to recover" case, since that is an entirely normal outcome for a library caller
 * (e.g. a "recover before opening" health check) rather than an error.
 */
public class RecoverCommand {

    private final HgRepository repository;
    private boolean verify = false;

    public RecoverCommand(HgRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("Repository cannot be null");
        }
        this.repository = repository;
    }

    /**
     * When {@code true}, and only when an interrupted transaction was actually found and
     * successfully rolled back, additionally runs {@link VerifyCommand} afterward -- mirroring
     * real hg's {@code hg recover --verify} flag -- and reports its errors on the result.
     *
     * @return this command, for chaining
     */
    public RecoverCommand setVerify(boolean verify) {
        this.verify = verify;
        return this;
    }

    /**
     * Checks for an interrupted transaction and rolls it back if one is found, then returns a
     * {@link RecoverResult} describing what happened.
     */
    public RecoverResult call() {
        File journalFile = new File(repository.getStoreDir(), "journal");
        boolean hadJournal = journalFile.exists();
        if (!hadJournal) {
            // Matches real hg's "no interrupted transaction available": there is nothing to do.
            return new RecoverResult(false, true, null);
        }

        repository.checkAndPerformAutoRollback();

        // checkAndPerformAutoRollback() deletes the journal only once rollback succeeds and
        // retains it for retry on failure (see HgRepository#checkAndPerformAutoRollback), so its
        // absence afterward is the public, filesystem-observable signal of success.
        boolean success = !journalFile.exists();

        List<String> verifyErrors = null;
        if (verify && success) {
            verifyErrors = new VerifyCommand(repository).call();
        }

        return new RecoverResult(true, success, verifyErrors);
    }

    /**
     * Outcome of a {@link RecoverCommand} run.
     */
    public static final class RecoverResult {
        private final boolean interrupted;
        private final boolean success;
        private final List<String> verifyErrors;

        RecoverResult(boolean interrupted, boolean success, List<String> verifyErrors) {
            this.interrupted = interrupted;
            this.success = success;
            this.verifyErrors = verifyErrors;
        }

        /**
         * @return {@code true} if an interrupted transaction (a leftover journal) was found.
         * {@code false} corresponds to real hg's "no interrupted transaction available" / exit 1.
         */
        public boolean wasInterrupted() {
            return interrupted;
        }

        /**
         * @return {@code true} if there was nothing to recover, or an interrupted transaction was
         * found and successfully rolled back. {@code false} means a journal was found but the
         * rollback could not complete, and it has been retained on disk for a future retry.
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * @return the errors reported by {@link VerifyCommand} when {@code --verify} behavior was
         * requested via {@link RecoverCommand#setVerify(boolean)} and a rollback actually ran and
         * succeeded; {@code null} if verify was not requested, or was skipped because there was
         * nothing to recover or the rollback failed. An empty (non-null) list means verify ran and
         * found no errors.
         */
        public List<String> getVerifyErrors() {
            return verifyErrors;
        }
    }
}
