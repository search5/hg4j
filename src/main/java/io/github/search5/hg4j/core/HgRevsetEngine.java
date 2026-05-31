package io.github.search5.hg4j.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * High-performance query evaluator for Mercurial revision sets (Revsets).
 * Evaluates composite expressions like "draft()", "author(tester)", "parents(rev)" and AND/OR combinations.
 */
public final class HgRevsetEngine {
    private final HgRepository repository;

    public HgRevsetEngine(HgRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("Repository cannot be null");
        }
        this.repository = repository;
    }

    /**
     * Evaluates a revset query expression against the repository's changelog.
     *
     * @param query Revset query string (e.g. "draft() and author(tester)")
     * @return list of matching revision indexes
     * @throws IOException if repository reading or evaluation fails
     */
    public List<Integer> query(String query) throws IOException {
        if (query == null || query.trim().isEmpty()) {
            return List.of();
        }

        File clIdx = new java.io.File(repository.getStoreDir(), "00changelog.i");
        File clDat = new java.io.File(repository.getStoreDir(), "00changelog.d");
        if (!clIdx.exists()) {
            return List.of();
        }

        Revlog changelog = repository.getRevlog(clIdx, clDat);
        int totalRevs = changelog.getRevisionCount();

        return evaluateExpression(query.trim(), changelog, totalRevs);
    }

    private List<Integer> evaluateExpression(String query, Revlog changelog, int totalRevs) throws IOException {
        String trimmed = query.trim();
        
        // 1. Parenthesis and Quote-aware logical OR parsing
        int orIdx = findLogicalKeyword(trimmed, "or");
        if (orIdx != -1) {
            String left = trimmed.substring(0, orIdx).trim();
            String right = trimmed.substring(orIdx + 2).trim();
            
            List<Integer> leftRes = evaluateExpression(left, changelog, totalRevs);
            List<Integer> rightRes = evaluateExpression(right, changelog, totalRevs);
            
            Set<Integer> merged = new java.util.TreeSet<>(leftRes);
            merged.addAll(rightRes);
            return new ArrayList<>(merged);
        }

        // 2. Parenthesis and Quote-aware logical AND parsing
        int andIdx = findLogicalKeyword(trimmed, "and");
        if (andIdx != -1) {
            String left = trimmed.substring(0, andIdx).trim();
            String right = trimmed.substring(andIdx + 3).trim();
            
            List<Integer> leftRes = evaluateExpression(left, changelog, totalRevs);
            List<Integer> rightRes = evaluateExpression(right, changelog, totalRevs);
            
            Set<Integer> intersect = new java.util.TreeSet<>(leftRes);
            intersect.retainAll(rightRes);
            return new ArrayList<>(intersect);
        }

        // 3. Parenthesis and Quote-aware logical NOT parsing
        if (trimmed.toLowerCase().startsWith("not ") || trimmed.startsWith("!")) {
            String subExpr = trimmed.toLowerCase().startsWith("not ") ? trimmed.substring(4).trim() : trimmed.substring(1).trim();
            List<Integer> subRes = evaluateExpression(subExpr, changelog, totalRevs);
            List<Integer> allRes = new ArrayList<>();
            for (int i = 0; i < totalRevs; i++) {
                if (!subRes.contains(i)) {
                    allRes.add(i);
                }
            }
            return allRes;
        }

        // Single function evaluation
        String lower = trimmed.toLowerCase();
        if (lower.equals("all()") || lower.equals("all")) {
            List<Integer> res = new ArrayList<>();
            for (int i = 0; i < totalRevs; i++) {
                res.add(i);
            }
            return res;
        } else if (lower.equals("draft()")) {
            return evaluateDraft(changelog, totalRevs);
        } else if (lower.equals("heads()")) {
            return evaluateHeads(changelog, totalRevs);
        } else if (lower.equals("merge()")) {
            return evaluateMerge(changelog, totalRevs);
        } else if (lower.startsWith("author(") && lower.endsWith(")")) {
            String authorPattern = trimmed.substring("author(".length(), trimmed.length() - 1).trim();
            if (authorPattern.startsWith("\"") && authorPattern.endsWith("\"")) {
                authorPattern = authorPattern.substring(1, authorPattern.length() - 1);
            }
            return evaluateAuthor(authorPattern, changelog, totalRevs);
        } else if (lower.startsWith("user(") && lower.endsWith(")")) {
            String userPattern = trimmed.substring("user(".length(), trimmed.length() - 1).trim();
            if (userPattern.startsWith("\"") && userPattern.endsWith("\"")) {
                userPattern = userPattern.substring(1, userPattern.length() - 1);
            }
            return evaluateUser(userPattern, changelog, totalRevs);
        } else if (lower.startsWith("keyword(") && lower.endsWith(")")) {
            String kw = trimmed.substring("keyword(".length(), trimmed.length() - 1).trim();
            if (kw.startsWith("\"") && kw.endsWith("\"")) {
                kw = kw.substring(1, kw.length() - 1);
            }
            return evaluateKeyword(kw, changelog, totalRevs);
        } else if (lower.startsWith("branch(") && lower.endsWith(")")) {
            String bName = trimmed.substring("branch(".length(), trimmed.length() - 1).trim();
            if (bName.startsWith("\"") && bName.endsWith("\"")) {
                bName = bName.substring(1, bName.length() - 1);
            }
            return evaluateBranch(bName, changelog, totalRevs);
        } else if (lower.startsWith("file(") && lower.endsWith(")")) {
            String fPath = trimmed.substring("file(".length(), trimmed.length() - 1).trim();
            if (fPath.startsWith("\"") && fPath.endsWith("\"")) {
                fPath = fPath.substring(1, fPath.length() - 1);
            }
            return evaluateFile(fPath, changelog, totalRevs);
        } else if (lower.startsWith("date(") && lower.endsWith(")")) {
            String dPat = trimmed.substring("date(".length(), trimmed.length() - 1).trim();
            if (dPat.startsWith("\"") && dPat.endsWith("\"")) {
                dPat = dPat.substring(1, dPat.length() - 1);
            }
            return evaluateDate(dPat, changelog, totalRevs);
        } else if (lower.startsWith("parents(") && lower.endsWith(")")) {
            String targetRevStr = trimmed.substring("parents(".length(), trimmed.length() - 1).trim();
            int targetRev = resolveRevisionToInt(targetRevStr, changelog);
            return evaluateParents(targetRev, changelog);
        } else if (lower.startsWith("ancestors(") && lower.endsWith(")")) {
            String targetRevStr = trimmed.substring("ancestors(".length(), trimmed.length() - 1).trim();
            int targetRev = resolveRevisionToInt(targetRevStr, changelog);
            return evaluateAncestors(targetRev, changelog);
        } else if (lower.startsWith("descendants(") && lower.endsWith(")")) {
            String targetRevStr = trimmed.substring("descendants(".length(), trimmed.length() - 1).trim();
            int targetRev = resolveRevisionToInt(targetRevStr, changelog);
            return evaluateDescendants(targetRev, changelog, totalRevs);
        } else if (lower.startsWith("tag(") && lower.endsWith(")")) {
            String tagName = trimmed.substring("tag(".length(), trimmed.length() - 1).trim();
            if (tagName.startsWith("\"") && tagName.endsWith("\"")) {
                tagName = tagName.substring(1, tagName.length() - 1);
            }
            return evaluateTag(tagName, changelog);
        } else if (lower.startsWith("bookmark(") && lower.endsWith(")")) {
            String bkmkName = trimmed.substring("bookmark(".length(), trimmed.length() - 1).trim();
            if (bkmkName.startsWith("\"") && bkmkName.endsWith("\"")) {
                bkmkName = bkmkName.substring(1, bkmkName.length() - 1);
            }
            return evaluateBookmark(bkmkName, changelog);
        } else if (lower.equals("public()")) {
            return evaluatePublic(changelog, totalRevs);
        } else if (lower.equals("secret()")) {
            return evaluateSecret(changelog, totalRevs);
        } else if (lower.startsWith("limit(") && lower.endsWith(")")) {
            String inner = trimmed.substring("limit(".length(), trimmed.length() - 1).trim();
            int lastComma = findLogicalKeyword(inner, ",");
            if (lastComma != -1) {
                String sub = inner.substring(0, lastComma).trim();
                String nStr = inner.substring(lastComma + 1).trim();
                int n = Integer.parseInt(nStr);
                List<Integer> subRes = evaluateExpression(sub, changelog, totalRevs);
                return subRes.subList(0, Math.min(n, subRes.size()));
            }
            return new ArrayList<>();
        } else if (lower.startsWith("sort(") && lower.endsWith(")")) {
            String inner = trimmed.substring("sort(".length(), trimmed.length() - 1).trim();
            int lastComma = findLogicalKeyword(inner, ",");
            if (lastComma != -1) {
                String sub = inner.substring(0, lastComma).trim();
                String field = inner.substring(lastComma + 1).trim().replace("\"", "").replace("'", "");
                List<Integer> subRes = evaluateExpression(sub, changelog, totalRevs);
                return evaluateSort(subRes, field, changelog);
            }
            return new ArrayList<>();
        } else if (lower.startsWith("children(") && lower.endsWith(")")) {
            String inner = trimmed.substring("children(".length(), trimmed.length() - 1).trim();
            List<Integer> subRes = evaluateExpression(inner, changelog, totalRevs);
            return evaluateChildren(subRes, changelog, totalRevs);
        } else {
            // Numeric or hex match fallback
            List<Integer> res = new ArrayList<>();
            try {
                int rev = Integer.parseInt(trimmed);
                if (rev >= 0 && rev < totalRevs) {
                    res.add(rev);
                }
            } catch (NumberFormatException e) {
                try {
                    int rev = changelog.findRevision(NodeIdUtil.fromHex(trimmed));
                    if (rev != -1) {
                        res.add(rev);
                    }
                } catch (Exception ignored) {}
            }
            return res;
        }
    }

    private static int findLogicalKeyword(String query, String keyword) {
        String trimmedKw = keyword.trim();
        int kwLen = trimmedKw.length();
        
        boolean inDoubleQuotes = false;
        boolean inSingleQuotes = false;
        int bracketDepth = 0;
        int len = query.length();
        
        for (int i = 0; i <= len - kwLen; i++) {
            char c = query.charAt(i);
            if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
                continue;
            } else if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                continue;
            } else if (c == '(' && !inDoubleQuotes && !inSingleQuotes) {
                bracketDepth++;
                continue;
            } else if (c == ')' && !inDoubleQuotes && !inSingleQuotes) {
                bracketDepth--;
                continue;
            }
            
            if (!inDoubleQuotes && !inSingleQuotes && bracketDepth == 0) {
                if (query.substring(i, i + kwLen).equalsIgnoreCase(trimmedKw)) {
                    boolean leftOk = (i == 0 || Character.isWhitespace(query.charAt(i - 1)) || query.charAt(i - 1) == ')');
                    boolean rightOk = (i + kwLen == len || Character.isWhitespace(query.charAt(i + kwLen)) || query.charAt(i + kwLen) == '(' || query.charAt(i + kwLen) == '!');
                    if (leftOk && rightOk) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private int resolveRevisionToInt(String revStr, Revlog changelog) {
        if ("tip".equalsIgnoreCase(revStr)) {
            return changelog.getRevisionCount() - 1;
        }
        try {
            return Integer.parseInt(revStr);
        } catch (NumberFormatException e) {
            try {
                return changelog.findRevision(NodeIdUtil.fromHex(revStr));
            } catch (Exception ex) {
                return -1;
            }
        }
    }

    private List<Integer> evaluateDraft(Revlog changelog, int totalRevs) throws IOException {
        List<Integer> res = new ArrayList<>();
        PhaseRoots phaseRoots = repository.getPhaseRoots();
        for (int r = 0; r < totalRevs; r++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(r);
            io.github.search5.hg4j.lib.NodeId node = new io.github.search5.hg4j.lib.NodeId(rec.getNodeId());
            if (phaseRoots.isDraft(node, changelog)) {
                res.add(r);
            }
        }
        return res;
    }

    private List<Integer> evaluateHeads(Revlog changelog, int totalRevs) {
        boolean[] isParent = new boolean[totalRevs];
        for (int i = 0; i < totalRevs; i++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            if (rec.getParent1() >= 0 && rec.getParent1() < totalRevs) {
                isParent[rec.getParent1()] = true;
            }
            if (rec.getParent2() >= 0 && rec.getParent2() < totalRevs) {
                isParent[rec.getParent2()] = true;
            }
        }
        List<Integer> heads = new ArrayList<>();
        for (int i = 0; i < totalRevs; i++) {
            if (!isParent[i]) {
                heads.add(i);
            }
        }
        return heads;
    }

    private List<Integer> evaluateMerge(Revlog changelog, int totalRevs) {
        List<Integer> res = new ArrayList<>();
        for (int i = 0; i < totalRevs; i++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            if (rec.getParent2() != -1) {
                res.add(i);
            }
        }
        return res;
    }

    private List<Integer> evaluateAuthor(String authorPattern, Revlog changelog, int totalRevs) throws IOException {
        List<Integer> res = new ArrayList<>();
        for (int r = 0; r < totalRevs; r++) {
            byte[] content = changelog.getRevisionContent(r);
            String text = new String(content, StandardCharsets.UTF_8);
            String[] lines = text.split("\n");
            if (lines.length > 1) {
                String authorLine = lines[1].trim();
                if (authorLine.toLowerCase().contains(authorPattern.toLowerCase())) {
                    res.add(r);
                }
            }
        }
        return res;
    }

    private List<Integer> evaluateUser(String userPattern, Revlog changelog, int totalRevs) throws IOException {
        return evaluateAuthor(userPattern, changelog, totalRevs);
    }

    private List<Integer> evaluateKeyword(String kw, Revlog changelog, int totalRevs) throws IOException {
        List<Integer> res = new ArrayList<>();
        for (int r = 0; r < totalRevs; r++) {
            byte[] content = changelog.getRevisionContent(r);
            String text = new String(content, StandardCharsets.UTF_8);
            if (text.toLowerCase().contains(kw.toLowerCase())) {
                res.add(r);
            }
        }
        return res;
    }

    private List<Integer> evaluateBranch(String branchName, Revlog changelog, int totalRevs) throws IOException {
        List<Integer> res = new ArrayList<>();
        for (int r = 0; r < totalRevs; r++) {
            byte[] content = changelog.getRawRevisionContent(r);
            String text = new String(content, StandardCharsets.UTF_8);
            String[] lines = text.split("\n");
            if (lines.length > 2) {
                String timeLine = lines[2].trim();
                if (timeLine.contains("branch:" + branchName)) {
                    res.add(r);
                } else if ("default".equalsIgnoreCase(branchName) && !timeLine.contains("branch:")) {
                    res.add(r);
                }
            }
        }
        return res;
    }

    private List<Integer> evaluateFile(String filePath, Revlog changelog, int totalRevs) throws IOException {
        List<Integer> res = new ArrayList<>();
        for (int r = 0; r < totalRevs; r++) {
            byte[] content = changelog.getRawRevisionContent(r);
            String text = new String(content, StandardCharsets.UTF_8);
            String[] lines = text.split("\n");
            boolean found = false;
            for (int i = 3; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    break;
                }
                if (line.equals(filePath) || line.replace('\\', '/').equals(filePath.replace('\\', '/'))) {
                    found = true;
                    break;
                }
            }
            if (found) {
                res.add(r);
            }
        }
        return res;
    }

    private List<Integer> evaluateDate(String datePattern, Revlog changelog, int totalRevs) throws IOException {
        List<Integer> res = new ArrayList<>();
        for (int r = 0; r < totalRevs; r++) {
            byte[] content = changelog.getRawRevisionContent(r);
            String text = new String(content, StandardCharsets.UTF_8);
            String[] lines = text.split("\n");
            if (lines.length > 2) {
                String timeLine = lines[2].trim();
                if (timeLine.contains(datePattern)) {
                    res.add(r);
                }
            }
        }
        return res;
    }

    private List<Integer> evaluateParents(int targetRev, Revlog changelog) {
        List<Integer> res = new ArrayList<>();
        if (targetRev >= 0 && targetRev < changelog.getRevisionCount()) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(targetRev);
            if (rec.getParent1() != -1) {
                res.add(rec.getParent1());
            }
            if (rec.getParent2() != -1) {
                res.add(rec.getParent2());
            }
        }
        return res;
    }

    private List<Integer> evaluateAncestors(int targetRev, Revlog changelog) {
        Set<Integer> visited = new java.util.TreeSet<>();
        if (targetRev < 0 || targetRev >= changelog.getRevisionCount()) {
            return new ArrayList<>();
        }
        
        java.util.Queue<Integer> queue = new java.util.LinkedList<>();
        queue.add(targetRev);
        while (!queue.isEmpty()) {
            int current = queue.poll();
            if (visited.add(current)) {
                Revlog.IndexRecord rec = changelog.getIndexRecord(current);
                if (rec.getParent1() >= 0) queue.add(rec.getParent1());
                if (rec.getParent2() >= 0) queue.add(rec.getParent2());
            }
        }
        return new ArrayList<>(visited);
    }

    private List<Integer> evaluateDescendants(int targetRev, Revlog changelog, int totalRevs) {
        Set<Integer> descendants = new java.util.TreeSet<>();
        if (targetRev < 0 || targetRev >= totalRevs) {
            return new ArrayList<>();
        }
        
        descendants.add(targetRev);
        for (int i = targetRev + 1; i < totalRevs; i++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            if (descendants.contains(rec.getParent1()) || descendants.contains(rec.getParent2())) {
                descendants.add(i);
            }
        }
        return new ArrayList<>(descendants);
    }

    private List<Integer> evaluateTag(String tagName, Revlog changelog) throws IOException {
        List<Integer> res = new ArrayList<>();
        File tagFile = new File(repository.getDirectory(), ".hgtags");
        if (tagFile.exists()) {
            List<String> lines = java.nio.file.Files.readAllLines(tagFile.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int spaceIdx = line.indexOf(' ');
                if (spaceIdx != -1) {
                    String hexNode = line.substring(0, spaceIdx).trim();
                    String name = line.substring(spaceIdx + 1).trim();
                    if (name.equalsIgnoreCase(tagName)) {
                        int rev = changelog.findRevision(NodeIdUtil.fromHex(hexNode));
                        if (rev != -1) {
                            res.add(rev);
                        }
                    }
                }
            }
        }
        return res;
    }

    private List<Integer> evaluateBookmark(String bookmarkName, Revlog changelog) throws IOException {
        List<Integer> res = new ArrayList<>();
        File bkFile = new File(repository.getHgDir(), "bookmarks");
        if (bkFile.exists()) {
            List<String> lines = java.nio.file.Files.readAllLines(bkFile.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int spaceIdx = line.indexOf(' ');
                if (spaceIdx != -1) {
                    String hexNode = line.substring(0, spaceIdx).trim();
                    String name = line.substring(spaceIdx + 1).trim();
                    if (name.equalsIgnoreCase(bookmarkName)) {
                        int rev = changelog.findRevision(NodeIdUtil.fromHex(hexNode));
                        if (rev != -1) {
                            res.add(rev);
                        }
                    }
                }
            }
        }
        return res;
    }

    private List<Integer> evaluatePublic(Revlog changelog, int totalRevs) throws IOException {
        List<Integer> res = new ArrayList<>();
        io.github.search5.hg4j.core.PhaseRoots phaseRoots = repository.getPhaseRoots();
        for (int i = 0; i < totalRevs; i++) {
            byte[] node = changelog.getIndexRecord(i).getNodeId();
            io.github.search5.hg4j.core.PhaseRoots.Phase phase = phaseRoots.getPhase(new io.github.search5.hg4j.lib.NodeId(node), changelog);
            if (phase == io.github.search5.hg4j.core.PhaseRoots.Phase.PUBLIC) {
                res.add(i);
            }
        }
        return res;
    }

    private List<Integer> evaluateSecret(Revlog changelog, int totalRevs) throws IOException {
        List<Integer> res = new ArrayList<>();
        io.github.search5.hg4j.core.PhaseRoots phaseRoots = repository.getPhaseRoots();
        for (int i = 0; i < totalRevs; i++) {
            byte[] node = changelog.getIndexRecord(i).getNodeId();
            io.github.search5.hg4j.core.PhaseRoots.Phase phase = phaseRoots.getPhase(new io.github.search5.hg4j.lib.NodeId(node), changelog);
            if (phase == io.github.search5.hg4j.core.PhaseRoots.Phase.SECRET) {
                res.add(i);
            }
        }
        return res;
    }

    private List<Integer> evaluateSort(List<Integer> revs, String field, Revlog changelog) throws IOException {
        List<Integer> sorted = new ArrayList<>(revs);
        if ("rev".equalsIgnoreCase(field)) {
            Collections.sort(sorted);
        } else if ("date".equalsIgnoreCase(field)) {
            sorted.sort((a, b) -> {
                long tA = getRevisionTimestamp(a, changelog);
                long tB = getRevisionTimestamp(b, changelog);
                return Long.compare(tA, tB);
            });
        } else if ("author".equalsIgnoreCase(field) || "user".equalsIgnoreCase(field)) {
            sorted.sort((a, b) -> {
                try {
                    Map<String, String> mA = changelog.getRevisionMetadata(a);
                    Map<String, String> mB = changelog.getRevisionMetadata(b);
                    String authorA = mA.getOrDefault("author", "");
                    String authorB = mB.getOrDefault("author", "");
                    return authorA.compareTo(authorB);
                } catch (Exception e) {
                    return 0;
                }
            });
        }
        return sorted;
    }

    private long getRevisionTimestamp(int rev, Revlog changelog) {
        try {
            byte[] content = changelog.getRawRevisionContent(rev);
            String text = new String(content, StandardCharsets.UTF_8);
            String[] lines = text.split("\n");
            if (lines.length > 2) {
                String timeLine = lines[2].trim();
                int space = timeLine.indexOf(' ');
                String tsStr = space != -1 ? timeLine.substring(0, space) : timeLine;
                return (long) Double.parseDouble(tsStr);
            }
        } catch (Exception ignored) {}
        return rev;
    }

    private List<Integer> evaluateChildren(List<Integer> parentRevs, Revlog changelog, int totalRevs) throws IOException {
        Set<Integer> children = new LinkedHashSet<>();
        Set<Integer> parentsSet = new HashSet<>(parentRevs);
        for (int i = 0; i < totalRevs; i++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            if (parentsSet.contains(rec.getParent1()) || parentsSet.contains(rec.getParent2())) {
                children.add(i);
            }
        }
        return new ArrayList<>(children);
    }
}
