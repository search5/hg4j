package com.github.search5.hg4j.core;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.util.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

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
     * Resolves the expected local path for a given OID.
     * E.g., OID "7b1a2c3d..." resolves to:
     * {lfsObjectsDir}/7b/1a/2c3d...
     *
     * @param oid 64-char hex OID
     * @return cache file reference
     */
    public File getLocalPath(String oid) {
        if (oid == null || oid.length() != 64) {
            throw new IllegalArgumentException("Invalid OID: must be a 64-character hex string");
        }
        String first2 = oid.substring(0, 2);
        String next2 = oid.substring(2, 4);
        String remaining = oid.substring(4);

        File sub1 = new File(lfsObjectsDir, first2);
        File sub2 = new File(sub1, next2);
        return new File(sub2, remaining);
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
            throw new com.github.search5.hg4j.errors.HgCorruptDataException("LFS payload size mismatch: expected " 
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
            throw new com.github.search5.hg4j.errors.HgCorruptDataException("Requested LFS object not cached or size mismatch: " + pointer.getOid());
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

        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest batchRequest = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(lfsServerUrl + "/objects/batch"))
                .header("Accept", "application/vnd.git-lfs+json")
                .header("Content-Type", "application/vnd.git-lfs+json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(batchJson, java.nio.charset.StandardCharsets.UTF_8))
                .build();

        java.net.http.HttpResponse<String> batchResponse = client.send(batchRequest, java.net.http.HttpResponse.BodyHandlers.ofString());
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
            java.util.Map<String, Object> resMap = (java.util.Map<String, Object>) parser.parse();
            @SuppressWarnings("unchecked")
            java.util.List<Object> objects = (java.util.List<Object>) resMap.get("objects");
            if (objects == null || objects.isEmpty()) {
                throw new IOException("No objects in LFS batch response: " + responseBody);
            }
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> objMap = (java.util.Map<String, Object>) objects.get(0);
            if (objMap.containsKey("error")) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> errMap = (java.util.Map<String, Object>) objMap.get("error");
                throw new IOException("LFS object download failed from batch API: " 
                    + errMap.get("code") + " - " + errMap.get("message"));
            }
            
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> actions = (java.util.Map<String, Object>) objMap.get("actions");
            if (actions == null) {
                throw new IOException("No actions for LFS object in batch response: " + responseBody);
            }
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> download = (java.util.Map<String, Object>) actions.get("download");
            if (download == null) {
                throw new IOException("No download action for LFS object: " + responseBody);
            }
            downloadUrl = (String) download.get("href");
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> headers = (java.util.Map<String, Object>) download.get("header");
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
        java.net.http.HttpRequest.Builder getReqBuilder = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(downloadUrl))
                .GET();
        if (authHeaderVal != null) {
            getReqBuilder.header("Authorization", authHeaderVal);
        }

        java.net.http.HttpResponse<byte[]> getResponse = client.send(getReqBuilder.build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
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
        
        private java.util.Map<String, Object> parseObject() throws IOException {
            ptr++; // skip '{'
            java.util.Map<String, Object> map = new java.util.HashMap<>();
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
        
        private java.util.List<Object> parseArray() throws IOException {
            ptr++; // skip '['
            java.util.List<Object> list = new java.util.ArrayList<>();
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
