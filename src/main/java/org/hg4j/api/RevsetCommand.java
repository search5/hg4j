package org.hg4j.api;

import org.hg4j.core.HgRepository;
import org.hg4j.core.Revlog;
import org.hg4j.core.NodeIdUtil;
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

        int count = changelog.getRevisionCount();
        List<String> results = new ArrayList<>();

        if ("all()".equalsIgnoreCase(expression) || "all".equalsIgnoreCase(expression)) {
            for (int i = 0; i < count; i++) {
                results.add(NodeIdUtil.toHex(changelog.getIndexRecord(i).getNodeId()));
            }
        } else if (expression.startsWith("parents(") && expression.endsWith(")")) {
            String target = expression.substring(8, expression.length() - 1);
            byte[] node = NodeIdUtil.resolveRevision(changelog, target);
            if (node != null) {
                int rev = changelog.findRevision(node);
                if (rev != -1) {
                    Revlog.IndexRecord rec = changelog.getIndexRecord(rev);
                    int p1 = rec.getParent1();
                    int p2 = rec.getParent2();
                    if (p1 != -1) results.add(NodeIdUtil.toHex(changelog.getIndexRecord(p1).getNodeId()));
                    if (p2 != -1) results.add(NodeIdUtil.toHex(changelog.getIndexRecord(p2).getNodeId()));
                }
            }
        } else if ("tip".equalsIgnoreCase(expression)) {
            if (count > 0) {
                results.add(NodeIdUtil.toHex(changelog.getIndexRecord(count - 1).getNodeId()));
            }
        } else {
            // Default fallback: parse single revision representation
            byte[] node = NodeIdUtil.resolveRevision(changelog, expression);
            if (node != null) {
                results.add(NodeIdUtil.toHex(node));
            }
        }
        return results;
    }
}
