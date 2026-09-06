package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import io.github.search5.hg4j.errors.HgValidationException;
import java.lang.reflect.Field;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.ArrayList;

public class ProcessHookTest {

    @Test
    public void testProcessHookSuccess() throws Exception {
        // Execute 'true' command (normal exit)
        ProcessHook hook = new ProcessHook("true");
        Map<String, Object> context = new HashMap<>();
        context.put("author", "Tester");
        
        boolean result = hook.run(context);
        assertTrue(result, "true 명령어 훅은 성공을 반환해야 합니다.");
    }

    @Test
    public void testProcessHookFailure() throws Exception {
        // Execute 'false' command (abnormal exit)
        ProcessHook hook = new ProcessHook("false");
        Map<String, Object> context = new HashMap<>();
        
        boolean result = hook.run(context);
        assertFalse(result, "false 명령어 훅은 실패를 반환해야 합니다.");
    }

    @Test
    public void testProcessHookEnvironmentMapping(@TempDir Path tempDir) throws Exception {
        File logFile = tempDir.resolve("hook_output.txt").toFile();
        File scriptFile = tempDir.resolve("test_hook.sh").toFile();

        // 1. Create a temporary shell script: reads environment variables HG_AUTHOR and HG_MESSAGE, and writes to a file
        String scriptContent = "#!/bin/sh\n" +
                "echo \"Author:$HG_AUTHOR\" > \"" + logFile.getAbsolutePath() + "\"\n" +
                "echo \"Message:$HG_MESSAGE\" >> \"" + logFile.getAbsolutePath() + "\"\n" +
                "exit 0\n";
        
        Files.writeString(scriptFile.toPath(), scriptContent);
        scriptFile.setExecutable(true);

        // 2. Create ProcessHook and pass SCM context data
        ProcessHook hook = new ProcessHook(Arrays.asList("sh", scriptFile.getAbsolutePath()));
        Map<String, Object> context = new HashMap<>();
        context.put("author", "Alice <alice@example.com>");
        context.put("message", "Commit verification test");

        boolean result = hook.run(context);
        assertTrue(result, "쉘 스크립트 훅이 정상 종료되어 성공을 반환해야 합니다.");

        // 3. Assert the content of the written file to check if environment variable mapping was successfully propagated
        assertTrue(logFile.exists(), "로그 파일이 생성되어야 합니다.");
        List<String> lines = Files.readAllLines(logFile.toPath());
        assertEquals(2, lines.size());
        assertEquals("Author:Alice <alice@example.com>", lines.get(0));
        assertEquals("Message:Commit verification test", lines.get(1));
    }

    @Test
    public void testCommitCommandPreCommitHookRejection(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repository = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "test");
        new AddCommand(repository).call();

        CommitCommand commitCmd = new CommitCommand(repository)
                .setAuthor("Alice")
                .setMessage("A message");

        // Register a ProcessHook that exits abnormally
        ProcessHook failingHook = new ProcessHook("false");
        commitCmd.registerPreCommitHook(failingHook);

