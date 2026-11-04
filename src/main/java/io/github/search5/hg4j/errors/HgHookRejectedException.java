package io.github.search5.hg4j.errors;

import java.io.IOException;

/**
 * A hook refused the operation. When {@link #isRolledBack()} the hook ran after the changes had been made (Mercurial's
 * {@code pretxnchangegroup}), and they have been undone again.
 */
public class HgHookRejectedException extends IOException {

    private final boolean rolledBack;

    /**
     * Creates the exception.
     *
     * @param message the detail message, for example {@code pretxnchangegroup hook failed}
     * @param rolledBack {@code true} if the hook ran after the changes were made and they have been undone again
     */
    public HgHookRejectedException(String message, boolean rolledBack) {
        super(message);
        this.rolledBack = rolledBack;
    }

    /**
     * Tells whether the changes the hook ran after have been undone again.
     *
     * @return {@code true} if the repository was rolled back to its state before the operation; {@code false} if the hook refused before anything was changed
     */
    public boolean isRolledBack() {
        return rolledBack;
    }
}
