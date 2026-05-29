package org.hg4j.core;

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
            throw new org.hg4j.errors.HgCorruptDataException("LFS payload size mismatch: expected " 
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
            throw new org.hg4j.errors.HgCorruptDataException("Requested LFS object not cached or size mismatch: " + pointer.getOid());
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

        int hrefKeyIdx = responseBody.indexOf("\"href\"");
        if (hrefKeyIdx != -1) {
            int colonIdx = responseBody.indexOf(":", hrefKeyIdx);
            if (colonIdx != -1) {
                int quoteStart = responseBody.indexOf("\"", colonIdx);
                if (quoteStart != -1) {
                    int quoteEnd = responseBody.indexOf("\"", quoteStart + 1);
                    if (quoteEnd != -1) {
                        downloadUrl = responseBody.substring(quoteStart + 1, quoteEnd);
                    }
                }
            }
        }

        int authKeyIdx = responseBody.indexOf("\"Authorization\"");
        if (authKeyIdx != -1) {
            int colonIdx = responseBody.indexOf(":", authKeyIdx);
            if (colonIdx != -1) {
                int quoteStart = responseBody.indexOf("\"", colonIdx);
                if (quoteStart != -1) {
                    int quoteEnd = responseBody.indexOf("\"", quoteStart + 1);
                    if (quoteEnd != -1) {
                        authHeaderVal = responseBody.substring(quoteStart + 1, quoteEnd);
                    }
                }
            }
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
}
