package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.lfs.errors.HgLfsException;
import io.github.search5.hg4j.lfs.errors.HgLfsObjectNotFoundException;
import io.github.search5.hg4j.lfs.errors.HgLfsUnauthorizedException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HgLfsPointerSeamTest {

    private static final String OID = "7b1a2c3d".repeat(8);

    @Test
    void encodeThenParseRoundTripsWithExtraFields() throws Exception {
        HgLfsPointer pointer = new HgLfsPointer("https://git-lfs.github.com/spec/v1", OID, 2000,
                Map.of("x-is-binary", "0", "x-hg-copy", "a.bin", "x-hg-copyrev", "ab".repeat(20)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        pointer.encode(out);

        HgLfsPointer parsed = HgLfsPointer.parse(new ByteArrayInputStream(out.toByteArray()));

        assertEquals(pointer, parsed);
        assertEquals(pointer.hashCode(), parsed.hashCode());
        assertArrayEquals(pointer.serialize(), parsed.serialize());
    }

    @Test
    void pointersAreOrderedByOidThenSize() {
        HgLfsPointer small = new HgLfsPointer("v", "a".repeat(64), 1);
        HgLfsPointer large = new HgLfsPointer("v", "a".repeat(64), 9);
        HgLfsPointer later = new HgLfsPointer("v", "b".repeat(64), 1);
        assertTrue(small.compareTo(large) < 0);
        assertTrue(large.compareTo(later) < 0);
        assertEquals(0, small.compareTo(new HgLfsPointer("v", "a".repeat(64), 1)));
    }

    @Test
    void statusMapsToTheMatchingExceptionClass() {
        assertInstanceOf(HgLfsUnauthorizedException.class, HgLfsException.forStatus(401, "m"));
        assertInstanceOf(HgLfsUnauthorizedException.class, HgLfsException.forStatus(403, "m"));
        assertInstanceOf(HgLfsObjectNotFoundException.class, HgLfsException.forStatus(404, "m"));
        HgLfsException other = HgLfsException.forStatus(500, "m");
        assertEquals(HgLfsException.class, other.getClass());
        assertEquals(500, other.getStatus());
    }

    @Test
    void factoryDefaultsToTheBuiltinImplementation() {
        assertInstanceOf(BuiltinHgLfs.class, HgLfsFactory.getInstance());
        assertTrue(HgLfsFactory.getInstance().isAvailable());
    }
}
