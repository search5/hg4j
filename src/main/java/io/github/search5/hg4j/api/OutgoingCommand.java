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
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

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
                int rHeadRev = changelog.findRevision(rHead);
                if (rHeadRev != -1) {
                    // If remote head is descendant of local revision i, remote already knows i
                    if (isAncestor(changelog, i, rHeadRev)) {
                        isKnownByRemote = true;
                        break;
                    }
                } else {
                    if (Arrays.equals(node, rHead)) {
                        isKnownByRemote = true;
                        break;
                    }
                }
            }

            if (!isKnownByRemote) {
                byte[] clContent = changelog.getRevisionContent(i);
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
                String author = (headerLines.length > 1) ? headerLines[1].trim() : "unknown";
                String msg = "commit msg";
                if (headerAndDesc.length > 1) {
                    String desc = headerAndDesc[1];
                    int nl = desc.indexOf('\n');
                    String firstLine = (nl == -1) ? desc : desc.substring(0, nl);
                    firstLine = firstLine.trim();
                    if (!firstLine.isEmpty()) {
                        msg = firstLine;
                    }
                }

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

    private boolean isAncestor(Revlog changelog, int ancestorRev, int descendantRev) {
        if (ancestorRev < 0 || descendantRev < 0) return false;
        if (ancestorRev == descendantRev) return true;
        if (ancestorRev > descendantRev) return false; // Topological ordering optimization
        
        Queue<Integer> queue = new LinkedList<>();
        Set<Integer> visited = new HashSet<>();
        queue.add(descendantRev);
        visited.add(descendantRev);
        
        while (!queue.isEmpty()) {
            int curr = queue.poll();
            if (curr == ancestorRev) {
                return true;
            }
            if (curr < ancestorRev) {
                continue;
            }
            
            Revlog.IndexRecord rec = changelog.getIndexRecord(curr);
            int p1 = rec.getParent1();
            int p2 = rec.getParent2();
            if (p1 != -1 && visited.add(p1)) {
                queue.add(p1);
            }
            if (p2 != -1 && visited.add(p2)) {
                queue.add(p2);
            }
        }
        return false;
    }
}

