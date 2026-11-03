package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.lfs.errors.HgLfsCorruptObjectException;
import io.github.search5.hg4j.lfs.errors.HgLfsException;
import io.github.search5.hg4j.lib.HgRcConfig;
import io.github.search5.hg4j.lib.ProgressMonitor;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Talks to a Git LFS server (the {@code _gitlfsremote} of real hg's {@code hgext/lfs/blobstore.py}; JGit's
 * {@code LfsConnectionFactory} with the transfer code): one Batch API request for a set of objects, then the Basic transfer of
 * each object the answer gives an action for. Error texts and hints are the original's.
 *
 * <p>Settings: {@code [lfs] retry} (default 5; a transfer that fails with a plain I/O error is repeated, but a failure of the
 * HTTP exchange itself is not, as measured against real hg), {@code [experimental] lfs.worker-enable} (parallel transfers,
 * default true) and {@code lfs.user-agent}. Credentials in the URL ({@code http://user:pass@host/...}) are sent as Basic
 * authentication; a server asking for any other scheme is refused.
 */
final class HgLfsClient {

    private static final String MEDIA_TYPE = "application/vnd.git-lfs+json";
    private static final int MAX_WORKERS = 4;

    private final HgLfsManager local;
    private final HgRcConfig config;
    private final String baseUrl;
    private volatile String basicAuthorization;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    HgLfsClient(HgLfsManager local, HgRcConfig config, String url) {
        this.local = local;
        this.config = config;
        URI uri = URI.create(url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
        String userInfo = uri.getUserInfo();
        String bareUrl;
        if (userInfo != null) {
            String hostPart = uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
            bareUrl = uri.getScheme() + "://" + hostPart + (uri.getRawPath() == null ? "" : uri.getRawPath());
        } else {
            bareUrl = uri.toString();
        }
        this.baseUrl = bareUrl;
        this.user = userInfo == null ? null : (userInfo.contains(":") ? userInfo.substring(0, userInfo.indexOf(':')) : userInfo);
        // all credentials, those of the address and those of [auth], are sent only after the server asked for them (see send())
        this.addressCredentials = userInfo != null && userInfo.contains(":") ? userInfo : null;
        this.basicAuthorization = null;
    }

    private final String addressCredentials;

    private final String user;

    /**
     * Sends a request; a 401 that asks for Basic authentication, when the address gave no credentials, is answered once with the
     * ones the {@code [auth]} section holds for the address (Mercurial's Basic authentication handler), and every later request
     * carries them from the start.
     */
    private <T> HttpResponse<T> send(HttpRequest.Builder request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        HttpResponse<T> response = http.send(request.build(), handler);
        if (response.statusCode() == 401 && basicAuthorization == null) {
            String challenge = response.headers().firstValue("WWW-Authenticate").orElse("");
            if (challenge.toLowerCase(java.util.Locale.ROOT).startsWith("basic")) {
                String credentials = addressCredentials;
                if (credentials == null) {
                    io.github.search5.hg4j.transport.HgAuthConfig.Match match =
                            io.github.search5.hg4j.transport.HgAuthConfig.find(config, baseUrl, user);
                    if (match != null && match.username() != null && match.password() != null) {
                        credentials = match.username() + ":" + match.password();
                    }
                }
                if (credentials != null) {
                    basicAuthorization = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                    request.header("Authorization", basicAuthorization);
                    response = http.send(request.build(), handler);
                }
            }
        }
        return response;
    }

    // ---------------------------------------------------------------- public operations

    /** Downloads the objects that are not stored locally. {@code filenames} (oid to path) only improve error messages. */
    void download(Collection<HgLfsPointer> pointers, Map<String, String> filenames) throws IOException, InterruptedException {
        Map<String, HgLfsPointer> missing = new TreeMap<>();
        for (HgLfsPointer pointer : pointers) {
            if (!local.isCached(pointer)) {
                missing.putIfAbsent(pointer.getOid(), pointer);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        List<Map<String, Object>> objects = batch("download", missing.values(), filenames);
        transferAll(objects, missing, "download", filenames);
    }

    /** Uploads the objects the server does not have. */
    void upload(Collection<HgLfsPointer> pointers) throws IOException, InterruptedException {
        Map<String, HgLfsPointer> byOid = new TreeMap<>();
        for (HgLfsPointer pointer : pointers) {
            byOid.putIfAbsent(pointer.getOid(), pointer);
        }
        if (byOid.isEmpty()) {
            return;
        }
        List<Map<String, Object>> objects = batch("upload", byOid.values(), Map.of());
        transferAll(objects, byOid, "upload", Map.of());
    }

    // ---------------------------------------------------------------- Batch API

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> batch(String action, Collection<HgLfsPointer> pointers, Map<String, String> filenames)
            throws IOException, InterruptedException {
        StringBuilder objects = new StringBuilder();
        for (HgLfsPointer pointer : pointers) {
            if (objects.length() > 0) {
                objects.append(", ");
            }
            objects.append("{\"oid\": \"").append(pointer.getOid()).append("\", \"size\": ").append(pointer.getSize()).append('}');
        }
        // json.dumps layout of the original, which sends no "transfers" key (the server then assumes basic)
        String requestData = "{\"objects\": [" + objects + "], \"operation\": \"" + action + "\"}";
        String url = baseUrl + "/objects/batch";
        HttpRequest.Builder request = base(url).header("Accept", MEDIA_TYPE).header("Content-Type", MEDIA_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(requestData, StandardCharsets.UTF_8));
        HttpResponse<String> response;
        try {
            response = send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw error("LFS error: " + reason(e), 0, "the \"lfs.url\" config may be used to override " + baseUrl, e);
        }
        if (response.statusCode() >= 400) {
            String hint = switch (response.statusCode()) {
                case 400 -> "check that lfs serving is enabled on " + baseUrl + " and \"" + action + "\" is supported";
                case 404 -> "the \"lfs.url\" config may be used to override " + baseUrl;
                default -> "api=" + url + ", action=" + action;
            };
            throw error("LFS HTTP error: " + httpError(response), response.statusCode(), hint, null);
        }

        Map<String, Object> parsed;
        try {
            parsed = (Map<String, Object>) new HgLfsManager.MapJsonParser(response.body()).parse();
        } catch (IOException | RuntimeException e) {
            throw new HgLfsException("LFS server returns invalid JSON: " + response.body(), 0, e);
        }
        Object listed = parsed.get("objects");
        List<Map<String, Object>> answered = new ArrayList<>();
        if (listed instanceof List<?> list) {
            for (Object element : list) {
                answered.add((Map<String, Object>) element);
            }
        }
        checkForServerError(pointers, answered, action, filenames);
        // practically, this skips uploading objects which exist on the server
        List<Map<String, Object>> withAction = new ArrayList<>();
        for (Map<String, Object> object : answered) {
            Object actions = object.get("actions");
            if (actions instanceof Map<?, ?> map && map.containsKey(action)) {
                withAction.add(object);
            }
        }
        return withAction;
    }

    /** Real hg's {@code _checkforservererror}: the first object with an error (or a download without action) fails the lot. */
    @SuppressWarnings("unchecked")
    private void checkForServerError(Collection<HgLfsPointer> pointers, List<Map<String, Object>> objects, String action,
                                     Map<String, String> filenames) throws IOException {
        for (Map<String, Object> object : objects) {
            int code;
            if (!object.containsKey("error")) {
                Object actions = object.get("actions");
                boolean noDownload = actions == null || !((Map<String, Object>) actions).containsKey("download");
                if (action.equals("download") && noDownload) {
                    // some servers just drop "download" instead of setting an error: the same as a 404
                    code = 404;
                } else {
                    continue;
                }
            } else {
                Object codeValue = ((Map<String, Object>) object.get("error")).get("code");
                code = codeValue instanceof Number number ? number.intValue() : 500;
            }
            String oid = String.valueOf(object.get("oid"));
            boolean requested = pointers.stream().anyMatch(p -> p.getOid().equals(oid));
            if (!requested) {
                throw new HgLfsException("LFS server error. Unsolicited response for oid " + oid, code);
            }
            String message = switch (code) {
                case 404 -> "The object does not exist";
                case 410 -> "The object was removed by the owner";
                case 422 -> "Validation error";
                case 500 -> "Internal server error";
                default -> "status code " + code;
            };
            String filename = filenames.getOrDefault(oid, "unknown");
            throw HgLfsException.forStatus(code, "LFS server error for \"" + filename + "\": " + message);
        }
    }

    // ---------------------------------------------------------------- Basic transfer

    private void transferAll(List<Map<String, Object>> objects, Map<String, HgLfsPointer> pointers, String action,
                             Map<String, String> filenames) throws IOException, InterruptedException {
        // sorted by object id, like the original
        Map<String, Map<String, Object>> sorted = new TreeMap<>();
        for (Map<String, Object> object : objects) {
            sorted.put((String) object.get("oid"), object);
        }
        int retries = intConfig("lfs", "retry", 5);
        boolean parallel = boolConfig("experimental", "lfs.worker-enable", true) && sorted.size() > 1;
        ProgressMonitor monitor = HgLfsProgress.current();
        long total = 0;
        for (Map<String, Object> object : sorted.values()) {
            total += object.get("size") instanceof Number size ? size.longValue() : 0;
        }
        if (monitor != null) {
            monitor.start("lfs " + action + "ing", (int) Math.min(total, Integer.MAX_VALUE));
        }
        long processed = 0;
        long reported = 0;
        int blobs = 0;
        try {
            if (!parallel) {
                for (Map.Entry<String, Map<String, Object>> entry : sorted.entrySet()) {
                    transferWithRetry(entry.getValue(), pointers, action, retries);
                    processed += sizeOf(entry.getValue());
                    blobs++;
                    reported = report(monitor, processed, reported);
                }
            } else {
                ExecutorService pool = Executors.newFixedThreadPool(Math.min(MAX_WORKERS, sorted.size()));
                try {
                    List<Map<String, Object>> order = new ArrayList<>(sorted.values());
                    List<Future<?>> futures = new ArrayList<>();
                    for (Map<String, Object> object : order) {
                        futures.add(pool.submit(() -> {
                            transferWithRetry(object, pointers, action, retries);
                            return null;
                        }));
                    }
                    for (int i = 0; i < futures.size(); i++) {
                        try {
                            futures.get(i).get();
                        } catch (ExecutionException e) {
                            if (e.getCause() instanceof IOException io) {
                                throw io;
                            }
                            throw new IOException(e.getCause());
                        }
                        processed += sizeOf(order.get(i));
                        blobs++;
                        reported = report(monitor, processed, reported);
                    }
                } finally {
                    pool.shutdownNow();
                }
            }
        } finally {
            if (monitor != null) {
                monitor.end();
            }
        }
        if (monitor != null && blobs > 0) {
            monitor.message("lfs: " + (action.equals("upload") ? "uploaded" : "downloaded") + " " + blobs + " files ("
                    + HgByteCount.format(processed) + ")");
        }
    }

    private static long sizeOf(Map<String, Object> object) {
        return object.get("size") instanceof Number size ? size.longValue() : 0;
    }

    /** Tells the monitor the bytes done since the last report (it adds them up); returns the new reported total. */
    private static long report(ProgressMonitor monitor, long processed, long reported) {
        if (monitor == null) {
            return reported;
        }
        long now = Math.min(processed, Integer.MAX_VALUE);
        if (now > reported) {
            monitor.update((int) (now - reported));
        }
        return now;
    }

    private void transferWithRetry(Map<String, Object> object, Map<String, HgLfsPointer> pointers, String action, int retries)
            throws IOException, InterruptedException {
        int remaining = retries;
        while (true) {
            try {
                transfer(object, pointers, action);
                return;
            } catch (HgLfsException wrapped) {
                throw wrapped; // an error of the HTTP exchange is reported as it is, not repeated
            } catch (IOException plain) {
                if (remaining <= 0) {
                    throw plain;
                }
                remaining--; // a plain I/O error (typically the local store) is repeated, as the original does for OSError
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void transfer(Map<String, Object> object, Map<String, HgLfsPointer> pointers, String action)
            throws IOException, InterruptedException {
        String oid = (String) object.get("oid");
        HgLfsPointer pointer = pointers.get(oid);
        if (pointer == null) {
            throw new HgLfsException("LFS server error. Unsolicited response for oid " + oid);
        }
        Map<String, Object> actionData = (Map<String, Object>) ((Map<String, Object>) object.get("actions")).get(action);
        String href = (String) actionData.get("href");
        if (href == null) {
            throw new HgLfsException("No " + action + " href for LFS object: " + oid);
        }
        Map<String, Object> headers = (Map<String, Object>) actionData.getOrDefault("header", Map.of());

        HttpRequest.Builder request = base(href);
        headers.forEach((name, value) -> request.header(name, String.valueOf(value)));
        byte[] blob = null;
        if (action.equals("upload")) {
            // the blob is read from the local store, and verified first
            try {
                blob = local.getCachedObject(pointer);
            } catch (IOException missingOrWrongSize) {
                throw new HgLfsCorruptObjectException("detected corrupt lfs object: " + oid + "\n(run hg verify)");
            }
            if (!HgLfsPointer.sha256Hex(blob).equals(oid)) {
                throw new HgLfsCorruptObjectException("detected corrupt lfs object: " + oid + "\n(run hg verify)");
            }
            request.header("Content-Type", "application/octet-stream").PUT(HttpRequest.BodyPublishers.ofByteArray(blob));
        } else {
            request.GET();
        }

        HttpResponse<byte[]> response;
        try {
            response = send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw error("LFS error: " + reason(e), 0, "attempted connection to " + href, e);
        }
        if (response.statusCode() >= 400) {
            throw error("LFS HTTP error: " + httpError(response) + " (oid=" + oid + ", action=" + action + ")",
                    response.statusCode(), null, null);
        }
        if (action.equals("download")) {
            byte[] body = response.body();
            if (!HgLfsPointer.sha256Hex(body).equals(oid)) {
                throw new HgLfsCorruptObjectException("detected corrupt lfs object: " + oid + "\n(run hg verify)");
            }
            local.cacheObject(pointer, body);
        }
    }

    // ---------------------------------------------------------------- helpers

    private HttpRequest.Builder base(String url) {
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", userAgent());
        if (basicAuthorization != null) {
            request.header("Authorization", basicAuthorization);
        }
        return request;
    }

    private String userAgent() {
        String configured = config == null ? null : config.get("experimental", "lfs.user-agent");
        return configured != null && !configured.isBlank() ? configured : "git-lfs/2.3.4 (hg4j)";
    }

    /** {@code HTTPError}'s text: "HTTP Error 404: Not Found". A 401 asking for anything but Basic authentication is refused. */
    private String httpError(HttpResponse<?> response) {
        int status = response.statusCode();
        if (status == 401) {
            String challenge = response.headers().firstValue("WWW-Authenticate").orElse(null);
            if (challenge != null && !challenge.isBlank() && !challenge.split("\\s+")[0].equalsIgnoreCase("basic")) {
                return "HTTP Error 401: the server must support Basic Authentication";
            }
        }
        return "HTTP Error " + status + ": " + reasonPhrase(status);
    }

    private static String reasonPhrase(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 406 -> "Not Acceptable";
            case 409 -> "Conflict";
            case 410 -> "Gone";
            case 413 -> "Request Entity Too Large";
            case 415 -> "Unsupported Media Type";
            case 422 -> "Unprocessable Entity";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 507 -> "Insufficient Storage";
            default -> "Error";
        };
    }

    /** The reason of a failed exchange, as {@code _urlerrorreason} words it where the cause is known. */
    private static String reason(IOException e) {
        if (e instanceof ConnectException) {
            return "Connection refused";
        }
        if (e instanceof UnknownHostException) {
            return "Name or service not known";
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static HgLfsException error(String message, int status, String hint, Throwable cause) {
        return HgLfsException.forStatus(status, hint == null ? message : message + "\n(" + hint + ")", cause);
    }

    private int intConfig(String section, String key, int fallback) {
        String value = config == null ? null : config.get(section, key);
        try {
            return value == null ? fallback : Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean boolConfig(String section, String key, boolean fallback) {
        String value = config == null ? null : config.get(section, key);
        if (value == null) {
            return fallback;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "0", "no", "false", "off", "never" -> false;
            case "1", "yes", "true", "on", "always" -> true;
            default -> fallback;
        };
    }
}
