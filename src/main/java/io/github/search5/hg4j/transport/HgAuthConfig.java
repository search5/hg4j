package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.lib.HgRcConfig;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code [auth]} section of the Mercurial configuration (Mercurial's {@code httpconnection.readauthforuri}): each entry is
 * {@code <group>.<setting> = value} with the settings {@code prefix}, {@code username}, {@code password} and {@code schemes}
 * (default {@code https}). For an address the group with the longest matching prefix wins; a group that names a user must
 * match the user of the address; {@code *} as the prefix matches every host. {@code cert} and {@code key} are not used.
 *
 * <p>The section is looked for in the repository's configuration first and, below it, in the user level files
 * ({@code HGRCPATH}, or {@code /etc/mercurial/hgrc}, {@code ~/.hgrc} and {@code ~/.config/hg/hgrc}).</p>
 */
public final class HgAuthConfig {

    /** The winning group and the credentials it names (a part is {@code null} when the group does not give it). */
    public record Match(String group, String username, String password) {
    }

    private HgAuthConfig() {
    }

    /**
     * @param repositoryConfig the repository's configuration, may be {@code null}
     * @param uri              the address without credentials, e.g. {@code https://host:8443/path}
     * @param user             the user named in the address, {@code null} if none
     * @return the best matching group, or {@code null}
     */
    public static Match find(HgRcConfig repositoryConfig, String uri, String user) {
        if (uri == null || !uri.contains("://")) {
            return null;
        }
        Map<String, Map<String, String>> groups = new LinkedHashMap<>();
        collect(userConfig(), groups);
        collect(repositoryConfig, groups);

        String scheme = uri.substring(0, uri.indexOf("://"));
        String hostPath = uri.substring(uri.indexOf("://") + 3);
        int bestLength = 0;
        boolean bestHasUser = false;
        Match best = null;
        for (Map.Entry<String, Map<String, String>> entry : groups.entrySet()) {
            Map<String, String> auth = entry.getValue();
            String groupUser = auth.get("username");
            if (user != null && !user.isEmpty() && groupUser != null && !user.equals(groupUser)) {
                continue;
            }
            String prefix = auth.get("prefix");
            if (prefix == null || prefix.isEmpty()) {
                continue;
            }
            // credentials inside the prefix: they must name the user of the address
            String prefixUser = null;
            int schemeEnd = prefix.indexOf("://");
            String prefixScheme = schemeEnd >= 0 ? prefix.substring(0, schemeEnd) : null;
            String rest = schemeEnd >= 0 ? prefix.substring(schemeEnd + 3) : prefix;
            int at = rest.indexOf('@');
            int slash = rest.indexOf('/');
            if (at >= 0 && (slash < 0 || at < slash)) {
                String info = rest.substring(0, at);
                prefixUser = info.contains(":") ? info.substring(0, info.indexOf(':')) : info;
                rest = rest.substring(at + 1);
            }
            if (prefixUser != null && !prefixUser.isEmpty() && !prefixUser.equals(user)) {
                continue;
            }
            String[] schemes = prefixScheme != null ? new String[]{prefixScheme}
                    : auth.getOrDefault("schemes", "https").trim().split("\\s+");
            boolean schemeOk = false;
            for (String candidate : schemes) {
                if (candidate.equals(scheme)) {
                    schemeOk = true;
                }
            }
            boolean hostOk = rest.equals("*") || hostPath.startsWith(rest);
            boolean better = rest.length() > bestLength
                    || (rest.length() == bestLength && !bestHasUser && groupUser != null);
            if (hostOk && better && schemeOk) {
                bestLength = rest.length();
                bestHasUser = groupUser != null;
                String effectiveUser = groupUser != null ? groupUser : (user != null && !user.isEmpty() ? user : null);
                best = new Match(entry.getKey(), effectiveUser, auth.get("password"));
            }
        }
        return best;
    }

    private static void collect(HgRcConfig config, Map<String, Map<String, String>> groups) {
        if (config == null) {
            return;
        }
        for (Map.Entry<String, String> item : config.getSection("auth").entrySet()) {
            String key = item.getKey();
            int dot = key.lastIndexOf('.');
            if (dot < 0) {
                continue; // cookiefile and invalid keys
            }
            groups.computeIfAbsent(key.substring(0, dot), k -> new LinkedHashMap<>())
                    .put(key.substring(dot + 1).toLowerCase(java.util.Locale.ROOT), item.getValue());
        }
    }

    /** The user level configuration: {@code HGRCPATH} when set, otherwise the standard files. */
    static HgRcConfig userConfig() {
        HgRcConfig config = new HgRcConfig();
        String rcPath = System.getenv("HGRCPATH");
        try {
            if (rcPath != null) {
                for (String entry : rcPath.split(File.pathSeparator)) {
                    if (entry.isEmpty()) {
                        continue;
                    }
                    File file = new File(entry);
                    if (file.isDirectory()) {
                        File[] files = file.listFiles((dir, name) -> name.endsWith(".rc"));
                        if (files != null) {
                            java.util.Arrays.sort(files);
                            for (File rc : files) {
                                config.load(rc);
                            }
                        }
                    } else if (file.isFile()) {
                        config.load(file);
                    }
                }
                return config;
            }
            loadIfPresent(config, new File("/etc/mercurial/hgrc"));
            String home = System.getProperty("user.home");
            if (home != null) {
                loadIfPresent(config, new File(home, ".hgrc"));
                loadIfPresent(config, new File(home, ".config/hg/hgrc"));
            }
        } catch (IOException e) {
            // an unreadable user configuration simply gives no credentials
        }
        return config;
    }

    private static void loadIfPresent(HgRcConfig config, File file) throws IOException {
        if (file.isFile()) {
            config.load(file);
        }
    }
}
