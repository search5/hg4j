package org.hg4j.core;

import java.io.File;
import java.io.IOException;

/**
 * Interface representing a Mercurial repository.
 * Provides abstraction for core repository directory access, dirstate operations, branch state, and concurrency locks.
 */
public interface Repository {

    File getDirectory();

    File getHgDir();

    File getStoreDir();

    Dirstate getDirstate() throws IOException;

    void writeDirstate(Dirstate dirstate) throws IOException;

    String getBranch();

    void setBranch(String branch) throws IOException;

    HgLock lockWorkingCopy() throws HgLockException;

    HgLock lockStore() throws HgLockException;
}
