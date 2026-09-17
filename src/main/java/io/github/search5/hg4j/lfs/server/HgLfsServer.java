package io.github.search5.hg4j.lfs.server;

import io.github.search5.hg4j.api.HgHook;
import io.github.search5.hg4j.lfs.HgLfsManager;
import io.github.search5.hg4j.lfs.HgLfsPointer;
import io.github.search5.hg4j.lib.HgRepository;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Production HTTP server for the Git LFS Batch API and Basic Transfer Adapter, real hg's own
 * server-side LFS implementation (see {@code hgext/lfs/wireprotolfsserver.py}: {@code
 * _processbatchrequest}/{@code _processbasictransfer}), reproduced here so hg4j-served
 * repositories can serve LFS blobs to other clients -- not just fetch them as a client itself
 * (that side is {@link HgLfsManager#fetchObject}).
 *
 * <p>A plain {@link HttpServlet}, like {@link io.github.search5.hg4j.transport.HgHttpWireServer}
 * -- deploy it in the same servlet container as that wire server, mounted under BOTH {@code
 * /.git/info/lfs/*} (the Batch API) and {@code /.hg/lfs/*} (the Basic Transfer Adapter) alongside
 * the wire server's own {@code /*} mapping; standard servlet path-mapping precedence (longest
 * matching prefix wins) routes requests under either of those two prefixes here instead of to the
 * wire server. Split into its own {@code io.github.search5.hg4j.lfs.server} package the same way
 * JGit separates {@code org.eclipse.jgit.lfs.server} from its core {@code org.eclipse.jgit.lfs}.
 */
public class HgLfsServer extends HttpServlet {

    private static final String LFS_JSON_MEDIA_TYPE = "application/vnd.git-lfs+json";
    /** Real hg's {@code gitlfspointer} version string -- only used here to satisfy {@link
     * HgLfsPointer}'s constructor when reusing {@link HgLfsManager#cacheObject}; the version field
     * itself is never persisted or read back by that call. */
    private static final String SPEC_VERSION = "https://git-lfs.github.com/spec/v1";
    private static final Pattern OID_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final HgRepository repository;
    private final HgLfsManager manager;
    /** Hooks consulted before serving a Batch API request or a Basic Transfer GET -- real hg's
     * {@code checkperm(..., 'pull')}. */
    private final List<HgHook> pullPermissionHooks = new ArrayList<>();
    /** Hooks consulted before accepting a Basic Transfer PUT -- real hg's {@code
     * checkperm(..., 'upload')}. */
    private final List<HgHook> uploadPermissionHooks = new ArrayList<>();

    /**
     * Creates an LFS server exposing {@code repository}'s LFS blob store over HTTP.
     *
     * @param repository the repository whose LFS blobs are served
     */
    public HgLfsServer(HgRepository repository) {
        this.repository = repository;
        this.manager = new HgLfsManager(repository.getHgDir(), repository.getConfig());
    }

    /** Registers a hook consulted before a Batch API request or a Basic Transfer download is
     * served -- returning {@code false} aborts it with a 403.
     * @param hook the hook to add to the pull-permission chain
     * @return this server, for chaining further {@code registerXxxHook} calls */
    public HgLfsServer registerPullPermissionHook(HgHook hook) {
        pullPermissionHooks.add(hook);
        return this;
    }

    /** Registers a hook consulted before a Basic Transfer upload is accepted -- returning {@code
     * false} aborts it with a 403.
     * @param hook the hook to add to the upload-permission chain
     * @return this server, for chaining further {@code registerXxxHook} calls */
    public HgLfsServer registerUploadPermissionHook(HgHook hook) {
        uploadPermissionHooks.add(hook);
        return this;
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            repository.refreshIfChangedOnDisk();
            String path = request.getRequestURI();
            String method = request.getMethod();

            if ("POST".equals(method) && path.endsWith("/objects/batch")) {
                handleBatch(request, response);
                return;
            }

            String oid = extractOid(path);
            if (oid != null && "PUT".equals(method)) {
                handleUpload(request, response, oid);
                return;
            }
            if (oid != null && "GET".equals(method)) {
                handleDownload(request, response, oid);
                return;
            }

            response.setStatus(404);
        } catch (Exception e) {
            response.setStatus(500);
        }
    }

    /**
     * Handles {@code POST .../.git/info/lfs/objects/batch} -- real hg's {@code
     * _processbatchrequest}: validates the LFS JSON content negotiation, then for each requested
     * object either points the client at a Basic Transfer href or reports why it can't (missing/
     * corrupt on download, already-uploaded skip signal on upload).
     */
    private void handleBatch(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!checkPermission(pullPermissionHooks, "pull")) {
            response.setStatus(403);
            return;
        }

        String contentType = request.getContentType();
        if (contentType == null || !contentType.startsWith(LFS_JSON_MEDIA_TYPE)) {
            response.setStatus(415);
            return;
        }
        String accept = request.getHeader("Accept");
        if (accept == null || !accept.startsWith(LFS_JSON_MEDIA_TYPE)) {
            response.setStatus(406);
            return;
        }

        String body;
        try (InputStream in = request.getInputStream()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        Map<String, Object> lfsRequest;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = (Map<String, Object>) new HgLfsManager.MapJsonParser(body).parse();
            lfsRequest = parsed;
        } catch (Exception e) {
            response.setStatus(400);
            return;
        }

        @SuppressWarnings("unchecked")
        List<Object> transfers = (List<Object>) lfsRequest.get("transfers");
        if (transfers != null && !transfers.contains("basic")) {
            response.setStatus(400);
            return;
        }

        String operation = (String) lfsRequest.get("operation");
        if (!"upload".equals(operation) && !"download".equals(operation)) {
            response.setStatus(400);
            return;
        }

        @SuppressWarnings("unchecked")
        List<Object> objects = (List<Object>) lfsRequest.get("objects");
        if (objects == null) {
            response.setStatus(400);
            return;
        }

        String baseUrl = repoBaseUrl(request);
        String authHeader = request.getHeader("Authorization");
        List<Object> responseObjects = new ArrayList<>();
        for (Object o : objects) {
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = (Map<String, Object>) o;
            responseObjects.add(batchResponseObject(obj, operation, baseUrl, authHeader));
        }

        Map<String, Object> responseBody = new LinkedHashMap<>();
        responseBody.put("transfer", "basic");
        responseBody.put("objects", responseObjects);

        byte[] json = toJson(responseBody).getBytes(StandardCharsets.UTF_8);
        response.setStatus(200);
        response.setHeader("Content-Type", LFS_JSON_MEDIA_TYPE);
        response.setContentLength(json.length);
        try (OutputStream out = response.getOutputStream()) {
            out.write(json);
        }
    }

    private Map<String, Object> batchResponseObject(Map<String, Object> obj, String operation, String baseUrl,
                                                      String authHeader) throws IOException {
        String oid = (String) obj.get("oid");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("oid", oid);
        result.put("size", obj.get("size"));

        boolean valid = OID_PATTERN.matcher(oid == null ? "" : oid).matches() && manager.exists(oid) && manager.verify(oid);
        boolean exists = OID_PATTERN.matcher(oid == null ? "" : oid).matches() && manager.exists(oid);

        if ("download".equals(operation)) {
            if (!exists) {
                result.put("error", errorObject(404, "The object does not exist"));
                return result;
            }
            if (!valid) {
                result.put("error", errorObject(422, "The object is corrupt"));
                return result;
            }
        } else if (valid) {
            // upload, already present and verified -- skip re-upload, no "actions" key.
            return result;
        }

        result.put("actions", Map.of(operation, transferAction(baseUrl, oid, authHeader)));
        return result;
    }

    private static Map<String, Object> errorObject(int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        return error;
    }

    private static Map<String, Object> transferAction(String baseUrl, String oid, String authHeader) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("Accept", "application/vnd.git-lfs");
        if (authHeader != null) {
            header.put("Authorization", authHeader);
        }

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("href", baseUrl + "/.hg/lfs/objects/" + oid);
        action.put("expires_at", Instant.now().plusSeconds(600).toString());
        action.put("header", header);
        return action;
    }

    /**
     * Handles {@code PUT .../.hg/lfs/objects/{oid}} -- real hg's Basic Transfer upload: stores the
     * request body, rejecting it with 422 if its hash doesn't match {@code oid}.
     */
    private void handleUpload(HttpServletRequest request, HttpServletResponse response, String oid) throws IOException {
        if (!checkPermission(uploadPermissionHooks, "upload")) {
            response.setStatus(403);
            return;
        }

        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readAllBytes();
        }
        if (!HgLfsPointer.sha256Hex(body).equalsIgnoreCase(oid)) {
            response.setStatus(422);
            return;
        }

        boolean existed = manager.exists(oid);
        manager.cacheObject(new HgLfsPointer(SPEC_VERSION, oid, body.length), body);
        response.setStatus(existed ? 200 : 201);
    }

    /**
     * Handles {@code GET .../.hg/lfs/objects/{oid}} -- real hg's Basic Transfer download: streams
     * back the stored blob, or 404/422 if it's missing/corrupt.
     */
    private void handleDownload(HttpServletRequest request, HttpServletResponse response, String oid) throws IOException {
        if (!checkPermission(pullPermissionHooks, "pull")) {
            response.setStatus(403);
            return;
        }

        if (!manager.exists(oid)) {
            response.setStatus(404);
            return;
        }
        if (!manager.verify(oid)) {
            response.setStatus(422);
            return;
        }

        byte[] data = Files.readAllBytes(manager.locate(oid).toPath());
        response.setStatus(200);
        response.setHeader("Content-Type", "application/octet-stream");
        response.setContentLength(data.length);
        try (OutputStream out = response.getOutputStream()) {
            out.write(data);
        }
    }

    private boolean checkPermission(List<HgHook> hooks, String operation) throws IOException {
        if (hooks.isEmpty()) {
            return true;
        }
        Map<String, Object> context = Map.of("operation", operation, "repository", repository);
        for (HgHook hook : hooks) {
            if (!hook.run(context)) {
                return false;
            }
        }
        return true;
    }

    private static String extractOid(String path) {
        int idx = path.lastIndexOf('/');
        if (idx == -1) {
            return null;
        }
        String candidate = path.substring(idx + 1);
        return OID_PATTERN.matcher(candidate).matches() ? candidate : null;
    }

    /** Real hg's {@code req.baseurl + req.apppath} -- everything before {@code /.git/info/lfs},
     * which the Basic Transfer href in a batch response is then built relative to. */
    private static String repoBaseUrl(HttpServletRequest request) {
        String url = request.getRequestURL().toString();
        int idx = url.indexOf("/.git/info/lfs");
        return idx == -1 ? url : url.substring(0, idx);
    }

    private static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        writeJson(value, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeJson(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeJsonString(s, sb);
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeJsonString(String.valueOf(entry.getKey()), sb);
                sb.append(':');
                writeJson(entry.getValue(), sb);
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeJson(item, sb);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass());
        }
    }

    private static void writeJsonString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
    }
}
