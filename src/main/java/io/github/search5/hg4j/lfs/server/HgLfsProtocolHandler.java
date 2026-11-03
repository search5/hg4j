package io.github.search5.hg4j.lfs.server;

import io.github.search5.hg4j.lfs.HgLfsConfig;
import io.github.search5.hg4j.lfs.HgLfsManager;
import io.github.search5.hg4j.lfs.errors.HgLfsCorruptObjectException;
import io.github.search5.hg4j.lib.HgRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The LFS endpoints of {@code hg serve} (JGit's {@code LfsProtocolServlet} and {@code FileLfsServlet} in one), after real hg's
 * {@code hgext/lfs/wireprotolfsserver.py}: the Batch API at {@code .git/info/lfs/objects/batch} and the Basic transfer adapter at
 * {@code .hg/lfs/objects/<oid>}. Status codes, texts and the JSON layout are the original's.
 */
public final class HgLfsProtocolHandler {

    private static final String MEDIA_TYPE = "application/vnd.git-lfs+json";
    static final String BATCH_PATH = ".git/info/lfs/objects/batch";
    static final String TRANSFER_PREFIX = ".hg/lfs/objects";

    private HgLfsProtocolHandler() {
    }

    /**
     * Whether this server answers {@code path} (relative to the application root, no leading slash): LFS serving must be on
     * ({@code [experimental] lfs.serve}, default true) and the {@code lfs} extension enabled for the repository.
     */
    public static boolean handles(HgRepository repository, String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        boolean lfsPath = path.equals(BATCH_PATH) || path.startsWith(TRANSFER_PREFIX);
        return lfsPath && HgLfsConfig.isServing(repository) && HgLfsConfig.isExtensionEnabled(repository);
    }

    /** Answers a request {@link #handles} accepted. */
    public static void handle(HgRepository repository, String path, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        HgLargeFileRepository store = new HgFileLfsRepository(repository);
        if (path.equals(BATCH_PATH)) {
            batch(store, request, response);
        } else {
            basicTransfer(store, path, request, response);
        }
    }

    // ---------------------------------------------------------------- Batch API

    private static void batch(HgLargeFileRepository store, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!"POST".equals(request.getMethod())) {
            httpError(response, 405, null);
            return;
        }
        if (!MEDIA_TYPE.equals(request.getHeader("Content-Type"))) {
            httpError(response, 415, null);
            return;
        }
        if (!MEDIA_TYPE.equals(request.getHeader("Accept"))) {
            httpError(response, 406, null);
            return;
        }

        Map<String, Object> lfsRequest;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = (Map<String, Object>) new HgLfsManager.MapJsonParser(
                    new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).parse();
            lfsRequest = parsed;
        } catch (IOException | RuntimeException e) {
            httpError(response, 500, null);
            return;
        }

        // if no transfer handler is explicitly requested, 'basic' is assumed
        Object transfers = lfsRequest.get("transfers");
        if (transfers instanceof List<?> list && !list.contains("basic")) {
            httpError(response, 400, "Only the basic LFS transfer handler is supported");
            return;
        }
        String operation = String.valueOf(lfsRequest.get("operation"));
        if (!operation.equals("upload") && !operation.equals("download")) {
            httpError(response, 400, "Unsupported LFS transfer operation: " + operation);
            return;
        }

        List<String> objects = new ArrayList<>();
        Object requested = lfsRequest.get("objects");
        if (requested instanceof List<?> list) {
            for (Object element : list) {
                @SuppressWarnings("unchecked")
                Map<String, Object> object = (Map<String, Object>) element;
                objects.add(describe(store, request, operation, object));
            }
        }
        // json.dumps layout: ", " and ": " separators, keys in insertion order
        String body = "{\"transfer\": \"basic\", \"objects\": [" + String.join(", ", objects) + "]}";
        response.setStatus(200);
        response.setHeader("Content-Type", MEDIA_TYPE);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    /** One object of the batch answer ({@code _batchresponseobjects}). */
    private static String describe(HgLargeFileRepository store, HttpServletRequest request, String operation,
                                   Map<String, Object> object) throws IOException {
        String oid = (String) object.get("oid");
        Object size = object.get("size");
        StringBuilder out = new StringBuilder("{\"oid\": ").append(json(oid)).append(", \"size\": ").append(jsonNumber(size));

        boolean exists;
        boolean verifies = false;
        try {
            exists = store.has(oid);
            if (exists) {
                verifies = store.verify(oid);
            }
        } catch (IOException failure) {
            return out.append(", \"error\": {\"code\": 500, \"message\": ")
                    .append(json(failure.getMessage() == null ? "Internal Server Server" : failure.getMessage())).append("}}").toString();
        }

        // items are always listed for downloads; for uploads they are dropped if they already exist and are intact
        if (operation.equals("download")) {
            if (!exists) {
                return out.append(", \"error\": {\"code\": 404, \"message\": \"The object does not exist\"}}").toString();
            } else if (!verifies) {
                return out.append(", \"error\": {\"code\": 422, \"message\": \"The object is corrupt\"}}").toString();
            }
        } else if (verifies) {
            return out.append('}').toString(); // skip 'actions': already uploaded
        }

        String expiresAt = LocalDateTime.now().plusMinutes(10).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));
        StringBuilder header = new StringBuilder("{\"Accept\": \"application/vnd.git-lfs\"");
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Basic ")) {
            header.append(", \"Authorization\": ").append(json(authorization));
        }
        header.append('}');
        return out.append(", \"actions\": {").append(json(operation)).append(": {\"href\": ")
                .append(json(baseUrl(request) + "/.hg/lfs/objects/" + oid))
                .append(", \"expires_at\": ").append(json(expiresAt)).append(", \"header\": ").append(header).append("}}}").toString();
    }

    private static String baseUrl(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host == null || host.isBlank()) {
            host = request.getServerName() + ":" + request.getServerPort();
        }
        return request.getScheme() + "://" + host;
    }

    // ---------------------------------------------------------------- Basic transfer

    private static void basicTransfer(HgLargeFileRepository store, String path, HttpServletRequest request,
                                      HttpServletResponse response) throws IOException {
        String[] parts = path.split("/");
        if (parts.length != 4) {
            httpError(response, 404, null);
            return;
        }
        String oid = parts[3];
        String method = request.getMethod();
        if (method.equals("PUT")) {
            boolean existed = store.has(oid);
            byte[] content = request.getInputStream().readAllBytes();
            long announced = request.getContentLengthLong();
            try {
                store.store(oid, announced < 0 ? content.length : announced, content);
                response.setStatus(existed ? 200 : 201);
            } catch (HgLfsCorruptObjectException corrupt) {
                response.setStatus(422); // 'corrupt blob'
            }
            response.setHeader("Content-Type", "text/plain; charset=utf-8");
        } else if (method.equals("GET")) {
            byte[] content = null;
            try {
                content = store.read(oid);
            } catch (HgLfsCorruptObjectException corrupt) {
                response.setStatus(422); // 'corrupt blob'
                response.setHeader("Content-Type", "application/octet-stream");
                return;
            } catch (FileNotFoundException missing) {
                response.setStatus(500);
                return;
            }
            response.setStatus(200);
            response.setHeader("Content-Type", "application/octet-stream");
            response.setContentLength(content.length);
            response.getOutputStream().write(content);
        } else {
            httpError(response, 405, "Unsupported LFS transfer method: " + method);
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * {@code _sethttperror}: the status and an empty plain-text body. The original also puts {@code message} into the status
     * line's reason phrase; the servlet API cannot set that, so only the status code is the same.
     */
    private static void httpError(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setHeader("Content-Type", "text/plain; charset=utf-8");
    }

    private static String json(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        out.append(String.format("\\u%04x", (int) c)); // json.dumps escapes non-ASCII
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String jsonNumber(Object size) {
        if (size instanceof Number number) {
            double d = number.doubleValue();
            return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
        }
        return "null";
    }
}
