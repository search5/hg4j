package io.github.search5.hg4j.transport.wireprotov1;

import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.TagsCommand;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.transport.HgLocalClient;
import io.github.search5.hg4j.transport.wireprotov2.Wire2Commands;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Resolves the symbol of the wire command {@code lookup} the way real hg does ({@code scmutil.revsymbol} /
 * {@code localrepo.__getitem__}), in the same order:
 * <ol>
 *   <li>{@code .} (working directory parent), {@code tip}, {@code null}</li>
 *   <li>a decimal revision number (negative counts from the tip) that is spelled canonically</li>
 *   <li>a full 40-digit node that exists</li>
 *   <li>a NAME: bookmark, then tag, then branch (a branch names its tip: the highest open head, else the highest
 *       closed head) - {@code repo.names.singlenode}, namespaces by priority bookmarks, tags, branches</li>
 *   <li>an unambiguous hexadecimal node prefix</li>
 * </ol>
 * Before 2026-11-03 only the number, {@code tip} and the hex prefix were understood, so a real client's
 * {@code hg clone -r <bookmark|tag|branch>} aborted with "unknown revision".
 */
final class RevisionLookup {

    /** The node found, or {@code null} with the {@code message} real hg would give. */
    record Outcome(byte[] node, String message) {
        static Outcome found(byte[] node) {
            return new Outcome(node, null);
        }

        static Outcome failed(String message) {
            return new Outcome(null, message);
        }
    }

    private RevisionLookup() {
    }

    static Outcome resolve(HgRepository repo, String key) throws IOException {
        Revlog changelog = Wire2Commands.changelog(repo);
        int count = changelog.getRevisionCount();
        byte[] nullNode = new byte[20];

        // 1. special names
        if (key.equals("null")) {
            return Outcome.found(nullNode);
        }
        if (key.equals("tip")) {
            return Outcome.found(count == 0 ? nullNode : nodeOf(changelog, count - 1));
        }
        if (key.equals(".")) {
            NodeId parent = repo.getDirstate().getParent1Node();
            return Outcome.found(parent == null ? nullNode : parent.getBytes());
        }

        // 2. canonical decimal revision number
        Integer number = canonicalInt(key);
        if (number != null) {
            int rev = number < 0 ? number + count : number;
            if (rev >= 0 && rev < count) {
                return Outcome.found(nodeOf(changelog, rev));
            }
            if (count == 0 && number == 0) {
                return Outcome.found(nullNode); // verified against hg 7.2: in an empty repository revision 0 is the null revision
            }
        }

        // 3. a complete node
        if (key.length() == 40 && isHex(key)) {
            byte[] node = NodeIdUtil.fromHex(key.toLowerCase());
            if (changelog.findRevision(node) != -1) {
                return Outcome.found(node);
            }
        }

        // 4. names, by namespace priority: bookmarks, tags, branches
        byte[] named = byName(repo, changelog, key);
        if (named != null) {
            return Outcome.found(named);
        }

        // 5. an unambiguous node prefix
        if (key.length() <= 40 && isHex(key)) { // the empty string is a prefix of everything, hence "ambiguous"
            List<byte[]> matches;
            if (key.isEmpty()) { // every node starts with ""
                matches = new java.util.ArrayList<>();
                for (int r = 0; r < count; r++) {
                    matches.add(nodeOf(changelog, r));
                }
            } else {
                matches = changelog.getIndex().findByHexPrefix(key.toLowerCase());
            }
            // A prefix made only of 'f' (the empty one included) is also a prefix of the working directory's
            // pseudo node ffff...ff, so one real match makes it ambiguous. Verified against hg 7.2.4: with exactly
            // one real node starting with 'f' the prefix "f" is ambiguous, "fa" resolves; with none, "f" is unknown.
            boolean wdirPrefix = key.chars().allMatch(c -> c == 'f' || c == 'F');
            int total = matches == null ? 0 : matches.size() + (wdirPrefix && matches.size() >= 1 ? 1 : 0);
            if (matches != null && total == 1) {
                return Outcome.found(matches.get(0));
            }
            if (matches != null && total > 1) {
                // the wording is real hg's str() of AmbiguousPrefixLookupError: "<index>@<prefix>: <message>"
                return Outcome.failed("00changelog@" + key + ": ambiguous identifier");
            }
        }
        return Outcome.failed("unknown revision '" + key + "'");
    }

    private static byte[] byName(HgRepository repo, Revlog changelog, String name) throws IOException {
        String bookmark = Wire2Commands.readListKeys(repo, "bookmarks").get(name);
        if (bookmark != null) {
            byte[] node = NodeIdUtil.fromHex(bookmark);
            if (changelog.findRevision(node) != -1) {
                return node;
            }
        }
        for (TagsCommand.Tag tag : new TagsCommand(repo).call()) {
            if (tag.getName().equals(name) && !name.equals("tip")) {
                return tag.getNode();
            }
        }
        List<String> heads = new HgLocalClient(repo).getBranchHeads().get(name);
        if (heads == null || heads.isEmpty()) {
            return null;
        }
        // branchtip: the tipmost OPEN head; only if every head is closed, the tipmost closed one
        int bestOpen = -1;
        int bestAny = -1;
        for (String hex : heads) {
            int rev = changelog.findRevision(NodeIdUtil.fromHex(hex));
            if (rev < 0) {
                continue;
            }
            bestAny = Math.max(bestAny, rev);
            if (!CommitCommand.isRevisionClosingBranch(changelog, rev)) {
                bestOpen = Math.max(bestOpen, rev);
            }
        }
        int chosen = bestOpen >= 0 ? bestOpen : bestAny;
        return chosen >= 0 ? nodeOf(changelog, chosen) : null;
    }

    private static byte[] nodeOf(Revlog changelog, int rev) {
        return java.util.Arrays.copyOf(changelog.getIndexRecord(rev).getNodeId(), 20);
    }

    /** {@code int(symbol)} accepted only if {@code str(int) == symbol} (so "01", "+1", " 1" are NOT numbers). */
    private static Integer canonicalInt(String s) {
        try {
            int v = Integer.parseInt(s);
            return Integer.toString(v).equals(s) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
