package org.hg4j.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class HgSubrepoTest {

    @Test
    public void testSubrepositoriesParsingSuccess() throws IOException {
        String hgsubText = "# Subrepositories configuration\n" +
                "libs/core = https://hg.example.com/libs/core\n" +
                "libs/external = [git]https://github.com/external/project.git\n" +
                "libs/unused = https://hg.example.com/libs/unused\n";

        String hgsubstateText = "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b libs/core\n" +
                "7b1a2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b libs/external\n";

        Map<String, HgSubrepoEntry> subrepos = HgSubrepoParser.parseSubrepositories(
                hgsubText.getBytes(StandardCharsets.UTF_8),
                hgsubstateText.getBytes(StandardCharsets.UTF_8)
        );

        assertNotNull(subrepos);
        assertEquals(3, subrepos.size());

        // Check regular Mercurial subrepo
        HgSubrepoEntry core = subrepos.get("libs/core");
        assertNotNull(core);
        assertEquals("libs/core", core.getPath());
        assertEquals("https://hg.example.com/libs/core", core.getSourceUrl());
        assertEquals("1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b", core.getRevision());
        assertFalse(core.isGit());

        // Check Git subrepo
        HgSubrepoEntry external = subrepos.get("libs/external");
        assertNotNull(external);
        assertEquals("libs/external", external.getPath());
        assertEquals("https://github.com/external/project.git", external.getSourceUrl());
        assertEquals("7b1a2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b", external.getRevision());
        assertTrue(external.isGit());

        // Check unused config fallback
        HgSubrepoEntry unused = subrepos.get("libs/unused");
        assertNotNull(unused);
        assertEquals("libs/unused", unused.getPath());
        assertEquals("https://hg.example.com/libs/unused", unused.getSourceUrl());
        assertEquals("", unused.getRevision()); // No state recorded

        // ToString/Equals coverage
        HgSubrepoEntry unusedClone = new HgSubrepoEntry("libs/unused", "https://hg.example.com/libs/unused", "", false);
        assertEquals(unusedClone, unused);
        assertEquals(unusedClone.hashCode(), unused.hashCode());
        assertNotNull(unused.toString());
    }

    @Test
    public void testSubrepositoriesParsingMalformedConfigThrows() {
        String badHgsub = "libs/core https://hg.example.com/libs/core\n"; // missing '='

        assertThrows(org.hg4j.errors.HgCorruptDataException.class, () -> {
            HgSubrepoParser.parseSubrepositories(badHgsub.getBytes(StandardCharsets.UTF_8), null);
        });
    }

    @Test
    public void testSubrepositoriesParsingMalformedStateThrows() {
        String hgsub = "libs/core = https://hg.example.com/libs/core\n";
        String badState = "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b_libs/core\n"; // missing space

        assertThrows(org.hg4j.errors.HgCorruptDataException.class, () -> {
            HgSubrepoParser.parseSubrepositories(hgsub.getBytes(StandardCharsets.UTF_8), badState.getBytes(StandardCharsets.UTF_8));
        });
    }
}