        // Verify that an exception is thrown when PreCommitHook is rejected
        assertThrows(HgValidationException.class, () -> {
            commitCmd.call();
        }, "Pre-commit hook이 실패를 반환하면 커밋이 거부되어 예외가 터져야 합니다.");
    }

    @Test
    public void testProcessHookQuoteSplitting() throws Exception {
        // Pass a script path containing spaces wrapped in quotes
        ProcessHook hook = new ProcessHook("\"/path/to/my script.sh\" arg1 'arg2 with space'");
        
        Field cmdField = ProcessHook.class.getDeclaredField("command");
        cmdField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> commandList = (List<String>) cmdField.get(hook);
        
        assertEquals(3, commandList.size(), "명령어는 3개의 인자로 파싱되어야 합니다.");
        assertEquals("/path/to/my script.sh", commandList.get(0));
        assertEquals("arg1", commandList.get(1));
        assertEquals("arg2 with space", commandList.get(2));
    }

    @Test
    public void testProcessHookQuoteSplittingWithMismatchedQuoteInsideQuotes() throws Exception {
        // A different quote character appearing inside an already-open quote must be kept literally
        ProcessHook hook = new ProcessHook("'abc\"def' ghi");

        Field cmdField = ProcessHook.class.getDeclaredField("command");
        cmdField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> commandList = (List<String>) cmdField.get(hook);

        assertEquals(2, commandList.size(), "명령어는 2개의 인자로 파싱되어야 합니다.");
        assertEquals("abc\"def", commandList.get(0), "따옴표 내부의 다른 종류 따옴표 문자는 그대로 유지되어야 합니다.");
        assertEquals("ghi", commandList.get(1));
    }

    @Test
    public void testProcessOutputIsLoggedLineByLine() throws Exception {
        ProcessHook hook = new ProcessHook(Arrays.asList("sh", "-c", "echo hello-from-hook"));
        Map<String, Object> context = new HashMap<>();

        Logger logger = Logger.getLogger(ProcessHook.class.getName());
        List<String> capturedMessages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                capturedMessages.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        boolean previousUseParentHandlers = logger.getUseParentHandlers();
        Level previousLevel = logger.getLevel();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        try {
            boolean result = hook.run(context);
            assertTrue(result, "정상 종료된 훅은 성공을 반환해야 합니다.");
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(previousUseParentHandlers);
            logger.setLevel(previousLevel);
        }

        assertTrue(capturedMessages.stream().anyMatch(m -> m.contains("hello-from-hook")),
                "프로세스의 표준 출력은 로거를 통해 한 줄씩 기록되어야 합니다.");
    }

    @Test
    public void testEmptyCommandListSkipsExecution() throws Exception {
        ProcessHook hook = new ProcessHook(Collections.emptyList());
        Map<String, Object> context = new HashMap<>();
        context.put("author", "Tester");

        boolean result = hook.run(context);
        assertTrue(result, "빈 명령어 리스트는 프로세스를 실행하지 않고 성공을 반환해야 합니다.");
    }

    @Test
    public void testNullCommandStringSkipsExecution() throws Exception {
        ProcessHook hook = new ProcessHook((String) null);
        Map<String, Object> context = new HashMap<>();

        boolean result = hook.run(context);
        assertTrue(result, "null 명령어 문자열은 빈 명령어로 처리되어 성공을 반환해야 합니다.");
    }

    @Test
    public void testBlankCommandStringSkipsExecution() throws Exception {
        ProcessHook hook = new ProcessHook("   ");
        Map<String, Object> context = new HashMap<>();

        boolean result = hook.run(context);
        assertTrue(result, "공백만 있는 명령어 문자열은 빈 명령어로 처리되어 성공을 반환해야 합니다.");
    }

    @Test
    public void testExplicitWorkingDirectoryOverridesRepositoryContext(@TempDir Path tempDir) throws Exception {
        File explicitDir = tempDir.resolve("explicit").toFile();
        explicitDir.mkdirs();
        File logFile = tempDir.resolve("cwd_output.txt").toFile();
        File scriptFile = tempDir.resolve("pwd_hook.sh").toFile();

        String scriptContent = "#!/bin/sh\n" +
                "pwd > \"" + logFile.getAbsolutePath() + "\"\n" +
                "exit 0\n";
        Files.writeString(scriptFile.toPath(), scriptContent);
        scriptFile.setExecutable(true);

        ProcessHook hook = new ProcessHook(Arrays.asList("sh", scriptFile.getAbsolutePath()), explicitDir);
        Map<String, Object> context = new HashMap<>();

        boolean result = hook.run(context);
        assertTrue(result, "정상 종료된 훅은 성공을 반환해야 합니다.");

        List<String> lines = Files.readAllLines(logFile.toPath());
        assertEquals(1, lines.size());
        assertEquals(explicitDir.getCanonicalPath(), new File(lines.get(0)).getCanonicalPath(),
                "명시적으로 지정된 작업 디렉터리가 프로세스의 cwd로 사용되어야 합니다.");
    }

    @Test
    public void testRepositoryContextResolvesWorkingDirectory(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repository = Hg.init().setDirectory(repoDir).call();
        File logFile = tempDir.resolve("cwd_output.txt").toFile();
        File scriptFile = tempDir.resolve("pwd_hook.sh").toFile();

        String scriptContent = "#!/bin/sh\n" +
                "pwd > \"" + logFile.getAbsolutePath() + "\"\n" +
                "exit 0\n";
        Files.writeString(scriptFile.toPath(), scriptContent);
        scriptFile.setExecutable(true);

        ProcessHook hook = new ProcessHook(Arrays.asList("sh", scriptFile.getAbsolutePath()));
        Map<String, Object> context = new HashMap<>();
        context.put("repository", repository);

        boolean result = hook.run(context);
        assertTrue(result, "정상 종료된 훅은 성공을 반환해야 합니다.");

        List<String> lines = Files.readAllLines(logFile.toPath());
        assertEquals(1, lines.size());
        assertEquals(repository.getDirectory().getCanonicalPath(), new File(lines.get(0)).getCanonicalPath(),
                "컨텍스트의 repository 키로부터 작업 디렉터리가 해석되어야 합니다.");
    }

    @Test
    public void testRepositoryContextKeyWithNonHgRepositoryValueIsIgnoredForWorkingDirectory(@TempDir Path tempDir) throws Exception {
        // context.get("repository") instanceof HgRepository must take its false branch here --
        // testRepositoryContextResolvesWorkingDirectory only exercises the true (real HgRepository)
        // case. No working directory is derived; pb.directory(null) keeps the JVM's own cwd, so the
        // hook must still run successfully rather than throwing.
        File logFile = tempDir.resolve("noop_output.txt").toFile();
        File scriptFile = tempDir.resolve("noop_hook.sh").toFile();
        Files.writeString(scriptFile.toPath(), "#!/bin/sh\necho ran > \"" + logFile.getAbsolutePath() + "\"\nexit 0\n");
        scriptFile.setExecutable(true);

        ProcessHook hook = new ProcessHook(Arrays.asList("sh", scriptFile.getAbsolutePath()));
        Map<String, Object> context = new HashMap<>();
        context.put("repository", "not-actually-a-repository");

        assertTrue(hook.run(context));
        assertTrue(logFile.exists());
    }

    @Test
    public void testByteArrayContextValueMappedToHexEnvironmentVariable(@TempDir Path tempDir) throws Exception {
        File logFile = tempDir.resolve("node_output.txt").toFile();
        File scriptFile = tempDir.resolve("node_hook.sh").toFile();

        String scriptContent = "#!/bin/sh\n" +
                "echo \"Node:$HG_NODE\" > \"" + logFile.getAbsolutePath() + "\"\n" +
                "exit 0\n";
        Files.writeString(scriptFile.toPath(), scriptContent);
        scriptFile.setExecutable(true);

        byte[] node = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
        ProcessHook hook = new ProcessHook(Arrays.asList("sh", scriptFile.getAbsolutePath()));
        Map<String, Object> context = new HashMap<>();
        context.put("node", node);

        boolean result = hook.run(context);
        assertTrue(result, "정상 종료된 훅은 성공을 반환해야 합니다.");

        List<String> lines = Files.readAllLines(logFile.toPath());
        assertEquals(1, lines.size());
        assertEquals("Node:" + NodeIdUtil.toHex(node), lines.get(0),
                "byte[] 컨텍스트 값은 16진수 문자열로 변환되어 환경 변수로 전달되어야 합니다.");
    }

    @Test
    public void testHgRepositoryContextValueMappedToDirectoryPathEnvironmentVariable(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo2").toFile();
        HgRepository repository = Hg.init().setDirectory(repoDir).call();
        File logFile = tempDir.resolve("repopath_output.txt").toFile();
        File scriptFile = tempDir.resolve("repo_hook.sh").toFile();

        String scriptContent = "#!/bin/sh\n" +
                "echo \"Source:$HG_SOURCEREPO\" > \"" + logFile.getAbsolutePath() + "\"\n" +
                "exit 0\n";
        Files.writeString(scriptFile.toPath(), scriptContent);
        scriptFile.setExecutable(true);

        ProcessHook hook = new ProcessHook(Arrays.asList("sh", scriptFile.getAbsolutePath()));
        Map<String, Object> context = new HashMap<>();
        context.put("sourceRepo", repository);

        boolean result = hook.run(context);
        assertTrue(result, "정상 종료된 훅은 성공을 반환해야 합니다.");

        List<String> lines = Files.readAllLines(logFile.toPath());
        assertEquals(1, lines.size());
        assertEquals("Source:" + repository.getDirectory().getAbsolutePath(), lines.get(0),
                "HgRepository 컨텍스트 값은 절대 경로 문자열로 변환되어 환경 변수로 전달되어야 합니다.");
    }

    @Test
    public void testNullContextValueIsSkipped() throws Exception {
        ProcessHook hook = new ProcessHook("true");
        Map<String, Object> context = new HashMap<>();
        context.put("author", null);

        boolean result = hook.run(context);
        assertTrue(result, "null 값을 가진 컨텍스트 항목은 무시되고 훅은 정상적으로 실행되어야 합니다.");
    }

    @Test
    public void testProcessSpawnFailureThrowsIOException() {
        ProcessHook hook = new ProcessHook("this-binary-definitely-does-not-exist-12345");
        Map<String, Object> context = new HashMap<>();

        assertThrows(IOException.class, () -> hook.run(context),
                "존재하지 않는 실행 파일은 프로세스 생성 시 IOException을 던져야 합니다.");
    }

    @Test
    public void testInterruptedWaitForWrapsIOException() throws Exception {
        ProcessHook hook = new ProcessHook(Arrays.asList("sh", "-c", "sleep 2"));
        Map<String, Object> context = new HashMap<>();

        final Throwable[] thrown = new Throwable[1];
        Thread runner = new Thread(() -> {
            try {
                hook.run(context);
            } catch (Throwable t) {
                thrown[0] = t;
            }
        });
        runner.start();
        // Wide grace margin before interrupting: under heavy concurrent load (e.g. running the
        // full test suite) thread scheduling delays can eat into a tight window, so use a chunk
        // of the subprocess's 2-second sleep rather than a fixed 300ms that assumes prompt
        // scheduling (found via a real, reproducible failure under full-suite load, 2026-09-03).
        Thread.sleep(800);
        runner.interrupt();
        runner.join(10_000);

        assertFalse(runner.isAlive(), "인터럽트 이후 훅 실행 스레드는 종료되어야 합니다.");
        assertNotNull(thrown[0], "인터럽트가 발생하면 예외가 던져져야 합니다.");
        assertInstanceOf(IOException.class, thrown[0], "InterruptedException은 IOException으로 감싸져야 합니다.");
        assertInstanceOf(InterruptedException.class, thrown[0].getCause(), "원본 InterruptedException이 원인으로 유지되어야 합니다.");
    }
}
