package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgRepository;

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

    private boolean subrepos;
    private final List<String> messages = new ArrayList<>();

    /**
     * Creates an addremove command for the given repository. Nothing is read or written until {@link #call()}.
     *
     * @param repository the repository to operate on; must not be {@code null}
     */
    public AddremoveCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * {@code hg addremove -S}: also add and remove in the hg subrepositories ({@code scmutil.addremove}). A subrepository always
     * recurses into its own subrepositories ({@code hgsubrepo.addremove} sets {@code subrepos} on its copy of the options).
     */
    public AddremoveCommand setSubrepos(boolean subrepos) {
        this.subrepos = subrepos;
        return this;
    }

    /** What the last call said besides the changed files ({@code skipping missing subrepository}). */
    public List<String> getMessages() {
        return messages;
    }

    /**
     * @return status-style lines, e.g. {@code "A path/to/new.txt"} / {@code "R path/to/gone.txt"}
     */
    public List<String> call() throws IOException, HgLockException {
        // Like real hg, the locks are taken before the first read of the repository state and released when the command ends.
        int lockTimeout = repository.resolvePushLockTimeoutMs();
        try (io.github.search5.hg4j.lib.HgLock wlock = repository.lockWorkingCopy(lockTimeout)) {
            List<String> affected = new ArrayList<>();
            messages.clear();
            if (subrepos) {
                // scmutil.addremove handles the subrepositories first, each under its own working copy lock
                for (SubrepoRecursion.Sub sub : SubrepoRecursion.hgSubrepos(repository)) {
                    if (sub.repository() == null) {
                        messages.add(SubrepoRecursion.missingMessage(sub.path()));
                        continue;
                    }
                    try (HgRepository subRepository = sub.repository()) {
                        AddremoveCommand nested = new AddremoveCommand(subRepository).setSubrepos(true);
                        for (String line : nested.call()) {
                            affected.add(line.substring(0, 2) + SubrepoRecursion.display(sub.path(), line.substring(2)));
                        }
                        messages.addAll(nested.messages);
                    }
                }
            }

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
}
