package com.github.search5.hg4j.api;

import java.util.logging.Level;
import java.util.logging.Logger;
import com.github.search5.hg4j.core.ChangegroupParser;
import com.github.search5.hg4j.core.Dirstate;
import com.github.search5.hg4j.core.HgLock;
import com.github.search5.hg4j.transport.HgRemoteConnection;
import com.github.search5.hg4j.transport.HgRemoteConnectionFactory;
import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.core.NodeIdUtil;
import com.github.search5.hg4j.core.Revlog;
import com.github.search5.hg4j.core.SafeFileIO;
import com.github.search5.hg4j.errors.HgLockException;
import com.github.search5.hg4j.lib.ProgressMonitor;
import com.github.search5.hg4j.lib.NullProgressMonitor;
import com.github.search5.hg4j.transport.CredentialsProvider;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Porcelain command to pull changes from a remote repository.
 * Built with crash-durable transaction protection and full fncache/on-disk layout fidelity.
 */
public class PullCommand {
    private static final Logger LOGGER = Logger.getLogger(PullCommand.class.getName());

    private final HgRepository repository;
    private String sourceUrl;
    private ProgressMonitor monitor = NullProgressMonitor.INSTANCE;
    private com.github.search5.hg4j.core.HgTreeFilter treeFilter = com.github.search5.hg4j.core.HgTreeFilter.ALL;
    private CredentialsProvider credentialsProvider;

    public PullCommand(HgRepository repository) {
        this.repository = repository;
    }

    public PullCommand setCredentialsProvider(CredentialsProvider credentialsProvider) {
        this.credentialsProvider = credentialsProvider;
        return this;
    }

    public PullCommand setTreeFilter(com.github.search5.hg4j.core.HgTreeFilter treeFilter) {
        if (treeFilter != null) {
            this.treeFilter = treeFilter;
        }
        return this;
    }

    public PullCommand setProgressMonitor(ProgressMonitor monitor) {
        if (monitor != null) {
            this.monitor = monitor;
        }
        return this;
    }

    public PullCommand setSource(String sourceUrl) {
        this.sourceUrl = sourceUrl;
        return this;
    }

    public List<byte[]> call() throws IOException, HgLockException {
        if (sourceUrl == null || sourceUrl.isEmpty()) {
            throw new IllegalStateException("Remote source URL must be specified.");
        }

        // 1. Delegate core metadata network fetching and database store sync to FetchCommand
        FetchCommand fetchCmd = new FetchCommand(repository);
        fetchCmd.setTreeFilter(this.treeFilter);
        fetchCmd.setProgressMonitor(this.monitor);
        fetchCmd.setSource(this.sourceUrl);
        if (this.credentialsProvider != null) {
            fetchCmd.setCredentialsProvider(this.credentialsProvider);
        }

        List<byte[]> results = fetchCmd.call();

        // 2. PullCommand exclusive: automatically advance working directory dirstate parent if it was empty
        if (results != null && !results.isEmpty()) {
            Dirstate dirstate = repository.getDirstate();
            if (NodeIdUtil.isAllZero(dirstate.getParent1())) {
                byte[] latestHead = results.get(results.size() - 1);
                dirstate.setParents(latestHead, new byte[20]);
                repository.writeDirstate(dirstate);
            }
        }

        return results;
    }

    public List<byte[]> applyBundle(ChangegroupParser.ChangegroupBundle bundle) throws IOException, HgLockException {
        FetchCommand fetchCmd = new FetchCommand(repository);
        fetchCmd.setTreeFilter(this.treeFilter);
        fetchCmd.setProgressMonitor(this.monitor);
        if (this.credentialsProvider != null) {
            fetchCmd.setCredentialsProvider(this.credentialsProvider);
        }
        
        List<byte[]> results = fetchCmd.applyBundle(bundle);

        if (results != null && !results.isEmpty()) {
            Dirstate dirstate = repository.getDirstate();
            if (NodeIdUtil.isAllZero(dirstate.getParent1())) {
                byte[] latestHead = results.get(results.size() - 1);
                dirstate.setParents(latestHead, new byte[20]);
                repository.writeDirstate(dirstate);
            }
        }
        return results;
    }
}
