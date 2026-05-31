package io.github.search5.hg4j.api;

import io.github.search5.hg4j.core.HgRepository;
import java.io.IOException;

/**
 * Commands for branch management (viewing or switching branches).
 */
public class BranchCommand {
    private final HgRepository repository;
    private String branchName;

    public BranchCommand(HgRepository repository) {
        this.repository = repository;
    }

    public BranchCommand setBranchName(String branchName) {
        this.branchName = branchName;
        return this;
    }

    public String call() throws IOException {
        if (branchName != null) {
            if (branchName.isEmpty()) {
                throw new IllegalArgumentException("Branch name cannot be empty");
            }
            repository.setBranch(branchName);
            return branchName;
        }
        return repository.getBranch();
    }
}
