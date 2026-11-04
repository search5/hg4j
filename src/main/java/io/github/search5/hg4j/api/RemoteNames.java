package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Real hg's {@code logexchange}: with {@code experimental.remotenames} on, what the remote's bookmarks and branch heads were at the
 * last pull, clone or push is kept in {@code .hg/logexchange/bookmarks} and {@code branches} (one {@code node NUL remote NUL name}
 * line each, after the header {@code "0\n\n"}). The entries of other remotes stay, those of this remote are replaced.
 *
 * <p>The files are written under the working copy lock, after the locks of the exchange itself are released
 * ({@code exchange.pull} and {@code push} call {@code pullremotenames} behind their {@code with} blocks).</p>
 */
public final class RemoteNames {

    /** One remote name: the changeset it pointed to, the remote it belongs to, and its name. */
    public record Entry(String node, String remote, String name) {
    }

    private static final String DIRECTORY = "logexchange";

    private RemoteNames() {
    }

    /** {@code experimental.remotenames}. */
    public static boolean enabled(HgRepository repository) {
        String value = repository.getConfig().get("experimental", "remotenames", "false").trim().toLowerCase(java.util.Locale.ROOT);
        return value.equals("true") || value.equals("1") || value.equals("yes") || value.equals("on");
    }

    /**
     * Real hg's {@code logexchange.activepath}: the name of the entry of {@code [paths]} that is this remote (compared with and
     * without credentials), otherwise the location itself.
     */
    public static String activePath(HgRepository repository, String location) {
        String comparable = location;
        for (Map.Entry<String, String> path : repository.getConfig().getSection("paths").entrySet()) {
            String url = path.getValue();
            if (url.equals(comparable) || removeAuth(url).equals(comparable)) {
                return path.getKey();
            }
        }
        return comparable;
    }

    /** What {@code activepath} compares with {@code [paths]}: the root of a local remote, otherwise the URL. */
    static String locationOf(io.github.search5.hg4j.transport.HgRemoteConnection client, String url) {
        if (client instanceof io.github.search5.hg4j.transport.HgLocalClient) {
            try {
                String path = url.startsWith("file://") ? java.net.URI.create(url).getPath() : url;
                return new File(path).getCanonicalPath().replace('\\', '/');
            } catch (IOException e) {
                return url;
            }
        }
        return url;
    }

    private static String removeAuth(String url) {
        int scheme = url.indexOf("://");
        int at = url.indexOf('@');
        if (scheme > 0 && at > scheme && url.indexOf('/', scheme + 3) > at) {
            return url.substring(0, scheme + 3) + url.substring(at + 1);
        }
        return url;
    }

    /**
     * Real hg's {@code logexchange.saveremotenames}: under the working copy lock, the bookmarks (name to node) and the branch heads
     * (branch to nodes) of {@code remotePath} replace what was kept for it. Nothing is written for an empty map.
     */
    public static void save(HgRepository repository, String remotePath, Map<String, List<String>> branches,
                            Map<String, String> bookmarks) throws IOException, HgLockException {
        try (HgLock wlock = repository.lockWorkingCopy()) {
            if (bookmarks != null && !bookmarks.isEmpty()) {
                write(repository, remotePath, "bookmarks", bookmarks, null);
            }
            if (branches != null && !branches.isEmpty()) {
                write(repository, remotePath, "branches", null, branches);
            }
        }
    }

    private static void write(HgRepository repository, String remotePath, String type, Map<String, String> bookmarks,
                              Map<String, List<String>> branches) throws IOException {
        List<Entry> old = new ArrayList<>(readFile(repository, type));
        Comparator<String> bytes = NodeIdUtil.UTF8_STRING_COMPARATOR;
        old.sort((a, b) -> {
            int c = bytes.compare(a.node(), b.node());
            if (c == 0) {
                c = bytes.compare(a.remote(), b.remote());
            }
            return c != 0 ? c : bytes.compare(a.name(), b.name());
        });
        StringBuilder text = new StringBuilder("0\n\n");
        for (Entry e : old) {
            if (!e.remote().equals(remotePath)) {
                text.append(e.node()).append('\0').append(e.remote()).append('\0').append(e.name()).append('\n');
            }
        }
        if (bookmarks != null) {
            for (Map.Entry<String, String> mark : sorted(bookmarks)) {
                if (mark.getValue() != null && !mark.getValue().isEmpty()) {
                    text.append(mark.getValue()).append('\0').append(remotePath).append('\0').append(mark.getKey()).append('\n');
                }
            }
        }
        if (branches != null) {
            TreeMap<String, List<String>> byName = new TreeMap<>(bytes);
            byName.putAll(branches);
            for (Map.Entry<String, List<String>> branch : byName.entrySet()) {
                for (String node : branch.getValue()) {
                    text.append(node).append('\0').append(remotePath).append('\0').append(branch.getKey()).append('\n');
                }
            }
        }
        File dir = new File(repository.getHgDir(), DIRECTORY);
        Files.createDirectories(dir.toPath());
        SafeFileIO.writeStringAtomic(new File(dir, type), text.toString());
    }

    private static List<Map.Entry<String, String>> sorted(Map<String, String> map) {
        TreeMap<String, String> tree = new TreeMap<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        tree.putAll(map);
        return new ArrayList<>(tree.entrySet());
    }

    private static List<Entry> readFile(HgRepository repository, String type) throws IOException {
        File file = new File(new File(repository.getHgDir(), DIRECTORY), type);
        List<Entry> entries = new ArrayList<>();
        if (!file.exists()) {
            return entries;
        }
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\0", -1);
            if (parts.length == 3) { // the header "0" has no separators and is skipped like any malformed line
                entries.add(new Entry(parts[0], parts[1], parts[2]));
            }
        }
        return entries;
    }

    /** Real hg's {@code readremotenames}: every kept remote bookmark and branch head. */
    public static List<Entry> read(HgRepository repository) throws IOException {
        List<Entry> all = new ArrayList<>(readFile(repository, "bookmarks"));
        all.addAll(readFile(repository, "branches"));
        return all;
    }

    /**
     * What a {@code pull}, {@code clone} or {@code push} keeps (real hg's {@code logexchange.pullremotenames}): the remote's bookmarks and
     * the heads of its branches that this repository has. Called with the exchange's locks still held; the files are written once they
     * are released.
     *
     * @param remoteBranchHeads the remote's {@code branchmap}, or {@code null} if it has none
     */
    static void keepAfterLock(HgRepository repository, String location, Map<String, String> remoteBookmarks,
                              Map<String, List<String>> remoteBranchHeads) throws IOException {
        if (!enabled(repository)) {
            return;
        }
        String remotePath = activePath(repository, location);
        Map<String, List<String>> branches = new LinkedHashMap<>();
        if (remoteBranchHeads != null) {
            Revlog changelog = repository.getRevlog(new File(repository.getStoreDir(), "00changelog.i"),
                    new File(repository.getStoreDir(), "00changelog.d"));
            for (Map.Entry<String, List<String>> branch : remoteBranchHeads.entrySet()) {
                List<String> present = new ArrayList<>();
                for (String node : branch.getValue()) {
                    if (changelog.findRevision(NodeIdUtil.fromHex(node)) >= 0) {
                        present.add(node);
                    }
                }
                branches.put(branch.getKey(), present);
            }
        }
        Map<String, String> bookmarks = remoteBookmarks == null ? Map.of() : new LinkedHashMap<>(remoteBookmarks);
        repository.afterLock(() -> save(repository, remotePath, branches, bookmarks));
    }
}
