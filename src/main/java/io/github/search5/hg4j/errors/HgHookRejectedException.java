package io.github.search5.hg4j.errors;

import java.io.IOException;

/**
 * A hook refused the operation. When {@link #isRolledBack()} the hook ran after the changes had been made (Mercurial's
 * {@code pretxnchangegroup}), and they have been undone again.
 */
public class HgHookRejectedException extends IOException {

    private final boolean rolledBack;

    public HgHookRejectedException(String message, boolean rolledBack) {
        super(message);
        this.rolledBack = rolledBack;
    }

    public boolean isRolledBack() {
        return rolledBack;
    }
}
