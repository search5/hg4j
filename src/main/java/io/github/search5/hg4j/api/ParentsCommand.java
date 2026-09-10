package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Porcelain command corresponding to {@code hg parents} — the working directory's parent
 * revision(s) (one, or two during an unresolved merge).
 *
 * @apiNote Typically obtained via {@link Hg#parents()} on an open {@link Hg}
 *     instance rather than constructed directly.
 */
public class ParentsCommand {
    private final HgRepository repository;

    /**
     * Creates a parents command bound to the given repository.
     *
     * @param repository the repository whose working directory parents are reported
     */
    public ParentsCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Resolves the working directory's parent revision(s) from the dirstate.
     *
     * @return list of parent node ids (hex), excluding the null parent. Size 1 in the common case,
     *         2 while a merge is in progress, 0 only for a brand-new empty repository.
     * @throws IOException if the dirstate cannot be read
     */
    public List<String> call() throws IOException {
        List<String> parents = new ArrayList<>();
        byte[] p1 = repository.getDirstate().getParent1();
        byte[] p2 = repository.getDirstate().getParent2();
        if (!NodeIdUtil.isAllZero(p1)) {
            parents.add(NodeIdUtil.toHex(p1));
        }
        if (!NodeIdUtil.isAllZero(p2)) {
            parents.add(NodeIdUtil.toHex(p2));
        }
        return parents;
    }
}
