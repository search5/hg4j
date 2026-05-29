package org.hg4j.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
        // Handle logical "or"
        if (query.toLowerCase().contains(" or ")) {
            int idx = query.toLowerCase().indexOf(" or ");
            String left = query.substring(0, idx).trim();
            String right = query.substring(idx + 4).trim();
            
            List<Integer> leftRes = evaluateExpression(left, changelog, totalRevs);
            List<Integer> rightRes = evaluateExpression(right, changelog, totalRevs);
            
            Set<Integer> merged = new java.util.TreeSet<>(leftRes);
            merged.addAll(rightRes);
            return new ArrayList<>(merged);
        }

        // Handle logical "and"
        if (query.toLowerCase().contains(" and ")) {
            int idx = query.toLowerCase().indexOf(" and ");
            String left = query.substring(0, idx).trim();
            String right = query.substring(idx + 5).trim();
            
            List<Integer> leftRes = evaluateExpression(left, changelog, totalRevs);
            List<Integer> rightRes = evaluateExpression(right, changelog, totalRevs);
            
            Set<Integer> intersect = new java.util.TreeSet<>(leftRes);
            intersect.retainAll(rightRes);
            return new ArrayList<>(intersect);
        }

        // Single function evaluation: "draft()", "author(val)", "parents(rev)"
        String lower = query.toLowerCase();
        if (lower.equals("draft()")) {
            return evaluateDraft(changelog, totalRevs);
        } else if (lower.startsWith("author(") && lower.endsWith(")")) {
            String authorPattern = query.substring("author(".length(), query.length() - 1).trim();
            // strip quotes if present
            if (authorPattern.startsWith("\"") && authorPattern.endsWith("\"")) {
                authorPattern = authorPattern.substring(1, authorPattern.length() - 1);
            }
            return evaluateAuthor(authorPattern, changelog, totalRevs);
        } else if (lower.startsWith("parents(") && lower.endsWith(")")) {
            String targetRevStr = query.substring("parents(".length(), query.length() - 1).trim();
            int targetRev = -1;
            try {
                targetRev = Integer.parseInt(targetRevStr);
            } catch (NumberFormatException e) {
                // Node ID matching fallback
                byte[] nodeId = NodeIdUtil.fromHex(targetRevStr);
                targetRev = changelog.findRevision(nodeId);
            }
            return evaluateParents(targetRev, changelog);
        } else {
            // Numeric or hex match fallback
            List<Integer> res = new ArrayList<>();
            try {
                int rev = Integer.parseInt(query);
                if (rev >= 0 && rev < totalRevs) {
                    res.add(rev);
                }
            } catch (NumberFormatException e) {
                int rev = changelog.findRevision(NodeIdUtil.fromHex(query));
                if (rev != -1) {
                    res.add(rev);
                }
            }
            return res;
        }
    }

    private List<Integer> evaluateDraft(Revlog changelog, int totalRevs) throws IOException {
        List<Integer> res = new ArrayList<>();
        PhaseRoots phaseRoots = repository.getPhaseRoots();
        for (int r = 0; r < totalRevs; r++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(r);
            org.hg4j.lib.NodeId node = new org.hg4j.lib.NodeId(rec.getNodeId());
            if (phaseRoots.isDraft(node, changelog)) {
                res.add(r);
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
                String authorLine = lines[1].trim(); // Second line is author in standard changelog
                if (authorLine.toLowerCase().contains(authorPattern.toLowerCase())) {
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
}
