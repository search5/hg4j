package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.storage.Revlog;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.search5.hg4j.errors.HgValidationException;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Commands for tag management (listing tags or creating tags).
 *
 * <p>{@link #setLocal} and {@link #setRemove} (added 2026-09-04, verified directly against real hg
 * 7.2.2's own {@code hg tag --local}/{@code hg tag --remove}): a local tag is written to
 * {@code .hg/localtags} instead of {@code .hgtags} and is never committed (real hg's own
 * {@code tagsmod.tag()} skips the commit entirely when {@code local} is set, regardless of any
 * {@code --message}/commit-editor option -- there is nothing to commit, since
 * {@code .hg/localtags} is untracked). Removing a tag appends a nullid ({@code "0"} x 40) line for
 * that name to the same file it was defined in (real hg's own {@code rev_ = b'null'}), which
 * {@link TagsCommand} already recognizes as "deleted" and omits from its listing.</p>
 */
public class TagCommand {
    private final HgRepository repository;
    private String tagName;
    private byte[] nodeId;
    private boolean commit = true;
    private boolean local = false;
    private boolean remove = false;
    private boolean force = false;
    private final List<HgHook> preTagHooks = new ArrayList<>();
    private final List<HgHook> postTagHooks = new ArrayList<>();

    /**
     * Creates the command.
     * @param repository the repository to tag in; must not be {@code null}
     */
    public TagCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Sets the name of the tag to create, move or remove.
     * @param tagName the tag name; {@code null} or empty (the default) makes {@link #call()} list tags instead
     * @return {@code this}
     */
    public TagCommand setTagName(String tagName) {
        this.tagName = tagName;
        return this;
    }

    /**
     * Sets the changeset to tag.
     * @param nodeId the node id (at least 20 bytes; the first 40 hex digits of it are used); required when creating a
     *         tag, ignored when {@link #setRemove} is set
     * @return {@code this}
     */
    public TagCommand setNodeId(byte[] nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    /**
     * Sets whether a global tag is committed right after {@code .hgtags} is updated.
     * @param commit {@code true} (the default) to commit; {@code false} leaves {@code .hgtags} changed but
     *         uncommitted. Has no effect for {@link #setLocal local} tags, which are never committed
     * @return {@code this}
     */
    public TagCommand setCommit(boolean commit) {
        this.commit = commit;
        return this;
    }

    /**
     * {@code hg tag --local}: write to {@code .hg/localtags} (untracked, never committed) instead
     * of the versioned {@code .hgtags}.
     */
    public TagCommand setLocal(boolean local) {
        this.local = local;
        return this;
    }

    /**
     * {@code hg tag --remove}: append a nullid entry for {@link #setTagName}, marking it deleted.
     * No {@link #setNodeId} is required when this is set -- the nullid is used regardless of
     * whatever node, if any, was configured.
     */
    public TagCommand setRemove(boolean remove) {
        this.remove = remove;
        return this;
    }

    /**
     * {@code hg tag -f}/{@code --force}: allows moving a tag name that already resolves to a
     * non-null revision. Without this, real hg 7.2.2 aborts with {@code tag '<name>' already
     * exists (use -f to force)} (verified directly against the CLI, 2026-09-04) rather than
     * silently overwriting -- {@link #call} now reproduces that gate for both global and
     * {@link #setLocal local} tags. Irrelevant when {@link #setRemove} is set: real hg lets an
     * existing tag be removed unconditionally, force or not.
     */
    public TagCommand setForce(boolean force) {
        this.force = force;
        return this;
    }

    /**
     * Registers a hook run before the tag is written; if it returns {@code false} the call fails.
     * @param hook the hook; {@code null} is ignored
     * @return {@code this}
     */
    public TagCommand registerPreTagHook(HgHook hook) {
        if (hook != null) {
            this.preTagHooks.add(hook);
        }
        return this;
    }

    /**
     * Registers a hook run after the tag was written (and committed).
     * @param hook the hook; {@code null} is ignored
     * @return {@code this}
     */
    public TagCommand registerPostTagHook(HgHook hook) {
        if (hook != null) {
            this.postTagHooks.add(hook);
        }
        return this;
    }

    /**
     * Creates, moves or removes a tag, or lists the tags of {@code .hgtags} when no tag name is set.
     * <p>
     * Takes the working copy lock and the store lock (waiting up to the repository's push lock timeout). With a tag
     * name, a line is appended to {@code .hgtags} (or to {@code .hg/localtags} with {@link #setLocal}); for a global tag
     * the file is added to the dirstate and, if {@link #setCommit} is on, committed with author
     * {@code hg4j <hg4j@google.com>} and message {@code Added tag NAME for changeset SHORT} (or
     * {@code Removed tag NAME}). Pre-tag hooks run before anything is written and can veto; post-tag hooks run last, and
     * exceptions from them propagate. The hook context holds {@code repository}, {@code tag} and {@code node}.
     * @return when a tag name was set, a single entry of tag name to the 40-character hex node written (all zeros for
     *         a removal); otherwise the tag name to hex node pairs of {@code .hgtags} in file order, with later lines
     *         replacing earlier ones, and an empty map if the file does not exist
     * @throws IllegalArgumentException if a tag is to be created and no node id of at least 20 bytes was set
     * @throws io.github.search5.hg4j.errors.HgValidationException (an {@link IOException}) if the tag already exists
     *         and neither force nor remove is set, or a pre-tag hook returns {@code false}
     * @throws IOException if reading or writing the tag files, or committing, fails
     * @throws HgLockException if a lock cannot be acquired within the timeout
     */
    public Map<String, String> call() throws IOException, HgLockException {
        // Like real hg, the locks are taken before the first read of the repository state and released when the command ends.
        int lockTimeout = repository.resolvePushLockTimeoutMs();
        try (io.github.search5.hg4j.lib.HgLock wlock = repository.lockWorkingCopy(lockTimeout);
             io.github.search5.hg4j.lib.HgLock storeLock = repository.lockStore(lockTimeout)) {
            File tagsFile = new File(repository.getDirectory(), ".hgtags");

            if (tagName != null && !tagName.isEmpty()) {
                tagName = checkedName(tagName);
                String hex;
                if (remove) {
                    hex = "0".repeat(40);
                } else {
                    if (nodeId == null || nodeId.length < 20) {
                        throw new IllegalArgumentException("Valid NodeID must be specified for creating a tag");
                    }
                    hex = NodeIdUtil.toHex(nodeId).substring(0, 40);
                }

                // commands.tag: the checks in the original's order, on the tags of the heads and .hg/localtags (repo.tags(), repo.tagtype())
                Revlog changelog = repository.getRevlog(new File(repository.getStoreDir(), "00changelog.i"),
                        new File(repository.getStoreDir(), "00changelog.d"));
                Map<String, byte[]>[] raw = new TagsCommand(repository).readRaw();
                if (remove) {
                    String name = tagName;
                    byte[] current = raw[0].get(name);
                    boolean known = current != null && (NodeIdUtil.isAllZero(current) || changelog.findRevision(current) >= 0);
                    if (known && !raw[1].containsKey(name) && NodeIdUtil.isAllZero(current)) {
                        throw new HgValidationException("tag '" + name + "' is already removed");
                    }
                    if (!known) {
                        throw new HgValidationException("tag '" + name + "' does not exist");
                    }
                    if (raw[1].containsKey(name) != local) {
                        throw new HgValidationException("tag '" + name + "' is not a " + (local ? "local" : "global") + " tag");
                    }
                } else if (!force && liveNode(raw, changelog, tagName) != null) {
                    throw new HgValidationException("tag '" + tagName + "' already exists (use -f to force)");
                }
                if (!local) {
                    if (!repository.getDirstate().getParent2Node().isNull()) {
                        throw new HgValidationException("uncommitted merge");
                    }
                    if (!force) {
                        String branch = repository.getBranch();
                        List<String> branchHeads = new HeadsCommand(repository).setBranch(branch).call();
                        if (!branchHeads.isEmpty() && !branchHeads.contains(repository.getDirstate().getParent1Node().toHex())) {
                            throw new HgValidationException("working directory is not at a branch head (use -f to force)");
                        }
                    }
                }
                if (!remove && NodeIdUtil.isAllZero(nodeId)) {
                    throw new HgValidationException("cannot tag null revision");
                }
                if (!local && commit) {
                    checkHgtagsUnchanged(tagsFile, changelog);
                }

                // 1. PRE_TAG hooks trigger
                if (!preTagHooks.isEmpty()) {
                    Map<String, Object> ctx = new HashMap<>();
                    ctx.put("repository", repository);
                    ctx.put("tag", tagName);
                    ctx.put("node", nodeId);
                    for (HgHook hook : preTagHooks) {
                        if (!hook.run(ctx)) {
                            throw new HgValidationException("Tag creation rejected by PRE_TAG hook: " + tagName);
                        }
                    }
                }

                // tags._tag.writetags: a name that is known (even a removed one) gets the node it had before as a line of its own first
                File target = local ? new File(repository.getHgDir(), "localtags") : tagsFile;
                StringBuilder entry = new StringBuilder();
                if (target.exists() && target.length() > 0) {
                    byte[] previous = Files.readAllBytes(target.toPath());
                    if (previous[previous.length - 1] != '\n') {
                        entry.append('\n');
                    }
                }
                if (raw[0].containsKey(tagName)) {
                    byte[] old = liveNode(raw, changelog, tagName);
                    entry.append(old == null ? "0".repeat(40) : NodeIdUtil.toHex(old).substring(0, 40)).append(' ').append(tagName).append('\n');
                }
                entry.append(hex).append(' ').append(tagName).append('\n');

                if (local) {
                    // .hg/localtags: untracked, never committed (real hg does not add/commit it either).
                    File localTagsFile = new File(repository.getHgDir(), "localtags");
                    Files.writeString(localTagsFile.toPath(), entry.toString(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } else {
                    // Append to .hgtags
                    Files.writeString(tagsFile.toPath(), entry.toString(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);

                    // Add .hgtags to tracking
                    new AddCommand(repository).call();

                    // Commit the tag. Real hg's own tag commit message uses the short (12-hex-digit)
                    // node form (mercurial.node.short()), verified directly against hg 7.2.2
                    // (2026-09-04) -- not the full 40-digit hex.
                    if (commit) {
                        String message = remove
                                ? "Removed tag " + tagName
                                : "Added tag " + tagName + " for changeset " + hex.substring(0, 12);
                        new CommitCommand(repository)
                                .setAuthor("hg4j <hg4j@google.com>")
                                .setMessage(message)
                                .call();
                    }
                }

                // 2. POST_TAG hooks trigger
                if (!postTagHooks.isEmpty()) {
                    Map<String, Object> ctx = new HashMap<>();
                    ctx.put("repository", repository);
                    ctx.put("tag", tagName);
                    ctx.put("node", nodeId);
                    for (HgHook hook : postTagHooks) {
                        hook.run(ctx);
                    }
                }

                Map<String, String> result = new LinkedHashMap<>();
                result.put(tagName, hex);
                return result;
            }


            // List tags
            if (!tagsFile.exists()) {
                return Collections.emptyMap();
            }

            Map<String, String> tags = new LinkedHashMap<>();
            List<String> lines = Files.readAllLines(tagsFile.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int spaceIdx = line.indexOf(' ');
                if (spaceIdx != -1) {
                    String node = line.substring(0, spaceIdx).trim();
                    String name = line.substring(spaceIdx + 1).trim();
                    tags.put(name, node);
                }
            }
            return tags;
        }
    }



    /** {@code scmutil.checknewlabel} on the stripped name, then the whitespace check of {@code commands.tag}. */
    private static String checkedName(String given) throws IOException {
        String name = given.strip();
        if (name.equals("tip") || name.equals(".") || name.equals("null")) {
            throw new HgValidationException("the name '" + name + "' is reserved");
        }
        for (char c : new char[] {':', '\0', '\n', '\r'}) {
            if (name.indexOf(c) >= 0) {
                String shown = c == ':' ? ":" : c == '\0' ? "\\x00" : c == '\n' ? "\\n" : "\\r";
                throw new HgValidationException("'" + shown + "' cannot be used in a name");
            }
        }
        if (name.matches("[+-]?[0-9]+")) {
            throw new HgValidationException("cannot use an integer as a name");
        }
        if (name.isEmpty()) {
            throw new HgValidationException("tag names cannot consist entirely of whitespace");
        }
        return name;
    }

    /** The node {@code repo.tags()} has for the name: not a removed tag and not a node the repository does not have. */
    private static byte[] liveNode(Map<String, byte[]>[] raw, Revlog changelog, String name) throws IOException {
        byte[] node = raw[0].get(name);
        if (node == null || NodeIdUtil.isAllZero(node) || changelog.findRevision(node) < 0) {
            return null;
        }
        return node;
    }

    /** {@code tags.tag}: a working copy {@code .hgtags} that differs from the committed one is not touched. */
    private void checkHgtagsUnchanged(File tagsFile, Revlog changelog) throws IOException {
        if (!tagsFile.exists()) {
            return;
        }
        String parent = repository.getDirstate().getParent1Node().toHex();
        boolean same = false;
        int rev = changelog.findRevision(NodeIdUtil.fromHex(parent));
        if (rev >= 0) {
            MergeCommand helper = new MergeCommand(repository);
            String entry = helper.loadManifestAtCommit(changelog, repository.getManifestRevlog(), rev).get(".hgtags");
            same = entry != null && java.util.Arrays.equals(helper.getFileRevisionContent(".hgtags", entry), Files.readAllBytes(tagsFile.toPath()));
        }
        if (!same) {
            throw new HgValidationException("working copy of .hgtags is changed");
        }
    }
}
