package org.hg4j.core;

import java.io.IOException;


/**
 * Common utility methods for handling Mercurial NodeIDs and Hexadecimal representations.
 */
public final class NodeIdUtil {

    private NodeIdUtil() {
        // Prevent instantiation of utility class
    }

    /**
     * Converts a byte array to its hexadecimal String representation.
     */
    public static String toHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * Converts a hexadecimal String to its byte array representation.
     */
    public static byte[] fromHex(String hex) {
        if (hex == null || hex.isEmpty()) {
            return new byte[0];
        }
        int len = hex.length();
        if (len % 2 != 0) {
            throw new IllegalArgumentException("Hex string must have an even length: " + hex);
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int high = Character.digit(hex.charAt(i), 16);
            int low = Character.digit(hex.charAt(i + 1), 16);
            if (high == -1 || low == -1) {
                throw new IllegalArgumentException("Invalid hex character in string: " + hex);
            }
            data[i / 2] = (byte) ((high << 4) + low);
        }
        return data;
    }

    /**
     * Checks if a 20-byte array represents a null (all-zero) node ID.
     */
    public static boolean isAllZero(byte[] bytes) {
        if (bytes == null) return true;
        for (byte b : bytes) {
            if (b != 0) return false;
        }
        return true;
    }

    /**
     * Searches for a revision index in a Revlog using its 20-byte Node ID.
     */
    public static int findRevisionByNodeId(Revlog revlog, byte[] nodeId) {
        if (revlog == null || nodeId == null) {
            return -1;
        }
        return revlog.findRevision(nodeId);
    }

    /**
     * Encodes a file path to its Mercurial store path format using basic encoding (equivalent to store.py:_encodefname).
     * Used specifically for registrations inside the fncache file.
     */
    public static String encodeFnameBasic(String relPath) {
        if (relPath == null || relPath.isEmpty()) {
            return relPath;
        }
        String[] parts = relPath.split("/");
        StringBuilder encodedPath = new StringBuilder();
        encodedPath.append("data/");
        for (int p = 0; p < parts.length; p++) {
            if (p > 0) {
                encodedPath.append("/");
            }
            String part = parts[p];
            if (part.isEmpty()) continue;

            // Check Windows reserved names
            String baseName = part;
            int dotIdx = part.indexOf('.');
            if (dotIdx != -1) {
                baseName = part.substring(0, dotIdx);
            }
            boolean isReserved = baseName.equals("con") || baseName.equals("prn") || 
                                 baseName.equals("aux") || baseName.equals("nul") ||
                                 baseName.matches("com[1-9]") || baseName.matches("lpt[1-9]");
            int reservedCharIdx = isReserved ? baseName.length() - 1 : -1;

            StringBuilder partSb = new StringBuilder();
            byte[] bytes = part.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (int i = 0; i < bytes.length; i++) {
                int b = bytes[i] & 0xff;
                char c = (char) b;
                
                // Windows 예약어 마지막 문자 처리: 예약어의 마지막 문자는 ~hex로 치환 (예: aux -> au~78)
                if (i == reservedCharIdx) {
                    partSb.append(String.format("~%02x", b));
                    continue;
                }

                if (c == '_') {
                    partSb.append("__");
                } else if (c == '~') {
                    partSb.append("~7e");
                } else if (c >= 'A' && c <= 'Z') {
                    partSb.append('_').append(Character.toLowerCase(c));
                } else if (b < 32 || b >= 127 || c == '"' || c == '*' || c == ':' || 
                           c == '<' || c == '>' || c == '?' || c == '\\' || c == '|') {
                    partSb.append(String.format("~%02x", b));
                } else {
                    partSb.append(c);
                }
            }
            encodedPath.append(partSb);
        }
        return encodedPath.toString();
    }

