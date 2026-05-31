package com.github.search5.hg4j.api;

import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.core.Revlog;
import com.github.search5.hg4j.core.NodeIdUtil;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Revset command for querying repository revision DAGs
 * using functional expressions (e.g., 'all()', 'parents(tip)', etc.).
 */
public class RevsetCommand {
    private final HgRepository repository;
    private String expression;

    public RevsetCommand(HgRepository repository) {
        this.repository = repository;
    }

    public RevsetCommand setExpression(String expression) {
        this.expression = expression;
        return this;
    }

    /**
     * Executes the revset query expression.
     * Evaluates expressions against repository changelog topology.
     *
     * @return List of matching revision NodeIDs in hex
     * @throws IOException if history traversal fails
     */
    public List<String> call() throws IOException {
        if (expression == null || expression.isEmpty()) {
            throw new IllegalArgumentException("Expression must be specified for revset query");
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        // 1. Reuse high-performance evaluation engine
        com.github.search5.hg4j.core.HgRevsetEngine engine = new com.github.search5.hg4j.core.HgRevsetEngine(repository);

        // Preprocess symbolic revision terms inside queries (e.g. parents(tip) -> parents(<hex>))
        String resolvedExpr = expression;
        if (expression.contains("(") && expression.endsWith(")")) {
            int openIdx = expression.indexOf('(');
            int closeIdx = expression.lastIndexOf(')');
            if (openIdx != -1 && closeIdx != -1 && openIdx < closeIdx) {
                String funcName = expression.substring(0, openIdx).trim();
                String target = expression.substring(openIdx + 1, closeIdx).trim();
                if ("parents".equalsIgnoreCase(funcName)) {
                    byte[] resolvedNode = NodeIdUtil.resolveRevision(changelog, target);
                    if (resolvedNode != null) {
                        resolvedExpr = "parents(" + NodeIdUtil.toHex(resolvedNode) + ")";
                    }
                }
            }
        }

        // 2. Query engine with fallbacks
        List<Integer> revIndexes;
        try {
            if ("all()".equalsIgnoreCase(expression) || "all".equalsIgnoreCase(expression)) {
                revIndexes = new ArrayList<>();
                for (int i = 0; i < changelog.getRevisionCount(); i++) {
                    revIndexes.add(i);
                }
            } else if ("tip".equalsIgnoreCase(expression)) {
                revIndexes = new ArrayList<>();
                int count = changelog.getRevisionCount();
                if (count > 0) {
                    revIndexes.add(count - 1);
                }
            } else {
                revIndexes = engine.query(resolvedExpr);
            }
        } catch (Exception e) {
            // Evaluator fallback: parse as single commit nodeId / revision
            revIndexes = new ArrayList<>();
            byte[] node = NodeIdUtil.resolveRevision(changelog, expression);
            if (node != null) {
                int rev = changelog.findRevision(node);
                if (rev != -1) {
                    revIndexes.add(rev);
                }
            }
        }

        // 3. Convert revision index offsets to hex strings
        List<String> results = new ArrayList<>();
        for (int rev : revIndexes) {
            results.add(NodeIdUtil.toHex(changelog.getIndexRecord(rev).getNodeId()));
        }
        return results;
    }
}
