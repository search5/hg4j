package io.github.search5.hg4j.errors;

import java.io.IOException;

/**
 * Checked exception thrown on failures in repository state, working directory integrity, or validation checks.
 * Extends java.io.IOException to maintain API compatibility with existing I/O exceptions.
 *
 * @apiNote The general-purpose validation failure across the porcelain layer — thrown by most
 *     mutating commands (e.g. {@code AddCommand}, {@code CommitCommand}, {@code UpdateCommand},
 *     {@code MergeCommand}, {@code RebaseCommand}, {@code ShelveCommand}, {@code PushCommand},
 *     {@code TagCommand}, {@code BookmarkCommand}) when a precondition on repository or working
 *     directory state is not met (e.g. dirty working directory, invalid argument combination,
 *     unsupported requirement). {@link io.github.search5.hg4j.dirstate.DirstateV2Serializer} and
 *     {@link io.github.search5.hg4j.treewalk.SparseConfig} also throw it while validating their
 *     own on-disk state. {@link HgPushRacedException} is a distinguishable subtype of this
 *     exception for the specific case of a concurrent-push race.
 */
public class HgValidationException extends IOException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a validation exception with the given message.
     *
     * @param message a human-readable description of the failed precondition
     */
    public HgValidationException(String message) {
        super(message);
    }

    /**
     * Creates a validation exception with the given message, wrapping an underlying cause.
     *
     * @param message a human-readable description of the failed precondition
     * @param cause the underlying cause
     */
    public HgValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
