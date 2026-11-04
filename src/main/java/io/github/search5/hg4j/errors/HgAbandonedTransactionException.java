package io.github.search5.hg4j.errors;

/**
 * An interrupted transaction ({@code store/journal}) is waiting to be recovered: what Mercurial's {@code repo.transaction()} refuses with
 * ({@code abandoned transaction found}, hint {@code run 'hg recover' to clean up transaction}). Nothing is recovered implicitly; the caller
 * runs {@code RecoverCommand} (hg's {@code recover}).
 */
public class HgAbandonedTransactionException extends HgLockException {

    public HgAbandonedTransactionException() {
        super("lock", "abandoned transaction found\n(hint: run 'hg recover' to clean up transaction)");
    }
}
