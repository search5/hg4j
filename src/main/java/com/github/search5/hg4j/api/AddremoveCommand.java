package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.errors.HgLockException;
import com.github.search5.hg4j.lib.HgRepository;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Porcelain command corresponding to {@code hg addremove} — adds all untracked files and marks
 * all missing tracked files as removed, in one pass.
 */
public class AddremoveCommand {
    private final HgRepository repository;

    public AddremoveCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * @return status-style lines, e.g. {@code "A path/to/new.txt"} / {@code "R path/to/gone.txt"}
     */
    public List<String> call() throws IOException, HgLockException {
        List<String> affected = new ArrayList<>();

        Dirstate dirstate = repository.getDirstate();
        List<String> untracked = new ArrayList<>();
        for (String relPath : repository.scanWorkingCopy()) {
            if (!dirstate.getEntries().containsKey(relPath)) {
                untracked.add(relPath);
            }
        }
        for (String path : untracked) {
            new AddCommand(repository).addFile(path).call();
            affected.add("A " + path);
        }

        // 위에서 add한 파일들이 반영된 최신 dirstate를 다시 읽어야 한다.
        dirstate = repository.getDirstate();
        Map<String, Dirstate.Entry> entriesSnapshot = new LinkedHashMap<>(dirstate.getEntries());
        for (Map.Entry<String, Dirstate.Entry> e : entriesSnapshot.entrySet()) {
            String path = e.getKey();
            char state = e.getValue().getState();
            if (state == 'r') {
                continue; // 이미 제거 표시됨
            }
            File diskFile = new File(repository.getDirectory(), path);
            if (!diskFile.exists()) {
                new RemoveCommand(repository).setFile(path).setForce(true).call();
                affected.add("R " + path);
            }
        }

        return affected;
    }
}
