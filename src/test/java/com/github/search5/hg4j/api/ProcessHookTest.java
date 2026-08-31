package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
        assertThrows(com.github.search5.hg4j.errors.HgValidationException.class, () -> {
            commitCmd.call();
        }, "Pre-commit hook이 실패를 반환하면 커밋이 거부되어 예외가 터져야 합니다.");
    }

    @Test
    public void testProcessHookQuoteSplitting() throws Exception {
        // Pass a script path containing spaces wrapped in quotes
        ProcessHook hook = new ProcessHook("\"/path/to/my script.sh\" arg1 'arg2 with space'");
        
        java.lang.reflect.Field cmdField = ProcessHook.class.getDeclaredField("command");
        cmdField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> commandList = (List<String>) cmdField.get(hook);
        
        assertEquals(3, commandList.size(), "명령어는 3개의 인자로 파싱되어야 합니다.");
        assertEquals("/path/to/my script.sh", commandList.get(0));
        assertEquals("arg1", commandList.get(1));
        assertEquals("arg2 with space", commandList.get(2));
    }
}
