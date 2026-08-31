package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Porcelain command corresponding to {@code hg parents} — the working directory's parent
 * revision(s) (one, or two during an unresolved merge).
 */
public class ParentsCommand {
    private final HgRepository repository;

    public ParentsCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * @return list of parent node ids (hex), excluding the null parent. Size 1 in the common case,
     *         2 while a merge is in progress, 0 only for a brand-new empty repository.
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
