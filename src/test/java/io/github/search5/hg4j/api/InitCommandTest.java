package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

public class InitCommandTest {

    @Test
    public void testInitCreatesBasicStructure(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        
        // Execute the init command
        HgRepository repository = Hg.init()
                .setDirectory(repoDir)
                .call();

        assertNotNull(repository);
        assertEquals(repoDir, repository.getDirectory());
        assertNotNull(repository.getHgDir());
        assertNotNull(repository.getStoreDir());

        File hgDir = new File(repoDir, ".hg");
        assertTrue(hgDir.exists() && hgDir.isDirectory(), ".hg directory should exist");

        File storeDir = new File(hgDir, "store");
        assertTrue(storeDir.exists() && storeDir.isDirectory(), ".hg/store directory should exist");

        File requiresFile = new File(hgDir, "requires");
        assertTrue(requiresFile.exists() && requiresFile.isFile(), ".hg/requires file should exist");

        // Verify requirements content
        List<String> requirements = Files.readAllLines(requiresFile.toPath());
        assertTrue(requirements.contains("store"), "requires should contain 'store'");
        assertTrue(requirements.contains("fncache"), "requires should contain 'fncache'");
        assertTrue(requirements.contains("dotencode"), "requires should contain 'dotencode'");
        assertTrue(requirements.contains("generaldelta"), "requires should contain 'generaldelta'");
        assertTrue(requirements.contains("revlogv1"), "requires should contain 'revlogv1'");

        // Test running it again on existing directory
        HgRepository repository2 = Hg.init()
                .setDirectory(repoDir)
                .call();
        assertNotNull(repository2);
    }

    @Test
    public void testInitThrowsExceptionOnInvalidDirectory() {
        File invalidDir = new File("/nonexistent/path/that/cannot/be/created/sub");
        InitCommand init = Hg.init().setDirectory(invalidDir);
        assertThrows(IOException.class, init::call);
    }

    @Test
    public void testInitThrowsExceptionWhenDirectoryIsFile(@TempDir Path tempDir) throws IOException {
        File tempFile = Files.createTempFile(tempDir, "some", "file").toFile();
        InitCommand init = Hg.init().setDirectory(tempFile);
        assertThrows(IOException.class, init::call);
    }

    @Test
    public void testInitThrowsExceptionWhenDirectoryNull() {
        InitCommand init = Hg.init();
        assertThrows(IllegalStateException.class, init::call);
    }

    @Test
    public void testInitFailsWhenHgDirIsFile(@TempDir Path tempDir) throws IOException {
        File repoDir = tempDir.toFile();
        File fakeHg = new File(repoDir, ".hg");
        assertTrue(fakeHg.createNewFile());
        InitCommand init = Hg.init().setDirectory(repoDir);
        assertThrows(IOException.class, init::call);
    }

    @Test
    public void testInitFailsWhenStoreDirIsFile(@TempDir Path tempDir) throws IOException {
        File repoDir = tempDir.toFile();
        File hgDir = new File(repoDir, ".hg");
        assertTrue(hgDir.mkdir());
        File fakeStore = new File(hgDir, "store");
        assertTrue(fakeStore.createNewFile());
        InitCommand init = Hg.init().setDirectory(repoDir);
        assertThrows(IOException.class, init::call);
    }

    @Test
    public void testInitCreatesDirectoryIfNotExist(@TempDir Path tempDir) throws IOException {
        File repoDir = new File(tempDir.toFile(), "nonexistent_subdir");
        assertFalse(repoDir.exists());
        HgRepository repository = Hg.init().setDirectory(repoDir).call();
        assertNotNull(repository);
        assertTrue(repoDir.exists() && repoDir.isDirectory());
    }

    @Test
    public void testHgConstructorCoverage() throws Exception {
        Constructor<Hg> constructor = Hg.class.getDeclaredConstructor(HgRepository.class);
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        constructor.setAccessible(true);
        HgRepository fakeRepo = new HgRepository(new File("."));
        Hg instance = constructor.newInstance(fakeRepo);
        assertNotNull(instance);
    }

    @Test
    public void testInitFailsWhenHgDirMkdirFails(@TempDir Path tempDir) throws IOException {
        File repoDir = new File(tempDir.toFile(), "readonly_dir");
        assertTrue(repoDir.mkdir());
        try {
            assertTrue(repoDir.setReadOnly());
            InitCommand init = Hg.init().setDirectory(repoDir);
            assertThrows(IOException.class, init::call);
        } finally {
            // Restore write permissions so JUnit temp dir cleanup doesn't fail
            repoDir.setWritable(true);
        }
    }

    @Test
    public void testInitFailsWhenStoreDirMkdirFails(@TempDir Path tempDir) throws IOException {
        File repoDir = tempDir.toFile();
        File hgDir = new File(repoDir, ".hg");
        assertTrue(hgDir.mkdir());
        try {
            assertTrue(hgDir.setReadOnly());
            InitCommand init = Hg.init().setDirectory(repoDir);
            assertThrows(IOException.class, init::call);
        } finally {
            hgDir.setWritable(true);
        }
    }

    @Test
    public void testInitFailsWhenWritingRequiresFails(@TempDir Path tempDir) throws IOException {
        File repoDir = tempDir.toFile();
        File hgDir = new File(repoDir, ".hg");
        assertTrue(hgDir.mkdir());
        File storeDir = new File(hgDir, "store");
        assertTrue(storeDir.mkdir());
        try {
            assertTrue(hgDir.setReadOnly());
            InitCommand init = Hg.init().setDirectory(repoDir);
            assertThrows(IOException.class, init::call);
        } finally {
            hgDir.setWritable(true);
        }
    }
}
