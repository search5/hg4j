package com.github.search5.hg4j.api;

import java.util.logging.Level;
import java.util.logging.Logger;
import com.github.search5.hg4j.bundle.ChangegroupParser;
import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.lib.HgLock;
import com.github.search5.hg4j.transport.HgRemoteConnection;
import com.github.search5.hg4j.transport.HgRemoteConnectionFactory;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.util.SafeFileIO;
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
import com.github.search5.hg4j.treewalk.HgTreeFilter;

/**
 * Porcelain command to pull changes from a remote repository.
 * Built with crash-durable transaction protection and full fncache/on-disk layout fidelity.
 */
public class PullCommand {
    private static final Logger LOGGER = Logger.getLogger(PullCommand.class.getName());

    private final HgRepository repository;
    private String sourceUrl;
    private ProgressMonitor monitor = NullProgressMonitor.INSTANCE;
    private HgTreeFilter treeFilter = HgTreeFilter.ALL;
    private CredentialsProvider credentialsProvider;

    public PullCommand(HgRepository repository) {
        this.repository = repository;
    }

    public PullCommand setCredentialsProvider(CredentialsProvider credentialsProvider) {
        this.credentialsProvider = credentialsProvider;
        return this;
    }

    public PullCommand setTreeFilter(HgTreeFilter treeFilter) {
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
        // 실제 hg 스펙(hg help urls): 소스를 안 주면 paths.default를 쓴다 — 가장 흔한
        // 실사용 형태("그냥 hg pull")인데 2026-09-01 이전에는 여기서 무조건 예외를
        // 던져서 지원이 안 됐다.
        String effectiveSource = sourceUrl;
        if (effectiveSource == null || effectiveSource.isEmpty()) {
            effectiveSource = repository.getConfig().getPath("default");
        }
        if (effectiveSource == null || effectiveSource.isEmpty()) {
            throw new IllegalStateException("Remote source URL must be specified.");
        }

        String resolvedUrl = effectiveSource;
        if (!effectiveSource.contains("://")) {
            String configPath = repository.getConfig().getPath(effectiveSource);
            if (configPath != null) {
                resolvedUrl = configPath;
            }
        }

        // 1. Delegate core metadata network fetching and database store sync to FetchCommand
        FetchCommand fetchCmd = new FetchCommand(repository);
        fetchCmd.setTreeFilter(this.treeFilter);
        fetchCmd.setProgressMonitor(this.monitor);
        fetchCmd.setSource(resolvedUrl);
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

        // 3. bookmark 동기화는 위 1단계의 FetchCommand.call() 안에서
        // BookmarkCommand.mergeFromRemote()로 이미 처리된다(ancestor 기반 fast-forward/
        // 진짜 divergence 구분 포함). 여기서 다시 하면 이미 병합이 끝난 상태를 대상으로
        // 안전하지 않은 하드코딩된 "@default" 분기 로직이 중복 실행되는 문제가 있어
        // 제거함(2026-09-01).

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
