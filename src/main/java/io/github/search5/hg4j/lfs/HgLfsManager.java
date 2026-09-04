package io.github.search5.hg4j.lfs;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import io.github.search5.hg4j.errors.HgCorruptDataException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the local caching and resolution of LFS (Large File Storage) objects.
 * Typically resolved inside '.hg/store/lfs/objects/' in Git/Mercurial style.
 */
public final class HgLfsManager {
    private final File lfsObjectsDir;

    public HgLfsManager(File hgDir) {
        if (hgDir == null) {
            throw new IllegalArgumentException("HG directory cannot be null");
        }
        File storeDir = new File(hgDir, "store");
        File lfsDir = new File(storeDir, "lfs");
        this.lfsObjectsDir = new File(lfsDir, "objects");
    }

    public File getLfsObjectsDir() {
        return lfsObjectsDir;
    }

    /**
     * Parses a {@code [lfs] threshold} value the same way real hg's {@code ui.configbytes()}/
     * {@code util.sizetoint()} does (confirmed 2026-09-04 against {@code mercurial/util.py}):
     * a plain number is bytes, otherwise a case-insensitive suffix of {@code b}/{@code k}/
     * {@code kb}/{@code m}/{@code mb}/{@code g}/{@code gb} (checked by string-endswith, which is
     * order-independent since single-letter suffixes never match a two-letter one) multiplies a
     * leading (possibly fractional) number by 1/2^10/2^20/2^30 as appropriate.
     *
     * @return the parsed byte count, or -1 if {@code value} is {@code null}/blank (no threshold
     *     configured -- caller should treat every file as non-LFS in that case, matching real hg's
     *     own "lfs.threshold unset" default of never triggering).
     */
    public static long parseThresholdBytes(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        String t = value.trim().toLowerCase(java.util.Locale.ROOT);
        String[] suffixes = {"kb", "mb", "gb", "k", "m", "g", "b"};
        long[] units = {1L << 10, 1L << 20, 1L << 30, 1L << 10, 1L << 20, 1L << 30, 1L};
        for (int i = 0; i < suffixes.length; i++) {
            if (t.endsWith(suffixes[i])) {
                String numPart = t.substring(0, t.length() - suffixes[i].length()).trim();
                return (long) (Double.parseDouble(numPart) * units[i]);
            }
        }
        return Long.parseLong(t);
    }

    /**
     * Resolves the expected local path for a given OID.
     * E.g., OID "7b1a2c3d..." resolves to: {lfsObjectsDir}/7b/1a2c3d...
     *
     * <p>Real hg's {@code lfs} extension (verified against hg 7.2, {@code hgext/lfs/blobstore.py}'s
     * {@code lfsvfs.join()}: {@code "split the path at first two characters, like: XX/XXXXX..."})
     * shards local LFS objects with a single two-character directory level, NOT the two-level
     * Git-style {@code XX/XX/XXXX...} sharding this used to implement -- which meant an hg4j
     * repo and a real-hg repo sharing the same {@code .hg/store/lfs/objects/} directory could
     * never find each other's blobs. Fixed as part of backlog 28's real-hg verification pass.
     *
     * @param oid 64-char hex OID
     * @return cache file reference
     */
    public File getLocalPath(String oid) {
        if (oid == null || oid.length() != 64) {
            throw new IllegalArgumentException("Invalid OID: must be a 64-character hex string");
        }
        String first2 = oid.substring(0, 2);
        String remaining = oid.substring(2);

        File sub1 = new File(lfsObjectsDir, first2);
        return new File(sub1, remaining);
    }

    /**
     * Checks if the LFS object exists locally in cache.
     *
     * @param pointer LFS pointer metadata
     * @return true if cached and size matches
     */
    public boolean isCached(HgLfsPointer pointer) {
        if (pointer == null) {
            return false;
        }
        File localFile = getLocalPath(pointer.getOid());
        return localFile.exists() && localFile.isFile() && localFile.length() == pointer.getSize();
    }

    /**
     * Writes binary payload into the local LFS objects directory.
     *
     * @param pointer LFS pointer metadata
     * @param data raw file payload bytes
     * @throws IOException if directory generation or write fails, or size mismatch
     */
    public void cacheObject(HgLfsPointer pointer, byte[] data) throws IOException {
        if (pointer == null || data == null) {
            throw new IllegalArgumentException("Pointer and data cannot be null");
        }
        if (data.length != pointer.getSize()) {
            throw new HgCorruptDataException("LFS payload size mismatch: expected " 
                    + pointer.getSize() + ", got " + data.length);
        }

        File cacheFile = getLocalPath(pointer.getOid());
        File parent = cacheFile.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create local LFS cache directories: " + parent.getAbsolutePath());
        }

