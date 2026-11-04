package io.github.search5.hg4j.bundle;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bundle2 streams hg4j writes: compressed changegroup parts and the small reply/abort streams of a push. */
class Bundle2BuildersTest {

    private static boolean contains(byte[] haystack, String needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
    }

    @Test
    void aChangegroupPartSurvivesEveryCompression() throws Exception {
        byte[] changegroup = "0000".getBytes(StandardCharsets.US_ASCII);
        for (String compression : new String[]{null, "", "GZ", "gz", "BZ", "bz"}) {
            byte[] bundle = Bundle2Parser.wrapChangegroupInBundle2(changegroup, "02", compression);
            assertEquals("HG20", new String(bundle, 0, 4, StandardCharsets.US_ASCII));
            Bundle2Parser.ExtractedBundle2 extracted = Bundle2Parser.extractChangegroupDetailed(new ByteArrayInputStream(bundle));
            assertEquals("02", extracted.cgVersion, String.valueOf(compression));
            assertArrayEquals(changegroup, extracted.changegroupBytes, String.valueOf(compression));
        }
    }

    @Test
    void anUnknownCompressionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Bundle2Parser.wrapChangegroupInBundle2(new byte[0], "02", "ZZ"));
    }

    @Test
    void theChangegroupReplyCarriesTheRequestPartAndTheResult() throws Exception {
        byte[] reply = Bundle2Parser.buildChangegroupReplyBundle2(3, 1);
        assertEquals("HG20", new String(reply, 0, 4, StandardCharsets.US_ASCII));
        assertTrue(contains(reply, "reply:changegroup"));
        assertTrue(contains(reply, "in-reply-to"));
        assertTrue(contains(reply, "return"));
    }

    @Test
    void anEmptyReplyHasNoPartsAtAll() throws Exception {
        byte[] reply = Bundle2Parser.buildEmptyBundle2Reply();
        assertEquals(12, reply.length);
        assertEquals("HG20", new String(reply, 0, 4, StandardCharsets.US_ASCII));
    }

    @Test
    void anAbortStreamCarriesTheMessage() throws Exception {
        byte[] abort = Bundle2Parser.buildErrorAbortBundle2("push creates new remote head");
        assertTrue(contains(abort, "error:abort"));
        assertTrue(contains(abort, "message"));
        assertTrue(contains(abort, "push creates new remote head"));
    }

    @Test
    void theRequestedChangegroupVersionsAreReadFromTheCapabilities() {
        String token = Bundle2Parser.buildBundle2CapsToken("01,02,03");
        assertEquals(List.of("01", "02", "03"), Bundle2Parser.decodeChangegroupVersions(Arrays.asList(null, "HG20", token)));
        assertEquals(List.of(), Bundle2Parser.decodeChangegroupVersions(null));
        assertEquals(List.of(), Bundle2Parser.decodeChangegroupVersions(Collections.singletonList("HG20")));
    }
}