    /**
     * Encodes a file path to its final on-disk Mercurial store path format (incorporating dotencode and long path dh/ encoding rules).
     */
    public static String encodeFname(String relPath) {
        String basic = encodeFnameBasic(relPath);
        String[] parts = basic.split("/");
        StringBuilder onDiskPath = new StringBuilder();
        for (int p = 0; p < parts.length; p++) {
            if (p > 0) {
                onDiskPath.append("/");
            }
            String part = parts[p];
            if (part.isEmpty()) continue;

            if (p == 0 && part.equals("data")) {
                onDiskPath.append(part);
                continue;
            }

            // Dotencode: if a folder/file component starts with '.' or ' ', escape it as ~2e or ~20 (B3 Fixed)
            if (part.startsWith(".")) {
                part = "~2e" + part.substring(1);
            } else if (part.startsWith(" ")) {
                part = "~20" + part.substring(1);
            }
            onDiskPath.append(part);
        }

        String path = onDiskPath.toString();
        byte[] pathBytes = path.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // Long path optimization: Mercurial hybrid/dh encoding for store paths exceeding 120 bytes (including 'store/' prefix, so pathBytes.length + 6 > 120)
        if (pathBytes.length + 6 > 120) {
            String subPath = path.startsWith("data/") ? path.substring(5) : path;
            int lastSlash = subPath.lastIndexOf('/');
            
            String dirPath = lastSlash != -1 ? subPath.substring(0, lastSlash) : "";
            String fileName = lastSlash != -1 ? subPath.substring(lastSlash + 1) : subPath;

            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
                
                // If the overall path exceeds 255 bytes, the filename is extremely long, or there is no directory component (dirPath is empty)
                if (pathBytes.length + 6 > 255 || fileName.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 100 || dirPath.isEmpty()) {
                    byte[] fullHashBytes = md.digest(subPath.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    String fullHash = toHex(fullHashBytes);
                    
                    String suffix = fileName;
                    if (suffix.length() > 30) {
                        suffix = suffix.substring(suffix.length() - 30);
                    }
                    return "dh/" + fullHash + "_" + suffix;
                } else {
                    // Hybrid encoding: shorten only the directory part
                    byte[] dirHashBytes = md.digest(dirPath.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    String dirHash = toHex(dirHashBytes);
                    return "dh/" + dirHash + "/" + fileName;
                }
            } catch (java.security.NoSuchAlgorithmException e) {
                // Fallback to basic on-disk path if SHA-1 is not available
            }
        }
        return path;
    }

    public static final java.util.Comparator<String> UTF8_STRING_COMPARATOR = (s1, s2) -> {
        byte[] b1 = s1.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b2 = s2.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int len = Math.min(b1.length, b2.length);
        for (int i = 0; i < len; i++) {
            int v1 = b1[i] & 0xFF;
            int v2 = b2[i] & 0xFF;
            if (v1 != v2) {
                return v1 - v2;
            }
        }
        return b1.length - b2.length;
    };

    public static byte[] resolveRevision(Revlog changelog, String revStr) throws IOException {
        if (revStr == null || revStr.isEmpty() || "tip".equalsIgnoreCase(revStr)) {
            int count = changelog.getRevisionCount();
            if (count == 0) return null;
            return changelog.getIndexRecord(count - 1).getNodeId();
        }
        try {
            int rev = Integer.parseInt(revStr);
            if (rev >= 0 && rev < changelog.getRevisionCount()) {
                return changelog.getIndexRecord(rev).getNodeId();
            }
        } catch (NumberFormatException ignored) {}

        byte[] matchNode = null;
        for (int i = 0; i < changelog.getRevisionCount(); i++) {
            byte[] node = changelog.getIndexRecord(i).getNodeId();
            String hex = toHex(node);
            if (hex.startsWith(revStr.toLowerCase())) {
                if (matchNode != null) {
                    throw new IOException("Ambiguous revision identifier: " + revStr);
                }
                matchNode = node;
            }
        }
        return matchNode;
    }
}
