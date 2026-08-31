package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class PathsAliasIntegrationTest {

    @Test
    public void testPullCommandResolvesAlias(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Write paths.default pointing to an invalid url (should throw exception but verify alias resolution)
        File hgrc = new File(repo.getHgDir(), "hgrc");
        Files.writeString(hgrc.toPath(), "[paths]\ndefault = http://invalid-default-alias-destination.com/repo\n");
        repo.getConfig().load(hgrc); // force reload config

        PullCommand pull = new PullCommand(repo).setSource("default");
        
        // If "default" alias is correctly resolved to "http://invalid-default-alias-destination.com/repo",
        // it will attempt to fetch from it and throw an IOException/UnknownHostException.
        // If it was not resolved, it would fail with IllegalStateException or malformed url.
        Exception ex = assertThrows(Exception.class, pull::call);
        assertTrue(ex.getMessage().contains("http://invalid-default-alias-destination.com/repo") || 
                   ex.toString().contains("invalid-default-alias-destination.com"));
    }

    @Test
    public void testPushCommandResolvesAlias(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Write paths.custom-dest pointing to an invalid url
        File hgrc = new File(repo.getHgDir(), "hgrc");
        Files.writeString(hgrc.toPath(), "[paths]\ncustom-dest = http://invalid-push-alias-destination.com/repo\n");
        repo.getConfig().load(hgrc);

        PushCommand push = new PushCommand(repo).setDestination("custom-dest");

        Exception ex = assertThrows(Exception.class, push::call);
        assertTrue(ex.getMessage().contains("http://invalid-push-alias-destination.com/repo") ||
                   ex.toString().contains("invalid-push-alias-destination.com"));
    }

    /**
     * 실제 hg 스펙(hg help urls): 소스/목적지를 안 주면 각각 paths.default,
     * paths.default-push(없으면 default)를 쓴다 — 가장 흔한 실사용 형태("그냥 hg pull",
     * "그냥 hg push")인데 2026-09-01 이전에는 여기서 무조건 예외를 던져서 지원이 안 됐다.
     */
    @Test
    public void testPullCommandWithNoSourceUsesPathsDefault(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File hgrc = new File(repo.getHgDir(), "hgrc");
        Files.writeString(hgrc.toPath(), "[paths]\ndefault = http://invalid-no-arg-pull.example.com/repo\n");
        repo.getConfig().load(hgrc);

        PullCommand pull = new PullCommand(repo); // setSource() 호출 없음

        Exception ex = assertThrows(Exception.class, pull::call);
        assertFalse(ex instanceof IllegalStateException,
                "paths.default가 있으면 '소스 미지정' 예외가 아니라 실제 접속 시도(후 실패)여야 함: " + ex);
        assertTrue(ex.toString().contains("invalid-no-arg-pull.example.com"), "실제 오류: " + ex);
    }

    @Test
    public void testPushCommandWithNoDestinationPrefersDefaultPushOverDefault(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File hgrc = new File(repo.getHgDir(), "hgrc");
        Files.writeString(hgrc.toPath(),
                "[paths]\ndefault = http://should-not-be-used.example.com/repo\n" +
                "default-push = http://invalid-no-arg-push.example.com/repo\n");
        repo.getConfig().load(hgrc);

        PushCommand push = new PushCommand(repo); // setDestination() 호출 없음

        Exception ex = assertThrows(Exception.class, push::call);
        assertTrue(ex.toString().contains("invalid-no-arg-push.example.com"),
                "default-push가 default보다 우선해야 함: " + ex);
    }
}
