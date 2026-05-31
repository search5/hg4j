package com.github.search5.hg4j.api;

import com.github.search5.hg4j.core.HgLock;
import com.github.search5.hg4j.core.HgObsMarker;
import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.core.Revlog;
import com.github.search5.hg4j.core.SafeFileIO;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Porcelain command to amend the tip commit (replace with modified changes and message).
 * Seamlessly registers the obsolescence marker in .hg/store/obsstore for evolve compatibility.
 */
public final class AmendCommand {
    private final HgRepository repository;
    private String author;
    private String message;

    public AmendCommand(HgRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("Repository cannot be null");
        }
        this.repository = repository;
    }

    public AmendCommand setAuthor(String author) {
        this.author = author;
        return this;
    }

    public AmendCommand setMessage(String message) {
        this.message = message;
        return this;
    }

    /**
     * Executes the commit amend and obsstore marker serializing.
     *
     * @return NodeId byte array of the new amended commit
     * @throws IOException if commit or obsstore serialization fails
     */
    public byte[] call() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        
        Revlog changelog = repository.getRevlog(clIdx, clDat);
        int count = changelog.getRevisionCount();
        if (count == 0) {
            throw new IllegalStateException("No commits exist to amend (empty repository)");
        }

        int obsoleteRev = count - 1;
        Revlog.IndexRecord obsoleteRec = changelog.getIndexRecord(obsoleteRev);
        byte[] obsoleteNode = obsoleteRec.getNodeId();

        // 1. Create a new amended commit on the same parents
        CommitCommand commitCmd = new CommitCommand(repository);
        if (author != null) commitCmd.setAuthor(author);
        if (message != null) commitCmd.setMessage(message);
        
        byte[] newCommitNode = commitCmd.call();

            // 2. Generate and append obsolescence marker in obsstore
            File obsstoreFile = new File(repository.getStoreDir(), "obsstore");
            
            // Format Evolve V1 obsolescence marker byte array
            byte[] flags = new byte[1];
            flags[0] = 0x00; // default flags
            
            byte[] metadataBlock = "user\0amend\0".getBytes(java.nio.charset.StandardCharsets.UTF_8);

            ByteBuffer markerBuf = ByteBuffer.allocate(20 + 1 + 20 + 1 + 2 + metadataBlock.length).order(ByteOrder.BIG_ENDIAN);
            markerBuf.put(obsoleteNode); // predecessor (20B)
            markerBuf.put((byte) 1);      // successors count (1B)
            markerBuf.put(newCommitNode); // successor (20B)
            markerBuf.put((byte) 0x00);   // flags (1B)
            markerBuf.putShort((short) metadataBlock.length); // metaLen
            markerBuf.put(metadataBlock);

            try (FileOutputStream out = new FileOutputStream(obsstoreFile, true)) {
                out.write(markerBuf.array());
                out.getFD().sync();
            }

            return newCommitNode;
    }
}
