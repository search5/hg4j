package io.github.search5.hg4j.transport.wireprotov1;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.phase.PhaseRoots;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the wire servers share about the served repository: its changelog, the pushkey namespaces
 * ({@code bookmarks}, {@code namespaces}, {@code phases}) for {@code listkeys}/{@code pushkey}, and whether it is publishing.
 */
public final class RepoSupport {

    private RepoSupport() {
    }

    /** {@code phases.publish} of the repository's own {@code .hg/hgrc}; true unless set to a false value (default true, like hg). */
    public static boolean isPublishing(HgRepository repo) {
        String raw = repo.getConfig().get("phases", "publish", "true");
        switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "0": case "no": case "false": case "off": case "never":
                return false;
            default:
                return true;
        }
    }

    /**
     * Implements {@code listkeys} for a namespace: {@code bookmarks} (name to hex node, hiding bookmarks of secret
     * changesets), {@code namespaces}, {@code phases} (draft roots plus {@code publishing} when the repository is
     * publishing) and {@code obsolete} (markers, only when obsolescence exchange is enabled).
     * @param repo the served repository; must not be null
     * @param namespace the pushkey namespace name
     * @return a new ordered map of keys to values; empty for an unknown namespace
     * @throws IOException if the repository data cannot be read
     */
    public static Map<String, String> readListKeys(HgRepository repo, String namespace) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        if ("bookmarks".equals(namespace)) {
            File bkFile = new File(repo.getHgDir(), "bookmarks");
            if (bkFile.exists()) {
                for (String line : Files.readAllLines(bkFile.toPath(), StandardCharsets.UTF_8)) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    int spaceIdx = line.indexOf(' ');
                    if (spaceIdx != -1) {
                        map.put(line.substring(spaceIdx + 1).trim(), line.substring(0, spaceIdx).trim());
                    }
                }
                // served view: a bookmark of a secret changeset is not listed (bookmarks.listbookmarks checks hasnode)
                Revlog changelog = changelog(repo);
                boolean[] hidden = io.github.search5.hg4j.transport.ServedView.hidden(repo, changelog);
                if (hidden != null) {
                    map.values().removeIf(hex -> {
                        int rev = changelog.findRevision(NodeIdUtil.fromHex(hex));
                        return rev >= 0 && hidden[rev];
                    });
                }
            }
        } else if ("namespaces".equals(namespace)) {
            // pushkey.listkeys(b'namespaces'): the names of the pushkey namespaces this repository serves, each with an
            // empty value. hg 7.2 lists bookmarks, namespaces and phases (plus obsolete only when obsolescence markers
            // are exchanged, which this server does not serve). A client reads this to learn whether the remote has
            // bookmarks at all: without it `hg incoming -B` / `outgoing -B` say "remote doesn't support bookmarks".
            map.put("bookmarks", "");
            map.put("namespaces", "");
            if (io.github.search5.hg4j.obsolete.ObsStore.exchangeEnabled(repo)) {
                map.put("obsolete", ""); // registered when obsolescence markers are exchanged (obsolete.isenabled(exchangeopt))
            }
            map.put("phases", "");
        } else if ("obsolete".equals(namespace)) {
            if (io.github.search5.hg4j.obsolete.ObsStore.exchangeEnabled(repo)) {
                map.putAll(io.github.search5.hg4j.obsolete.ObsStore.listMarkers(repo));
            }
        } else if ("phases".equals(namespace)) {
            // Real hg's phases.listphases(): the DRAFT ROOTS (not every non-public changeset), each valued "1",
            // and, when the repository is publishing, an extra "publishing" = "True". The extra key matters:
            // without it a client pulling from this server cannot tell it is publishing, and keeps everything
            // it pulled as draft instead of making it public. Roots whose effective phase is higher (secret,
            // through an ancestor) are not offered; roots this repository does not have are skipped.
            PhaseRoots phaseRoots = repo.getPhaseRoots();
            Revlog changelog = changelog(repo);
            for (NodeId root : phaseRoots.getRoots(PhaseRoots.Phase.DRAFT)) {
                if (changelog.findRevision(root.getBytes()) < 0) {
                    continue;
                }
                if (phaseRoots.getPhase(root, changelog).getValue() <= PhaseRoots.Phase.DRAFT.getValue()) {
                    map.put(NodeIdUtil.toHex(root.getBytes()), String.valueOf(PhaseRoots.Phase.DRAFT.getValue()));
                }
            }
            if (isPublishing(repo)) {
                map.put("publishing", "True");
            }
        }
        return map;
    }

    /**
     * Applies a {@code pushkey} update to the repository.
     * @param repo the served repository; must not be null
     * @param namespace the pushkey namespace
     * @param key the key to update
     * @param oldVal the value the caller expects the key to have currently
     * @param newVal the new value
     * @return true if the update was accepted and applied, false if it was refused
     * @throws IOException if the repository cannot be read or written
     */
    public static boolean applyPushkey(HgRepository repo, String namespace, String key, String oldVal, String newVal) throws IOException {
        return applyPushkey(repo, namespace, key, oldVal, newVal, false);
    }

    /** Whether {@link #applyPushkey} would succeed right now, without changing anything. */
    public static boolean canApplyPushkey(HgRepository repo, String namespace, String key, String oldVal, String newVal) throws IOException {
        return applyPushkey(repo, namespace, key, oldVal, newVal, true);
    }

    private static boolean applyPushkey(HgRepository repo, String namespace, String key, String oldVal, String newVal, boolean dryRun) throws IOException {
        if ("phases".equals(namespace)) {
            return pushPhase(repo, key, oldVal, newVal, dryRun);
        }
        if ("obsolete".equals(namespace)) {
            if (!io.github.search5.hg4j.obsolete.ObsStore.exchangeEnabled(repo)) {
                return false;
            }
            if (dryRun) {
                return key != null && key.startsWith("dump") && (oldVal == null || oldVal.isEmpty());
            }
            try {
                return io.github.search5.hg4j.obsolete.ObsStore.pushMarker(repo, key, oldVal, newVal);
            } catch (io.github.search5.hg4j.errors.HgLockException e) {
                throw new IOException(e.getMessage(), e);
            }
        }
        if (!"bookmarks".equals(namespace)) {
            return false;
        }
        Map<String, String> bookmarks = readListKeys(repo, "bookmarks");
        String currentVal = bookmarks.getOrDefault(key, "");
        String old = oldVal != null ? oldVal : "";
        String updated = newVal != null ? newVal : "";
        // bookmarks.pushbookmark: a divergent name (contains '@') is refused, the change is accepted when the bookmark is at
        // either the old OR the new value (so repeating a push is not an error), and a new target must exist in the repository.
        if (key != null && key.contains("@")) {
            return false;
        }
        if (!currentVal.equals(old) && !currentVal.equals(updated)) {
            return false;
        }
        if (!updated.isEmpty()) {
            Revlog changelog = changelog(repo);
            if (updated.length() != 40 || changelog.getRevisionCount() == 0
                    || changelog.findRevision(NodeIdUtil.fromHex(updated)) == -1) {
                return false;
            }
        }
        if (dryRun) {
            return true;
        }
        if (updated.isEmpty()) {
            bookmarks.remove(key);
        } else {
            bookmarks.put(key, updated);
        }
        File bkFile = new File(repo.getHgDir(), "bookmarks");
        if (bookmarks.isEmpty()) {
            Files.deleteIfExists(bkFile.toPath());
        } else {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : bookmarks.entrySet()) {
                sb.append(e.getValue()).append(' ').append(e.getKey()).append('\n');
            }
            SafeFileIO.writeStringAtomic(bkFile, sb.toString());
        }
        return true;
    }

    /**
     * {@code phases.pushphase}: moves {@code key} (a changeset) from phase {@code old} to the lower phase {@code new} when it is
     * at {@code old}; if it already is at {@code new} the answer is still success (a race that ended the same way).
     */
    private static boolean pushPhase(HgRepository repo, String key, String oldVal, String newVal, boolean dryRun) throws IOException {
        int oldPhase;
        int newPhase;
        try {
            oldPhase = Math.abs(Integer.parseInt(oldVal.trim()));
            newPhase = Math.abs(Integer.parseInt(newVal.trim()));
        } catch (NumberFormatException e) {
            return false;
        }
        if (key == null || key.length() != 40) {
            return false;
        }
        Revlog changelog = changelog(repo);
        byte[] node = NodeIdUtil.fromHex(key);
        if (changelog.getRevisionCount() == 0 || changelog.findRevision(node) < 0) {
            return false;
        }
        int current = repo.getPhaseRoots().getPhase(new io.github.search5.hg4j.lib.NodeId(node), changelog).getValue();
        if (current == oldPhase && newPhase < oldPhase) {
            if (dryRun) {
                return true;
            }
            io.github.search5.hg4j.api.Hg.wrap(repo).phase().setRevision(key).setPhase(newPhase).call();
            return true;
        }
        return current == newPhase;
    }

    /**
     * Opens the changelog revlog of the repository.
     * @param repo the repository; must not be null
     * @return the changelog revlog
     * @throws IOException if the revlog cannot be opened
     */
    public static Revlog changelog(HgRepository repo) throws IOException {
        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        return repo.getRevlog(clIdx, clDat);
    }
}