        SafeFileIO.writeAtomic(cacheFile, data);
    }

    /**
     * Reads the cached LFS object payload from disk.
     *
     * @param pointer LFS pointer metadata
     * @return cached binary payload bytes
     * @throws IOException if file does not exist, or size mismatch
     */
    public byte[] getCachedObject(HgLfsPointer pointer) throws IOException {
        if (!isCached(pointer)) {
            throw new HgCorruptDataException("Requested LFS object not cached or size mismatch: " + pointer.getOid());
        }
        File cacheFile = getLocalPath(pointer.getOid());
        return Files.readAllBytes(cacheFile.toPath());
    }

    /**
     * Fetches the missing LFS object from the remote LFS server and caches it.
     * Built with zero-dependency native HttpClient for specifications fidelity.
     *
     * @param pointer LFS pointer metadata
     * @param lfsServerUrl remote LFS server base url (e.g. "https://lfs.example.com/repo/info/lfs")
     * @throws IOException if network or caching fails
     * @throws InterruptedException if thread is interrupted
     */
    public void fetchObject(HgLfsPointer pointer, String lfsServerUrl) throws IOException, InterruptedException {
        if (pointer == null || lfsServerUrl == null) {
            throw new IllegalArgumentException("Pointer and server URL cannot be null");
        }
        if (isCached(pointer)) {
            return; // Already cached
        }

        // 1. Build Batch Request JSON according to Git LFS API v1 download spec
        String batchJson = "{"
                + "\"operation\":\"download\","
                + "\"transfers\":[\"basic\"],"
                + "\"objects\":[{"
                + "\"oid\":\"" + pointer.getOid() + "\","
                + "\"size\":" + pointer.getSize()
                + "}]"
                + "}";

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest batchRequest = HttpRequest.newBuilder()
                .uri(URI.create(lfsServerUrl + "/objects/batch"))
                .header("Accept", "application/vnd.git-lfs+json")
                .header("Content-Type", "application/vnd.git-lfs+json")
                .POST(HttpRequest.BodyPublishers.ofString(batchJson, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> batchResponse = client.send(batchRequest, HttpResponse.BodyHandlers.ofString());
        if (batchResponse.statusCode() != 200) {
            throw new IOException("LFS batch API request failed with status: " + batchResponse.statusCode());
        }

        String responseBody = batchResponse.body();
        String downloadUrl = null;
        String authHeaderVal = null;

        // Robust LFS JSON Parsing
        try {
            MapJsonParser parser = new MapJsonParser(responseBody);
            @SuppressWarnings("unchecked")
            Map<String, Object> resMap = (Map<String, Object>) parser.parse();
            @SuppressWarnings("unchecked")
            List<Object> objects = (List<Object>) resMap.get("objects");
            if (objects == null || objects.isEmpty()) {
                throw new IOException("No objects in LFS batch response: " + responseBody);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> objMap = (Map<String, Object>) objects.get(0);
            if (objMap.containsKey("error")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> errMap = (Map<String, Object>) objMap.get("error");
                throw new IOException("LFS object download failed from batch API: " 
                    + errMap.get("code") + " - " + errMap.get("message"));
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> actions = (Map<String, Object>) objMap.get("actions");
            if (actions == null) {
                throw new IOException("No actions for LFS object in batch response: " + responseBody);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> download = (Map<String, Object>) actions.get("download");
            if (download == null) {
                throw new IOException("No download action for LFS object: " + responseBody);
            }
            downloadUrl = (String) download.get("href");
            @SuppressWarnings("unchecked")
            Map<String, Object> headers = (Map<String, Object>) download.get("header");
            if (headers != null) {
                authHeaderVal = (String) headers.get("Authorization");
            }
        } catch (Exception e) {
            throw new IOException("Failed to parse LFS batch JSON response. Body: " + responseBody, e);
        }

        if (downloadUrl == null) {
            throw new IOException("Failed to extract download URL from LFS batch response: " + responseBody);
        }

        // 2. Download LFS object payload via HTTP GET
        HttpRequest.Builder getReqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .GET();
        if (authHeaderVal != null) {
            getReqBuilder.header("Authorization", authHeaderVal);
        }

        HttpResponse<byte[]> getResponse = client.send(getReqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (getResponse.statusCode() != 200) {
            throw new IOException("LFS object download failed with status: " + getResponse.statusCode() + " from: " + downloadUrl);
        }

        // 3. Cache downloaded object into local store/lfs/objects/
        cacheObject(pointer, getResponse.body());
    }

    public static class MapJsonParser {
        private final String src;
        private int ptr = 0;
        
        public MapJsonParser(String src) {
            this.src = src;
        }
        
        public Object parse() throws IOException {
            skipWhitespace();
            if (ptr >= src.length()) throw new IOException("Unexpected end of JSON");
            char c = src.charAt(ptr);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (Character.isDigit(c) || c == '-') return parseNumber();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') return parseNull();
            throw new IOException("Unexpected character: " + c + " at " + ptr);
        }
        
        private void skipWhitespace() {
            while (ptr < src.length() && Character.isWhitespace(src.charAt(ptr))) {
                ptr++;
            }
        }
        
        private Map<String, Object> parseObject() throws IOException {
            ptr++; // skip '{'
            Map<String, Object> map = new HashMap<>();
            skipWhitespace();
            if (ptr < src.length() && src.charAt(ptr) == '}') {
                ptr++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (ptr >= src.length() || src.charAt(ptr) != '"') throw new IOException("Expected string key at " + ptr);
                String key = parseString();
                skipWhitespace();
                if (ptr >= src.length() || src.charAt(ptr) != ':') throw new IOException("Expected ':' at " + ptr);
                ptr++; // skip ':'
                Object val = parse();
                map.put(key, val);
                skipWhitespace();
                if (ptr < src.length() && src.charAt(ptr) == '}') {
                    ptr++;
                    break;
                }
                if (ptr >= src.length() || src.charAt(ptr) != ',') throw new IOException("Expected ',' or '}' at " + ptr);
                ptr++; // skip ','
            }
            return map;
        }
        
        private List<Object> parseArray() throws IOException {
            ptr++; // skip '['
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (ptr < src.length() && src.charAt(ptr) == ']') {
                ptr++;
                return list;
            }
            while (true) {
                list.add(parse());
                skipWhitespace();
                if (ptr < src.length() && src.charAt(ptr) == ']') {
                    ptr++;
                    break;
                }
                if (ptr >= src.length() || src.charAt(ptr) != ',') throw new IOException("Expected ',' or ']' at " + ptr);
                ptr++; // skip ','
            }
            return list;
        }
        
        private String parseString() throws IOException {
            ptr++; // skip opening quote
            StringBuilder sb = new StringBuilder();
            while (ptr < src.length()) {
                char c = src.charAt(ptr);
                if (c == '"') {
                    ptr++;
                    return sb.toString();
                }
                if (c == '\\') {
                    ptr++;
                    if (ptr >= src.length()) throw new IOException("Unterminated string escape");
                    char esc = src.charAt(ptr);
                    if (esc == '"') sb.append('"');
                    else if (esc == '\\') sb.append('\\');
                    else if (esc == '/') sb.append('/');
                    else if (esc == 'b') sb.append('\b');
                    else if (esc == 'f') sb.append('\f');
                    else if (esc == 'n') sb.append('\n');
                    else if (esc == 'r') sb.append('\r');
                    else if (esc == 't') sb.append('\t');
                    else if (esc == 'u') {
                        if (ptr + 4 >= src.length()) throw new IOException("Unterminated unicode escape");
                        String hex = src.substring(ptr + 1, ptr + 5);
                        sb.append((char) Integer.parseInt(hex, 16));
                        ptr += 4;
                    } else sb.append(esc);
                } else {
                    sb.append(c);
                }
                ptr++;
            }
            throw new IOException("Unterminated string");
        }
        
        private Object parseNumber() {
            int start = ptr;
            if (src.charAt(ptr) == '-') ptr++;
            while (ptr < src.length() && (Character.isDigit(src.charAt(ptr)) || src.charAt(ptr) == '.' || src.charAt(ptr) == 'e' || src.charAt(ptr) == 'E' || src.charAt(ptr) == '+' || src.charAt(ptr) == '-')) {
                ptr++;
            }
            String numStr = src.substring(start, ptr);
            if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
                return Double.parseDouble(numStr);
            }
            return Long.parseLong(numStr);
        }
        
        private Boolean parseBoolean() throws IOException {
            if (src.startsWith("true", ptr)) {
                ptr += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", ptr)) {
                ptr += 5;
                return Boolean.FALSE;
            }
            throw new IOException("Expected boolean");
        }
        
        private Object parseNull() throws IOException {
            if (src.startsWith("null", ptr)) {
                ptr += 4;
                return null;
            }
            throw new IOException("Expected null");
        }
    }
}
