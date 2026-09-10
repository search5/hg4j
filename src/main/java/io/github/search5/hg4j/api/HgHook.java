package io.github.search5.hg4j.api;

import java.io.IOException;
import java.util.Map;

/**
 * JGit-style Mercurial SCM hook interface.
 * Allows intercepting events before and after SCM transactions within Java applications for validation or post-processing.
 *
 * @apiNote Registered against a specific {@link HgHookType} via {@link Hg#registerHook}; {@link
 *     ProcessHook} is the ready-made implementation for shelling out to an external script.
 *     {@link Hg}'s per-command factory methods (e.g. {@link Hg#commit()}, {@link Hg#push()})
 *     wire the matching registered hooks onto the returned command instance, which then invokes
 *     them itself at its own pre/post points during {@code call()}; {@code HgLocalClient}
 *     separately accepts a caller-supplied {@code CHANGEGROUP} hook list to run around applying
 *     an incoming changegroup.
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
