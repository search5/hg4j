package io.github.search5.hg4j.lfs;
import io.github.search5.hg4j.lib.HgRcConfig;
import io.github.search5.hg4j.lib.HgRepository;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Manages the local caching and resolution of LFS (Large File Storage) objects.
 *
 * <p>Two storage layers, mirroring real hg's {@code hgext/lfs/blobstore.py} {@code local} class
 * (confirmed 2026-09-06 by reproducing a real hg 7.2 LFS commit with {@code strace}/{@code -v}
 * and inspecting both resulting directories):
 * <ul>
 *   <li>The per-repository local store, {@code .hg/store/lfs/objects/}, always populated on a
 *       commit or a successful remote fetch, regardless of any cache config.</li>
 *   <li>The optional user-level cache (real hg's {@code lfs.usercache}/{@code
 *       experimental.lfs.disableusercache}), shared across every repository on the machine so a
 *       blob already downloaded once for repo A doesn't need re-downloading for repo B. Real hg
 *       defaults this to {@code $XDG_CACHE_HOME/lfs} (or {@code ~/.cache/lfs} if unset) on POSIX,
 *       {@code ~/Library/Caches/lfs} on macOS, and {@code %LOCALAPPDATA%\lfs} (falling back to
 *       {@code %APPDATA%}) on Windows -- verified live: a real hg 7.2 clone/commit under a
 *       controlled {@code XDG_CACHE_HOME} populated exactly {@code $XDG_CACHE_HOME/lfs/<oid[0:2]>/
 *       <oid[2:]>}, the SAME two-character shard scheme {@link #getLocalPath} already used for the
 *       per-repo store.</li>
 * </ul>
 */
public final class HgLfsManager {
    /** Real hg's {@code stringutil._booleans} (confirmed 2026-09-06 against {@code
     * mercurial/utils/stringutil.py}) -- the exact token set {@code ui.configbool} accepts. */
    private static final Set<String> TRUE_TOKENS = Set.of("1", "yes", "true", "on", "always");
    private static final Set<String> FALSE_TOKENS = Set.of("0", "no", "false", "off", "never");

    private final File lfsObjectsDir;
    /** The resolved user-level cache directory (already including the trailing {@code "lfs"}
     * path segment, so an oid shards directly under it as {@code <dir>/<oid[0:2]>/<oid[2:]>}),
     * or {@code null} when the user cache is disabled/unresolvable. */
    private final File userCacheDir;

    /**
     * Convenience constructor with NO cache-behavior configuration available -- the user-level
     * cache is left disabled (this intentionally does NOT fall back to real hg's actual default
     * of "enabled at the real per-OS path", precisely so that a caller with no {@link HgRcConfig}
     * at hand -- e.g. a unit test -- can never accidentally read from or write into the real host
     * machine's shared {@code ~/.cache/lfs}/{@code $XDG_CACHE_HOME/lfs}). Production call sites
     * that DO have a repository's config available should use
     * {@link #HgLfsManager(File, HgRcConfig)} instead, which resolves the user cache exactly like
     * real hg does.
     */
    public HgLfsManager(File hgDir) {
        this(hgDir, null);
    }

    /**
     * @param config the repository's merged hgrc config, consulted for {@code [lfs] usercache}
     *     (an explicit override path) and {@code [experimental] lfs.disableusercache} (turns the
     *     user cache off entirely) -- {@code null} behaves like an empty config (user cache
     *     enabled at the real default per-OS path, matching real hg's own defaults).
     */
    public HgLfsManager(File hgDir, HgRcConfig config) {
        if (hgDir == null) {
            throw new IllegalArgumentException("HG directory cannot be null");
        }
        File storeDir = new File(hgDir, "store");
        File lfsDir = new File(storeDir, "lfs");
        this.lfsObjectsDir = new File(lfsDir, "objects");
        this.userCacheDir = config == null ? null : resolveUserCacheDir(config);
    }

    public File getLfsObjectsDir() {
        return lfsObjectsDir;
    }

    /** The resolved user-level LFS cache directory, or {@code null} if disabled/unconfigured for
     * this manager instance. Exposed mainly for tests. */
    public File getUserCacheDir() {
        return userCacheDir;
    }

    private static boolean parseBool(String value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (TRUE_TOKENS.contains(lower)) {
            return true;
        }
        if (FALSE_TOKENS.contains(lower)) {
            return false;
        }
        return defaultValue;
    }

    /**
     * Resolves the effective user-cache directory from config, or {@code null} if disabled.
     * Real hg's actual precedence (confirmed 2026-09-06 against {@code hgext/lfs/__init__.py}'s
     * {@code eh.configitem} declarations and {@code hgext/largefiles/lfutil._usercachedir}):
     * {@code experimental.lfs.disableusercache} (note the literal dot in that config KEY, not a
     * subsection -- real hg reads it as section {@code "experimental"}, key
     * {@code "lfs.disableusercache"}) wins outright when true; otherwise an explicit
     * {@code [lfs] usercache} path wins; otherwise the real per-OS default.
     */
    private static File resolveUserCacheDir(HgRcConfig config) {
        if (parseBool(config.get("experimental", "lfs.disableusercache"), false)) {
            return null;
        }
        String override = config.get("lfs", "usercache");
        if (override != null && !override.isBlank()) {
            return new File(override);
        }
        return defaultUserCacheDir(System.getenv("XDG_CACHE_HOME"), System.getenv("LOCALAPPDATA"),
                System.getenv("APPDATA"), System.getProperty("user.home"), System.getProperty("os.name", ""));
    }

    /**
     * Pure, independently-testable version of real hg's {@code lfutil._usercachedir(ui, 'lfs')}
     * per-OS default resolution (confirmed 2026-09-06 against {@code
     * hgext/largefiles/lfutil.py}): POSIX prefers {@code $XDG_CACHE_HOME/lfs}, falling back to
     * {@code $HOME/.cache/lfs}; macOS uses {@code $HOME/Library/Caches/lfs}; Windows uses
     * {@code %LOCALAPPDATA%\lfs}, falling back to {@code %APPDATA%\lfs}. Returns {@code null} if
     * the relevant environment variable(s) for the running platform are entirely unset, matching
     * real hg's own "cannot determine a cache location" case (which it treats as effectively
     * disabling the user cache, since there is nowhere agreed-upon to put it).
     */
    static File defaultUserCacheDir(String xdgCacheHome, String localAppData, String appData,
                                     String userHome, String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appDataDir = localAppData != null && !localAppData.isBlank() ? localAppData
                    : (appData != null && !appData.isBlank() ? appData : null);
            return appDataDir != null ? new File(appDataDir, "lfs") : null;
        } else if (os.contains("mac") || os.contains("darwin")) {
            return userHome != null && !userHome.isBlank()
                    ? new File(new File(new File(userHome, "Library"), "Caches"), "lfs") : null;
        } else {
            if (xdgCacheHome != null && !xdgCacheHome.isBlank()) {
                return new File(xdgCacheHome, "lfs");
            }
            return userHome != null && !userHome.isBlank() ? new File(new File(userHome, ".cache"), "lfs") : null;
        }
    }

    /**
     * Resolves the remote LFS server base URL used to fetch a missing blob.
     *
     * <p>An explicit {@code [lfs] url} config always wins outright (confirmed 2026-09-06 live:
     * {@code hg clone --config lfs.url=<custom>} attempts the batch request against exactly
     * {@code <custom>/objects/batch}, with NO further path adjustment). Otherwise, real hg derives
     * a default from the pull/push remote by appending {@code ".git/info/lfs"} -- a git-lfs
     * server-discovery convention hg reuses verbatim -- NOT the bare {@code "/info/lfs"} this
     * codebase used before this fix: confirmed 2026-09-06 by cloning a real hg 7.2 LFS repository
     * over HTTP with {@code -v} and observing the exact logged line {@code "lfs: assuming remote
     * store: http://<host>/.git/info/lfs"}.
     *
     * @return the resolved base URL (caller appends {@code "/objects/batch"} etc.), or
     *     {@code null} if neither {@code [lfs] url} nor {@code [paths] default} is configured.
     */
    public static String resolveServerUrl(HgRepository repository) {
        String override = repository.getConfig().get("lfs", "url");
        if (override != null && !override.isBlank()) {
            return override;
        }
        String remote = repository.getConfig().getPath("default");
        if (remote == null || remote.isBlank()) {
            return null;
        }
        String base = remote.endsWith("/") ? remote.substring(0, remote.length() - 1) : remote;
        return base + "/.git/info/lfs";
    }

    /**
     * Resolves the real file bytes for a filelog revision that may be LFS-flagged
     * ({@code REVIDX_EXTSTORED}), transparently following real hg's LFS flag-processor {@code
     * readfromstore} semantics: parses {@code storedContent} as an LFS pointer, serves it from
     * the local/user cache when already present, and otherwise fetches it from the server
     * resolved by {@link #resolveServerUrl} on a cache miss. Returns {@code storedContent}
     * unchanged when {@code isExtStored} is {@code false}.
     *
     * <p>Centralizes what {@code UpdateCommand}'s checkout path and (backlog 42)
     * {@code AnnotateCommand}'s content-diffing path both need, so both consistently honor the
     * same {@code [lfs] url} override and user-cache config (standard: a single read path, not
     * one-off duplicated logic per caller).
     *
     * @param pathForErrors the repository-relative file path, included in a thrown message only
     *     -- may be {@code null}
     */
    public static byte[] resolveContent(HgRepository repository, byte[] storedContent, boolean isExtStored,
                                         String pathForErrors) throws IOException {
        if (!isExtStored) {
            return storedContent;
        }
        HgLfsPointer pointer = HgLfsPointer.parse(storedContent);
        HgLfsManager manager = new HgLfsManager(repository.getHgDir(), repository.getConfig());
        if (!manager.isCached(pointer)) {
            String url = resolveServerUrl(repository);
            String suffix = pathForErrors != null ? " for " + pathForErrors : "";
            if (url == null) {
                throw new IOException("LFS object " + pointer.getOid()
                        + " not cached locally and no [lfs] url / [paths] default configured to fetch it" + suffix);
            }
            try {
                manager.fetchObject(pointer, url);
            } catch (Exception e) {
                throw new IOException("LFS object " + pointer.getOid()
                        + " not cached locally and remote fetch failed" + suffix, e);
            }
        }
        return manager.getCachedObject(pointer);
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
     * Resolves {@code oid}'s path within the user-level cache (same two-character shard scheme
     * as {@link #getLocalPath}, confirmed live -- see the class doc), or {@code null} if this
     * manager instance has no user cache configured/enabled.
     */
    private File getUserCachePath(String oid) {
        if (userCacheDir == null || oid == null || oid.length() != 64) {
            return null;
        }
        return new File(new File(userCacheDir, oid.substring(0, 2)), oid.substring(2));
    }

    private static boolean isValidCacheFile(File f, long expectedSize) {
        return f != null && f.exists() && f.isFile() && f.length() == expectedSize;
    }

    /**
     * Checks if the LFS object exists locally, in EITHER the per-repo local store or the
     * user-level cache (real hg's own {@code local.path()}/{@code local.open()}: either location
     * counts as "already have it").
     *
     * @param pointer LFS pointer metadata
     * @return true if cached (with a byte-matching size) in either layer
     */
    public boolean isCached(HgLfsPointer pointer) {
        if (pointer == null) {
            return false;
        }
        if (isValidCacheFile(getLocalPath(pointer.getOid()), pointer.getSize())) {
            return true;
        }
        return isValidCacheFile(getUserCachePath(pointer.getOid()), pointer.getSize());
    }

    /**
     * Writes binary payload into the local LFS objects directory, and -- unless the user cache is
     * disabled/unconfigured -- also into the user-level cache if not already present there.
     * Mirrors real hg's {@code local.write()}/{@code local.download()}, both of which write the
     * per-repo store then opportunistically link the same bytes into the usercache (confirmed
     * live 2026-09-06: a real hg 7.2 commit of an LFS file populates BOTH {@code
     * .hg/store/lfs/objects/<oid[0:2]>/<oid[2:]>} and {@code $XDG_CACHE_HOME/lfs/<oid[0:2]>/
     * <oid[2:]>} from a single commit).
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
        linkIntoUserCacheIfAbsent(pointer.getOid(), data);
    }

    /** Populates the user cache from {@code data} if it isn't already there (a plain copy is
     * used, not a hardlink -- functionally equivalent for hg4j's purposes and avoids EXDEV
     * failures a real cross-filesystem hardlink attempt could hit). No-op when this manager has
     * no user cache configured/enabled. */
    private void linkIntoUserCacheIfAbsent(String oid, byte[] data) throws IOException {
        File userFile = getUserCachePath(oid);
        if (userFile == null || isValidCacheFile(userFile, data.length)) {
            return;
        }
        File userParent = userFile.getParentFile();
        if (!userParent.exists() && !userParent.mkdirs()) {
            throw new IOException("Failed to create user LFS cache directories: " + userParent.getAbsolutePath());
        }
        SafeFileIO.writeAtomic(userFile, data);
    }

    /**
     * Reads the cached LFS object payload from disk -- preferring the per-repo local store
     * (matching real hg's {@code local.read()}: the repo store is checked first), falling back to
     * the user-level cache, and opportunistically backfilling the local store from the usercache
     * hit (mirrors real hg's own backfill-on-read behavior) so a later read doesn't need the
     * usercache again.
     *
     * @param pointer LFS pointer metadata
     * @return cached binary payload bytes
     * @throws IOException if the object isn't cached in either layer, or a size mismatch is found
     */
    public byte[] getCachedObject(HgLfsPointer pointer) throws IOException {
        if (pointer == null) {
            throw new HgCorruptDataException("Requested LFS object not cached or size mismatch: null");
        }
        File localFile = getLocalPath(pointer.getOid());
        if (isValidCacheFile(localFile, pointer.getSize())) {
            return Files.readAllBytes(localFile.toPath());
        }

        File userFile = getUserCachePath(pointer.getOid());
        if (isValidCacheFile(userFile, pointer.getSize())) {
            byte[] data = Files.readAllBytes(userFile.toPath());
            File parent = localFile.getParentFile();
            if (!parent.exists()) {
                parent.mkdirs();
            }
            try {
                SafeFileIO.writeAtomic(localFile, data);
            } catch (IOException backfillFailed) {
                // Best-effort backfill -- the usercache hit itself is still a valid answer even
                // if this repo's local store happens to be unwritable.
            }
            return data;
        }

        throw new HgCorruptDataException("Requested LFS object not cached or size mismatch: " + pointer.getOid());
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
