package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;

import java.io.IOException;
import io.github.search5.hg4j.errors.HgValidationException;

/**
 * Porcelain command corresponding to {@code hg forget} — stops tracking a file without touching
 * it on disk (unlike {@link RemoveCommand}, which deletes the working copy file).
 */
public class ForgetCommand {
    private final HgRepository repository;
    private String file;

    public ForgetCommand(HgRepository repository) {
        this.repository = repository;
    }

    public ForgetCommand setFile(String file) {
        this.file = file;
        return this;
    }

    public void call() throws IOException, HgLockException {
        if (file == null || file.isEmpty()) {
            throw new IllegalStateException("File path must be specified.");
        }
        try (HgLock wlock = repository.lockWorkingCopy()) {
            Dirstate dirstate = repository.getDirstate();
            Dirstate.Entry entry = dirstate.getEntries().get(file);
            if (entry == null) {
                throw new HgValidationException("File is not tracked: " + file);
            }

            if (entry.getState() == 'a') {
                // 아직 커밋된 적 없는 파일 — 그냥 추적을 완전히 해제한다.
                dirstate.removeEntry(file);
            } else {
                // 이미 커밋된 파일 — 다음 커밋 시 제거로 기록하되 작업 사본은 그대로 둔다.
                dirstate.addEntry(file, new Dirstate.Entry('r', 0, 0, 0));
            }
            repository.writeDirstate(dirstate);
        }
    }
}
