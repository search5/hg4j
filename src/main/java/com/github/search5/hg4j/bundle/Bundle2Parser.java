package com.github.search5.hg4j.bundle;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.InflaterInputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lightweight, production-grade parser for decoding the Mercurial bundle2 (HG20) container format.
 * Dynamically resolves stream-level compression (zlib deflate) and extracts the inner CHANGEGROUP payload.
 */
public class Bundle2Parser {
    private static final Logger LOGGER = Logger.getLogger(Bundle2Parser.class.getName());

    /**
     * Structure representing the result of bundle2 extraction.
     */
    public static class ExtractedBundle2 {
        public byte[] changegroupBytes;
        public String cgVersion = "01"; // Default to cg1 if not specified
    }

    /**
     * Parses a bundle2 input stream and extracts the inner changegroup bundle bytes.
     * Supports 'HG20' magic, zlib/gzip compression decoding, and CHANGEGROUP part payload assembly.
     *
     * @param in the raw input stream of the bundle2 data
     * @return the extracted changegroup bundle raw bytes (compatible with bundle1/changegroup parser)
     * @throws IOException if parsing fails or invalid bundle2 format is encountered
     */
    public static byte[] extractChangegroup(InputStream in) throws IOException {
        return extractChangegroupDetailed(in).changegroupBytes;
    }

    /**
     * Detailed bundle2 extraction that retrieves both the changegroup bytes and its version parameter.
     */
    public static ExtractedBundle2 extractChangegroupDetailed(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        
        // 1. Read Magic "HG20"
        byte[] magic = new byte[4];
        dis.readFully(magic);
        String magicStr = new String(magic, java.nio.charset.StandardCharsets.US_ASCII);
        if (!"HG20".equals(magicStr)) {
            throw new com.github.search5.hg4j.errors.HgCorruptDataException("Unsupported bundle magic: " + magicStr + ". Expected HG20.");
        }

        // 2. Stream Level Parameters Size (unsigned short 2-byte in official spec)
        int paramsSize = dis.readUnsignedShort();
        String compression = null;
        if (paramsSize > 0) {
            byte[] paramBytes = new byte[paramsSize];
            dis.readFully(paramBytes);
            String params = new String(paramBytes, java.nio.charset.StandardCharsets.UTF_8);
            for (String param : params.split(" ")) {
                if (param.startsWith("Compression=")) {
                    compression = param.substring("Compression=".length());
                }
            }
        }

        // 3. Setup decompressor if needed
        InputStream payloadStream = dis;
        if (compression != null && !compression.isEmpty()) {
            if ("GZ".equalsIgnoreCase(compression)) {
                // HG20 GZ uses zlib deflate compression.
                payloadStream = new InflaterInputStream(dis);
            } else if ("BZ".equalsIgnoreCase(compression)) {
                // Bzip2 compression
                payloadStream = new org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(dis);
            } else if ("ZS".equalsIgnoreCase(compression)) {
                // Zstandard compression
                payloadStream = new com.github.luben.zstd.ZstdInputStream(dis);
            } else {
                throw new com.github.search5.hg4j.errors.HgCorruptDataException("Unsupported bundle2 compression: " + compression);
            }
        }

        DataInputStream pdis = new DataInputStream(payloadStream);
        ByteArrayOutputStream cgOut = new ByteArrayOutputStream();
        String extractedVersion = "01";

        // 4. Parse Payload Parts
        while (true) {
            int partHeaderSize = pdis.readInt();
            if (partHeaderSize == 0) {
                // EOF of bundle2
                break;
            }

            // Read the header block
            byte[] headerBlock = new byte[partHeaderSize];
            pdis.readFully(headerBlock);
            
            // Parse part header
            int cursor = 0;
            int nameSize = headerBlock[cursor++] & 0xFF;
            String partName = new String(headerBlock, cursor, nameSize, java.nio.charset.StandardCharsets.US_ASCII);
            LOGGER.log(Level.FINE, "[DEBUG BUNDLE2] Parsed partName: ''{0}'', partHeaderSize: {1}, nameSize: {2}", new Object[]{partName, partHeaderSize, nameSize});
            cursor += nameSize;
            
            // Part ID (4 bytes) - Skip
            cursor += 4;

            // Part parameters counts
            int mandatoryCount = headerBlock[cursor++] & 0xFF;
            int advisoryCount = headerBlock[cursor++] & 0xFF;
            
            boolean isChangegroup = "CHANGEGROUP".equalsIgnoreCase(partName);
            int paramCount = mandatoryCount + advisoryCount;
            
            // Parse part parameters to search for 'version'
            for (int i = 0; i < paramCount; i++) {
                int paramNameLen = headerBlock[cursor++] & 0xFF;
                String paramName = new String(headerBlock, cursor, paramNameLen, java.nio.charset.StandardCharsets.US_ASCII);
                cursor += paramNameLen;
                
                int paramValLen = headerBlock[cursor++] & 0xFF;
                byte[] paramValBytes = new byte[paramValLen];
                System.arraycopy(headerBlock, cursor, paramValBytes, 0, paramValLen);
                cursor += paramValLen;
                
                if (isChangegroup && "version".equalsIgnoreCase(paramName)) {
                    extractedVersion = new String(paramValBytes, java.nio.charset.StandardCharsets.US_ASCII).trim();
                }
            }

            // 5. Read Part Payload Chunks
            while (true) {
                int chunkSize = pdis.readInt();
                if (chunkSize == 0) {
                    // End of this part's payload
                    break;
                }
                if (chunkSize == -1) {
                    // Interrupt (nested part starts)
                    throw new com.github.search5.hg4j.errors.HgCorruptDataException("Nested stream interrupts are not supported in this lightweight parser.");
                }
                
                byte[] chunkData = new byte[chunkSize];
                pdis.readFully(chunkData);
                
                if (isChangegroup) {
                    cgOut.write(chunkData);
                }
            }
        }

        if (cgOut.size() == 0) {
            throw new com.github.search5.hg4j.errors.HgCorruptDataException("No CHANGEGROUP part found in the bundle2 stream.");
        }

        ExtractedBundle2 result = new ExtractedBundle2();
        result.changegroupBytes = cgOut.toByteArray();
        result.cgVersion = extractedVersion;
        return result;
    }
}
