package io.github.search5.hg4j.api;

import java.io.IOException;
import java.util.Map;

/**
 * JGit-style Mercurial SCM hook interface.
 * Allows intercepting events before and after SCM transactions within Java applications for validation or post-processing.
 *
 * <p>The keys of the context map depend on where the hook is registered. {@code "repository"} (the {@code HgRepository} that is
 * changed) is always present.</p>
 * <ul>
 *   <li>Changegroup hooks of the wire servers and of a local push ({@code prechangegroup}, {@code pretxnchangegroup},
 *       {@code changegroup}): {@code "nodes"}, a {@code List<String>} of the 40-character hex node ids of the changesets of the push,
 *       in the order they are applied. The {@code pretxnchangegroup} and {@code changegroup} hooks run only if at least one changeset
 *       is new to the repository, as in the original, and then list the new changesets only; {@code prechangegroup} lists every
 *       changeset of the bundle. The first and last node and the count are read from the list. The {@code changegroup} hook runs
 *       after the locks are released and its result is ignored.</li>
 *   <li>Pushkey hooks ({@code prepushkey}, {@code pushkey}): {@code "namespace"} (for example {@code "bookmarks"}), {@code "key"}
 *       (the name), {@code "old"} and {@code "new"} (hex node ids; the empty string, never {@code null}, for a bookmark that is
 *       created or deleted). The post hook runs only after a successful update.</li>
 *   <li>A server changegroup hook ({@code prechangegroup}, {@code pretxnchangegroup}) can refuse a push with a reason by throwing
 *       {@link io.github.search5.hg4j.errors.HgHookAbortException}; the client is told {@code error: <hook> hook failed: <reason>} and
 *       {@code abort: <reason>}, as with a Python hook that raises {@code error.Abort}. Returning {@code false} refuses without a reason.</li>
 *   <li>Commit hooks: {@code "author"}, {@code "message"} and, after the commit, {@code "commitNode"}.</li>
 * </ul>
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
