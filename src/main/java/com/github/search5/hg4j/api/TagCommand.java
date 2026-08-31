package com.github.search5.hg4j.api;

import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.core.NodeIdUtil;
import com.github.search5.hg4j.errors.HgLockException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Commands for tag management (listing tags or creating tags).
 */
public class TagCommand {
    private final HgRepository repository;
    private String tagName;
    private byte[] nodeId;
    private boolean commit = true;
    private final List<HgHook> preTagHooks = new java.util.ArrayList<>();
    private final List<HgHook> postTagHooks = new java.util.ArrayList<>();

    public TagCommand(HgRepository repository) {
        this.repository = repository;
    }

    public TagCommand setTagName(String tagName) {
        this.tagName = tagName;
        return this;
    }

    public TagCommand setNodeId(byte[] nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    public TagCommand setCommit(boolean commit) {
        this.commit = commit;
        return this;
    }

    public TagCommand registerPreTagHook(HgHook hook) {
        if (hook != null) {
            this.preTagHooks.add(hook);
        }
        return this;
    }

    public TagCommand registerPostTagHook(HgHook hook) {
        if (hook != null) {
            this.postTagHooks.add(hook);
        }
        return this;
    }

    public Map<String, String> call() throws IOException, HgLockException {
        File tagsFile = new File(repository.getDirectory(), ".hgtags");

        if (tagName != null && !tagName.isEmpty()) {
            if (nodeId == null || nodeId.length < 20) {
                throw new IllegalArgumentException("Valid NodeID must be specified for creating a tag");
            }
            String hex = NodeIdUtil.toHex(nodeId).substring(0, 40);

            // 1. PRE_TAG hooks trigger
            if (!preTagHooks.isEmpty()) {
                Map<String, Object> ctx = new java.util.HashMap<>();
                ctx.put("repository", repository);
                ctx.put("tag", tagName);
                ctx.put("node", nodeId);
                for (HgHook hook : preTagHooks) {
                    if (!hook.run(ctx)) {
                        throw new com.github.search5.hg4j.errors.HgValidationException("Tag creation rejected by PRE_TAG hook: " + tagName);
                    }
                }
            }

            String entry = hex + " " + tagName + "\n";

            // Append to .hgtags
            Files.writeString(tagsFile.toPath(), entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            // Add .hgtags to tracking
            new AddCommand(repository).call();

            // Commit the tag
            if (commit) {
                new CommitCommand(repository)
                        .setAuthor("hg4j <hg4j@google.com>")
                        .setMessage("Added tag " + tagName + " for changeset " + hex)
                        .call();
            }

            // 2. POST_TAG hooks trigger
            if (!postTagHooks.isEmpty()) {
                Map<String, Object> ctx = new java.util.HashMap<>();
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
