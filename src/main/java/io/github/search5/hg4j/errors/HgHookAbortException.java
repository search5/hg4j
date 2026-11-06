package io.github.search5.hg4j.errors;

import java.io.IOException;

/**
 * Thrown by a server {@link io.github.search5.hg4j.api.HgHook} to refuse a push and tell the client why, like a Python hook of the
 * original raising {@code error.Abort("reason")}. The client sees {@code error: <hook> hook failed: <reason>} among the output of the
 * server and then {@code abort: <reason>}, where {@code <hook>} is the type of the hook ({@code prechangegroup} or
 * {@code pretxnchangegroup}). A hook that only returns {@code false} refuses without a reason ({@code abort: <hook> hook failed}).
 */
public class HgHookAbortException extends IOException {

    /**
     * Creates the exception.
     *
     * @param reason what the client is told, for example {@code protected branch: default}
     */
    public HgHookAbortException(String reason) {
        super(reason);
    }
}
