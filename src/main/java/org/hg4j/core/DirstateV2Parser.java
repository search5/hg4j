package org.hg4j.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * High-performance native parser for Mercurial dirstate-v2 binary format.
 */
public class DirstateV2Parser {

    /**
     * Parses dirstate-v2 binary content into a standard Dirstate instance.
     *
     * @param bytes raw binary content
     * @return decoded Dirstate object
     * @throws IOException if format is invalid
     */
    public Dirstate parse(byte[] bytes) throws IOException {
        if (bytes == null) {
            throw new IOException("Invalid dirstate-v2 data: content is null");
        }

        if (bytes.length == 0) {
            Dirstate decoded = new Dirstate();
            decoded.setV2(true);
            return decoded;
        }

        // 100% native Mercurial format - no 12-byte header.
        // Find nodeCount (N) using the invariant: N * 44 + pathOffset[N-1] + pathLen[N-1] == bytes.length
        int nodeCount = 0;
        int nodeSize = DirstateV2Node.NODE_SIZE;
        ByteBuffer tempBuf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        
        for (int n = 1; n * nodeSize <= bytes.length; n++) {
            int lastNodeOffset = (n - 1) * nodeSize;
            int pathOffset = tempBuf.getInt(lastNodeOffset + 0);
            int pathLen = tempBuf.getShort(lastNodeOffset + 4) & 0xFFFF;
            
            if (n * nodeSize + pathOffset + pathLen == bytes.length) {
                nodeCount = n;
                break;
            }
        }

        if (nodeCount == 0) {
            throw new IOException("Malformed dirstate-v2 data: cannot resolve node count and layout");
        }

        int nodesOffset = 0;
        int dataOffset = nodeCount * nodeSize;

        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);

        Dirstate decoded = new Dirstate();
        decoded.setV2(true);

        // 2. Loop through all nodes sequentially to extract path and entry status (100% compliant with native hg layout)
        for (int i = 0; i < nodeCount; i++) {
            int nodeStart = nodesOffset + i * nodeSize;
            DirstateV2Node node = new DirstateV2Node(buffer, nodeStart);

            int pathOffset = node.getPathOffset();
            int pathLen = node.getPathLen() & 0xFFFF; // unsigned short

            if (dataOffset + pathOffset + pathLen > buffer.capacity()) {
                throw new IOException("Data block overflow for node segment: " + i);
            }

            // Extract the path string
            byte[] pathBytes = new byte[pathLen];
            int originalPos = buffer.position();
            buffer.position(dataOffset + pathOffset);
            buffer.get(pathBytes);
            buffer.position(originalPos);

            String currentPath = new String(pathBytes, StandardCharsets.UTF_8);

            char state = node.getState();
            if (state != '\0' && state != 'd') { // 'd' represents intermediate directories
                decoded.addEntry(currentPath, new Dirstate.Entry(state, node.getMode(), node.getSize(), node.getMtime()));
            }
        }

        return decoded;
    }
}
