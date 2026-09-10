package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.errors.HgLockException;
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
 * <p>{@link #setLocal} and {@link #setRemove} mirror real hg's own {@code hg tag --local}/
 * {@code hg tag --remove}: a local tag is written to {@code .hg/localtags} instead of
 * {@code .hgtags} and is never committed (real hg's own
 * {@code tagsmod.tag()} skips the commit entirely when {@code local} is set, regardless of any
 * {@code --message}/commit-editor option -- there is nothing to commit, since
 * {@code .hg/localtags} is untracked). Removing a tag appends a nullid ({@code "0"} x 40) line for
 * that name to the same file it was defined in (real hg's own {@code rev_ = b'null'}), which
 * {@link TagsCommand} already recognizes as "deleted" and omits from its listing.</p>
 *
 * @apiNote Typically obtained via {@link Hg#tag()} on an open {@link Hg}
 *     instance rather than constructed directly.
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
     * Creates a tag command bound to the given repository.
     *
     * @param repository the repository to list, create, or remove tags in
     */
    public TagCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Sets the name of the tag to create or remove. When left unset, {@link #call()} lists the
     * existing tags instead.
     *
     * @param tagName the tag name to create or remove
     * @return this command, for chaining
     */
    public TagCommand setTagName(String tagName) {
        this.tagName = tagName;
        return this;
    }

    /**
     * Sets the node id the new tag should point to. Required when creating a tag (ignored when
     * {@link #setRemove} is set).
     *
     * @param nodeId the node id (at least 20 bytes) the tag should reference
     * @return this command, for chaining
     */
    public TagCommand setNodeId(byte[] nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    /**
     * Whether creating/removing a global tag should also commit the resulting {@code .hgtags}
     * change. Defaults to {@code true}; has no effect on local tags, which are never committed.
     *
     * @param commit {@code true} to commit the {@code .hgtags} change, {@code false} to leave it uncommitted
     * @return this command, for chaining
     */
    public TagCommand setCommit(boolean commit) {
        this.commit = commit;
        return this;
    }

    /**
     * {@code hg tag --local}: write to {@code .hg/localtags} (untracked, never committed) instead
     * of the versioned {@code .hgtags}.
     *
     * @param local {@code true} to write to {@code .hg/localtags} instead of the versioned {@code .hgtags}
     * @return this command, for chaining
     */
    public TagCommand setLocal(boolean local) {
        this.local = local;
        return this;
    }

    /**
     * {@code hg tag --remove}: append a nullid entry for {@link #setTagName}, marking it deleted.
     * No {@link #setNodeId} is required when this is set -- the nullid is used regardless of
     * whatever node, if any, was configured.
     *
     * @param remove {@code true} to remove the tag named by {@link #setTagName} instead of creating one
     * @return this command, for chaining
     */
    public TagCommand setRemove(boolean remove) {
        this.remove = remove;
        return this;
    }

    /**
     * {@code hg tag -f}/{@code --force}: allows moving a tag name that already resolves to a
     * non-null revision. Without this, real hg aborts with {@code tag '<name>' already exists
     * (use -f to force)} rather than silently overwriting -- {@link #call} reproduces that gate
     * for both global and
     * {@link #setLocal local} tags. Irrelevant when {@link #setRemove} is set: real hg lets an
     * existing tag be removed unconditionally, force or not.
     *
     * @param force {@code true} to allow moving a tag name that already resolves to a non-null revision
     * @return this command, for chaining
     */
    public TagCommand setForce(boolean force) {
        this.force = force;
        return this;
    }

    /**
     * Registers a hook run before the tag is written, with the ability to veto the operation by
     * returning {@code false}.
     *
     * @param hook the hook to run before creating or removing the tag; ignored if {@code null}
     * @return this command, for chaining
     */
    public TagCommand registerPreTagHook(HgHook hook) {
        if (hook != null) {
            this.preTagHooks.add(hook);
        }
        return this;
    }

    /**
     * Registers a hook run after the tag has been written.
     *
     * @param hook the hook to run after creating or removing the tag; ignored if {@code null}
     * @return this command, for chaining
     */
    public TagCommand registerPostTagHook(HgHook hook) {
        if (hook != null) {
            this.postTagHooks.add(hook);
        }
        return this;
    }

    /**
     * Lists the repository's tags, or creates/removes a tag when {@link #setTagName} was called.
     *
     * @return when {@link #setTagName} was not set, a map of tag name to hex node id for every
     *         tag defined in {@code .hgtags}; when a tag name was set, a single-entry map from
     *         that name to the hex node id it now resolves to (the nullid hex when removed)
     * @throws IOException if {@code .hgtags} or {@code .hg/localtags} cannot be read or written
     * @throws HgLockException if the store lock cannot be acquired while committing the tag change
     */
    public Map<String, String> call() throws IOException, HgLockException {
        File tagsFile = new File(repository.getDirectory(), ".hgtags");

        if (tagName != null && !tagName.isEmpty()) {
            String hex;
            if (remove) {
                hex = "0".repeat(40);
            } else {
                if (nodeId == null || nodeId.length < 20) {
                    throw new IllegalArgumentException("Valid NodeID must be specified for creating a tag");
                }
                hex = NodeIdUtil.toHex(nodeId).substring(0, 40);
            }

            // Real hg: `hg tag <existing-name>` without -f aborts instead of silently moving the
            // tag -- and the check spans the MERGED local+global namespace, not just the file
            // being written to: a local tag colliding with an existing global name (or vice
            // versa) is rejected the same way. Removal is exempt -- an existing tag can always
            // be removed regardless of `force`.
            if (!remove && !force) {
                Map<String, byte[]> existingGlobal = TagsCommand.readTagFile(tagsFile);
                Map<String, byte[]> existingLocal = TagsCommand.readTagFile(new File(repository.getHgDir(), "localtags"));
                byte[] existingNode = existingLocal.containsKey(tagName) ? existingLocal.get(tagName) : existingGlobal.get(tagName);
                if (existingNode != null && !NodeIdUtil.isAllZero(existingNode)) {
                    throw new HgValidationException("tag '" + tagName + "' already exists (use -f to force)");
                }
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

            String entry = hex + " " + tagName + "\n";

            if (local) {
                // .hg/localtags: untracked, never committed (real hg does not add/commit it either).
                File localTagsFile = new File(repository.getHgDir(), "localtags");
                Files.writeString(localTagsFile.toPath(), entry, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                // Append to .hgtags
                Files.writeString(tagsFile.toPath(), entry, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);

                // Add .hgtags to tracking
                new AddCommand(repository).call();

                // Commit the tag. Real hg's own tag commit message uses the short (12-hex-digit)
                // node form (mercurial.node.short()) -- not the full 40-digit hex.
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
