package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.lib.HgRcConfig;

import java.util.Arrays;
import java.util.List;

/**
 * The access rules of Mercurial's web server ({@code hgweb.common.checkauthz}), read from the {@code [web]} section of the
 * served repository: {@code deny_read}, {@code allow_read}, {@code allowpull} (default true), {@code push_ssl} (default true),
 * {@code deny_push} and {@code allow-push}/{@code allow_push}. The user is the one the HTTP server has authenticated
 * (Mercurial's {@code REMOTE_USER}); a request without one is checked as an anonymous user.
 */
public final class HgWebPermissions {

    /** The operation a request asks for. */
    public enum Operation {
        PULL, PUSH, UPLOAD
    }

    /** A refusal: the status code and the message real hg sends with it. */
    public static final class Denied extends Exception {
        private final int status;

        Denied(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private HgWebPermissions() {
    }

    /**
     * @param config  the configuration of the served repository
     * @param scheme  {@code http} or {@code https}
     * @param method  the HTTP method of the request
     * @param user    the authenticated user, {@code null} when there is none
     * @throws Denied if the request is not allowed
     */
    public static void check(HgRcConfig config, String scheme, String method, String user, Operation operation) throws Denied {
        List<String> denyRead = list(config, "deny_read", "deny-read");
        if (!denyRead.isEmpty() && (user == null || isMember(user, denyRead))) {
            throw new Denied(401, "read not authorized");
        }
        List<String> allowRead = list(config, "allow_read", "allow-read");
        if (!allowRead.isEmpty() && !isMember(user, allowRead)) {
            throw new Denied(401, "read not authorized");
        }
        if (operation == Operation.PULL) {
            if (!bool(config, true, "allowpull", "allow_pull", "allow-pull")) {
                throw new Denied(401, "pull not authorized");
            }
            return;
        }
        if (operation == Operation.UPLOAD) {
            if (!"PUT".equals(method)) {
                throw new Denied(405, "upload requires PUT request");
            }
        } else if (!"POST".equals(method)) {
            throw new Denied(405, "push requires POST request");
        }
        if (bool(config, true, "push_ssl", "push-ssl") && !"https".equalsIgnoreCase(scheme)) {
            throw new Denied(403, "ssl required");
        }
        List<String> deny = list(config, "deny_push", "deny-push");
        if (!deny.isEmpty() && (user == null || isMember(user, deny))) {
            throw new Denied(401, "push not authorized");
        }
        List<String> allow = list(config, "allow-push", "allow_push");
        if (allow.isEmpty() || !isMember(user, allow)) {
            throw new Denied(401, "push not authorized");
        }
    }

    private static boolean isMember(String user, List<String> users) {
        return users.contains("*") || (user != null && users.contains(user));
    }

    private static String value(HgRcConfig config, String... keys) {
        for (String key : keys) {
            String value = config.get("web", key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static List<String> list(HgRcConfig config, String... keys) {
        String value = value(config, keys);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("[\\s,]+")).filter(s -> !s.isEmpty()).toList();
    }

    private static boolean bool(HgRcConfig config, boolean fallback, String... keys) {
        String value = value(config, keys);
        if (value == null) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "1", "yes", "true", "on", "always" -> true;
            case "0", "no", "false", "off", "never" -> false;
            default -> fallback;
        };
    }
}
