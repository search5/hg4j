package org.hg4j.api;

import org.hg4j.core.HgRepository;
import org.hg4j.core.Revlog;
import org.hg4j.core.NodeIdUtil;
import org.hg4j.transport.HgRemoteConnection;
import org.hg4j.transport.HgRemoteConnectionFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Outgoing command for identifying changesets present in the local repository
 * but not yet pushed to the remote repository.
 */
public class OutgoingCommand {
    private final HgRepository repository;
    private String destinationUrl;

    public OutgoingCommand(HgRepository repository) {
        this.repository = repository;
    }

    public OutgoingCommand setDestination(String destinationUrl) {
        this.destinationUrl = destinationUrl;
        return this;
    }

    /**
     * Executes outgoing changeset detection.
     * Contacts remote server and finds local commits that are not ancestors of remote heads.
     *
     * @return List of outgoing commit metadata headers
     * @throws IOException if network or handshake protocol fails
     */
    public List<String> call() throws IOException {
        if (destinationUrl == null || destinationUrl.isEmpty()) {
            throw new IllegalArgumentException("Destination URL must be specified for outgoing changesets query");
        }

        List<byte[]> remoteHeads = new ArrayList<>();
        try (HgRemoteConnection client = HgRemoteConnectionFactory.createConnection(destinationUrl)) {
            List<String> remoteHeadsStr = client.getHeads();
            if (remoteHeadsStr != null) {
                for (String headStr : remoteHeadsStr) {
                    remoteHeads.add(NodeIdUtil.fromHex(headStr));
                }
            }
        } catch (Exception e) {
            throw new IOException("Remote connection failed for " + destinationUrl, e);
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        List<String> outgoingMessages = new ArrayList<>();
        int count = changelog.getRevisionCount();

        for (int i = 0; i < count; i++) {
            byte[] node = changelog.getIndexRecord(i).getNodeId();
            boolean isKnownByRemote = false;
            
            for (byte[] rHead : remoteHeads) {
                if (java.util.Arrays.equals(node, rHead)) {
                    isKnownByRemote = true;
                    break;
                }
            }

            if (!isKnownByRemote) {
                byte[] clContent = changelog.getRevisionContent(i);
                String clText = new String(clContent, StandardCharsets.UTF_8);
                String[] clLines = clText.split("\n");
                
                String author = (clLines.length > 1) ? clLines[1].trim() : "unknown";
                String msg = (clLines.length > 4) ? clLines[clLines.length - 1].trim() : "commit msg";

                outgoingMessages.add("changeset:   " + i + ":" + NodeIdUtil.toHex(node).substring(0, 12));
                outgoingMessages.add("user:        " + author);
                outgoingMessages.add("summary:     " + msg);
            }
        }

        if (outgoingMessages.isEmpty()) {
            outgoingMessages.add("no outgoing changes found");
        }
        return outgoingMessages;
    }
}

