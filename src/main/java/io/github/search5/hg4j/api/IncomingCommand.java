package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.transport.HgRemoteConnection;
import io.github.search5.hg4j.transport.HgRemoteConnectionFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.diff.DeltaEngine;
import java.io.ByteArrayInputStream;

/**
 * Incoming command for identifying changesets present in the remote repository
 * but not yet pulled into the local repository.
 */
public class IncomingCommand {
    private final HgRepository repository;
    private String sourceUrl;

    public IncomingCommand(HgRepository repository) {
        this.repository = repository;
    }

    public IncomingCommand setSource(String sourceUrl) {
        this.sourceUrl = sourceUrl;
        return this;
    }

    /**
     * Executes incoming changeset detection.
     * Contacts remote server via standard heads protocol and compares graph ancestors.
     *
     * @return List of incoming commit metadata headers
     * @throws IOException if network or handshake protocol fails
     */
    public List<String> call() throws IOException {
        if (sourceUrl == null || sourceUrl.isEmpty()) {
            throw new IllegalArgumentException("Source URL must be specified for incoming changesets query");
        }

        List<byte[]> remoteHeads = new ArrayList<>();
        List<String> remoteHeadsHex = new ArrayList<>();
        try (HgRemoteConnection client = HgRemoteConnectionFactory.createConnection(sourceUrl)) {
            List<String> remoteHeadsStr = client.getHeads();
            if (remoteHeadsStr != null) {
                for (String headStr : remoteHeadsStr) {
                    remoteHeads.add(NodeIdUtil.fromHex(headStr));
                    remoteHeadsHex.add(headStr);
                }
            }
        } catch (Exception e) {
            throw new IOException("Remote connection failed for " + sourceUrl, e);
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        List<String> incomingMessages = new ArrayList<>();
        List<String> missingHeads = new ArrayList<>();

        for (byte[] head : remoteHeads) {
            if (changelog.findRevision(head) == -1) {
                missingHeads.add(NodeIdUtil.toHex(head));
            }
        }

        if (missingHeads.isEmpty()) {
            incomingMessages.add("no incoming changes found");
            return incomingMessages;
        }

        // Fetch the missing changegroup content from the remote server -- via getbundle whenever
        // the remote supports it (matching real hg's own modern client, and required to avoid a
        // real landmine in real hg's legacy `changegroup` wire command, see
        // FetchCommand#downloadChangegroupBundle's javadoc: calling it with an always-empty roots
        // list against a non-empty remote used to make IncomingCommand fail against literally any
        // real hg server that had content, backlog item 39 wave 5).
        try (HgRemoteConnection client = HgRemoteConnectionFactory.createConnection(sourceUrl)) {
            List<String> caps = client.getCapabilities();
            List<String> common = FetchCommand.computeLocalLeafHexes(changelog);
            FetchCommand.DownloadedChangegroup downloaded =
                    FetchCommand.downloadChangegroupBundle(client, caps, common, remoteHeadsHex);
            if (downloaded != null) {
                try (ByteArrayInputStream bin = new ByteArrayInputStream(downloaded.changegroupBytes)) {
                    ChangegroupParser.ChangegroupBundle bundle = ChangegroupParser.parseBundle(bin, downloaded.cgVersion);
                    if (bundle != null && bundle.changelogEntries != null) {
                        byte[] currentBase = new byte[0];
                        for (ChangegroupParser.ChangeGroupEntry entry : bundle.changelogEntries) {
                            byte[] clContent;
                            try {
                                clContent = DeltaEngine.applyDelta(currentBase, entry.delta);
                            } catch (Exception e) {
                                // Fallback if applyDelta fails, use delta as raw text
                                clContent = entry.delta;
                            }
                            currentBase = clContent;

                            if (changelog.findRevision(entry.node) == -1) {
                                if (clContent != null && clContent.length > 0) {
                                    String clText = new String(clContent, StandardCharsets.UTF_8);
                                    // Raw changelog entry layout (see mercurial/changelog.py):
                                    // manifest\nuser\ndate tz extra\nfiles...\n\ndescription
                                    // The header (manifest/user/date/files) is separated from the
                                    // free-form description by a blank line, and "summary" is the
                                    // *first* line of the description -- not the last raw line of
                                    // the whole blob, which for a multi-line commit message would
                                    // otherwise pick up the last line of the description instead.
                                    String[] headerAndDesc = clText.split("\n\n", 2);
                                    String[] headerLines = headerAndDesc[0].split("\n");
                                    String author = (headerLines.length > 1) ? headerLines[1].trim() : "remote_developer";
                                    String msg = "Remote changeset summary";
                                    if (headerAndDesc.length > 1) {
                                        String desc = headerAndDesc[1];
                                        int nl = desc.indexOf('\n');
                                        String firstLine = (nl == -1) ? desc : desc.substring(0, nl);
                                        firstLine = firstLine.trim();
                                        if (!firstLine.isEmpty()) {
                                            msg = firstLine;
                                        }
                                    }

                                    incomingMessages.add("changeset:   " + NodeIdUtil.toHex(entry.node).substring(0, 12));
                                    incomingMessages.add("user:        " + author);
                                    incomingMessages.add("summary:     " + msg);
                                } else {
                                    incomingMessages.add("changeset:   " + NodeIdUtil.toHex(entry.node).substring(0, 12));
                                    incomingMessages.add("user:        remote_developer");
                                    incomingMessages.add("summary:     [Binary delta metadata]");
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Fallback: listing missing head revisions with error status clearly
            for (String hexHead : missingHeads) {
                incomingMessages.add("changeset:   " + hexHead.substring(0, 12) + " (incoming head - offline)");
                incomingMessages.add("user:        remote_developer (fetch failed)");
                incomingMessages.add("summary:     Failed to fetch remote metadata: " + e.getMessage());
            }
        }

        if (incomingMessages.isEmpty()) {
            incomingMessages.add("no incoming changes found");
        }
        return incomingMessages;
    }
}

