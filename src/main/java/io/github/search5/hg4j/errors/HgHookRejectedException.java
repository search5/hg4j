package io.github.search5.hg4j.errors;

import java.io.IOException;

/**
 * A hook refused the operation. When {@link #isRolledBack()} the hook ran after the changes had been made (Mercurial's
 * {@code pretxnchangegroup}), and they have been undone again.
 */
public class HgHookRejectedException extends IOException {

    private final boolean rolledBack;
    private final String hookOutput;

    /**
     * Creates the exception.
     *
     * @param message the detail message, for example {@code pretxnchangegroup hook failed}
     * @param rolledBack {@code true} if the hook ran after the changes were made and they have been undone again
     */
    public HgHookRejectedException(String message, boolean rolledBack) {
        this(message, rolledBack, "");
    }

    /**
     * Creates the exception for a hook that gave a reason (see {@link HgHookAbortException}).
     *
     * @param message the detail message, what the client is told after {@code abort:}
     * @param rolledBack {@code true} if the hook ran after the changes were made and they have been undone again
     * @param hookOutput what the server prints for the client before it reports the abort ({@code error: ... hook failed: ...})
     */
    public HgHookRejectedException(String message, boolean rolledBack, String hookOutput) {
        super(message);
        this.rolledBack = rolledBack;
        this.hookOutput = hookOutput;
    }

    /**
     * What the refusing hook made the server print for the client (empty if the hook gave no reason).
     *
     * @return the text, with a trailing newline when it is not empty
     */
    public String getHookOutput() {
        return hookOutput;
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
