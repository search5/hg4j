package io.github.search5.hg4j.dirstate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Byte-for-byte regression test against a real dirstate-v2 docket + data file captured from an
 * actual Mercurial 6.0 server (Docker, {@code storage.dirstate-v2.slow-path=allow} pure-Python
 * path): {@code hg init --config format.exp-rc-dirstate-v2=1}, then {@code hg add a.txt sub/b.txt}
 * (both left in the "added" state, uncommitted).
 *
 * <p>An earlier version of {@link DirstateV2Node} used entirely different (never-verified, purely
 * invented) byte offsets and flag bit positions for the 44-byte {@code NODE} struct — self-
 * consistent for hg4j's own reader/writer round-trip, but unable to read this real fixture at all
 * (every field would decode to garbage). This test pins the corrected layout against real bytes
 * so a future change can't silently drift away from the real spec again the same way.</p>
 */
public class DirstateV2RealFixtureTest {

    // `.hg/dirstate` docket: real Mercurial 6.0's DirstateDocket.serialize() output.
    private static final byte[] REAL_DOCKET_BYTES = {
            (byte) 0x64, (byte) 0x69, (byte) 0x72, (byte) 0x73, (byte) 0x74, (byte) 0x61, (byte) 0x74, (byte) 0x65, (byte) 0x2d, (byte) 0x76, (byte) 0x32, (byte) 0x0a,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x3d, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x02,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x95, (byte) 0x08, (byte) 0x33, (byte) 0x64, (byte) 0x37, (byte) 0x37, (byte) 0x39, (byte) 0x64, (byte) 0x62,
            (byte) 0x63
    };

    // `.hg/dirstate.3d779dbc` data file.
    private static final byte[] REAL_DATA_BYTES = {
            (byte) 0x73, (byte) 0x75, (byte) 0x62, (byte) 0x2f, (byte) 0x62, (byte) 0x2e, (byte) 0x74, (byte) 0x78, (byte) 0x74, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x09, (byte) 0x00, (byte) 0x04, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x61, (byte) 0x2e, (byte) 0x74, (byte) 0x78, (byte) 0x74, (byte) 0x73, (byte) 0x75,
            (byte) 0x62, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x35, (byte) 0x00, (byte) 0x05, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x3a, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x09, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x20, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00
    };

    @Test
    public void parsesRealMercurial6DirstateV2FixtureThroughDirstateRead(@TempDir Path tempDir) throws Exception {
        File hgDir = tempDir.resolve(".hg").toFile();
        hgDir.mkdirs();
        Files.write(new File(hgDir, "dirstate").toPath(), REAL_DOCKET_BYTES);
        Files.write(new File(hgDir, "dirstate.3d779dbc").toPath(), REAL_DATA_BYTES);

        Dirstate dirstate = new Dirstate();
        dirstate.read(new File(hgDir, "dirstate"));

        assertTrue(dirstate.isV2());
        assertEquals(2, dirstate.getEntries().size());

        assertTrue(dirstate.getEntries().containsKey("a.txt"));
        assertTrue(dirstate.getEntries().containsKey("sub/b.txt"));
        // Both files were `hg add`ed but never committed by the real server: tracked in the
        // working copy but not yet in any parent -> real hg's 'a' (added) state.
        assertEquals('a', dirstate.getEntries().get("a.txt").getState());
        assertEquals('a', dirstate.getEntries().get("sub/b.txt").getState());
        // Neither has cached mode/size/mtime (HAS_MODE_AND_SIZE/HAS_MTIME both unset for a
        // freshly-added file with no prior parent-manifest data to compare against).
        assertEquals(0, dirstate.getEntries().get("a.txt").getSize());
    }

    @Test
    public void parsesRealFixtureNodesDirectlyAtTheVerifiedOffsets() throws Exception {
        DirstateV2Parser parser = new DirstateV2Parser();
        // root_nodes_start=61, root_nodes_len=2, read straight from the docket's tree metadata
        // (offset 76 / 80) -- see REAL_DOCKET_BYTES bytes 76..83 above (0x3d=61, 0x02).
        Dirstate decoded = parser.parse(REAL_DATA_BYTES, 61, 2);

        assertEquals(2, decoded.getEntries().size());
        assertEquals('a', decoded.getEntries().get("a.txt").getState());
        assertEquals('a', decoded.getEntries().get("sub/b.txt").getState());
    }
}
