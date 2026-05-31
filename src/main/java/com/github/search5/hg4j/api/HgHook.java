package com.github.search5.hg4j.api;

import java.io.IOException;
import java.util.Map;

/**
 * JGit-style Mercurial SCM hook interface.
 * Allows intercepting events before and after SCM transactions within Java applications for validation or post-processing.
 */
@FunctionalInterface
public interface HgHook {
    /**
     * Triggered at specific hook phases during SCM operations.
     *
     * @param context Context data required for hook execution (e.g., "author", "message", "commitNode", "repository")
     * @return true to allow the operation, false to block it and abort the transaction (raising an exception)
     * @throws IOException If an I/O error occurs or the hook execution fails
     */
    boolean run(Map<String, Object> context) throws IOException;
}
