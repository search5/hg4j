package com.github.search5.hg4j.transport.wireprotov2;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage-focused tests for {@link Cbor} and its nested {@code Reader}, targeting branches not
 * exercised by the higher-level wireprotocol v2 tests: {@code null}/boolean encoding, negative
 * integers, every RFC 8949 length-prefix width (additional info 24/25/26/27), CBOR tags (major
 * type 6, transparently unwrapped by this implementation), unsupported/malformed input handling,
 * and the {@code asX} accessor helpers.
 *
 * <p>Raw byte sequences below are hand-built against RFC 8949 ("Concise Binary Object
 * Representation (CBOR)") section 3 (initial byte = major type in top 3 bits, additional info in
 * bottom 5 bits) rather than any test-only helper, so that these tests independently verify
 * {@link Cbor}'s conformance rather than exercising a mirror encoding of the same production
 * code.</p>
 */
class CborCoverageTest {

    // ==================== null / boolean ====================

    @Test
    void encodeDecodeNull_roundTrips() {
        byte[] bytes = Cbor.encode(null);
        assertEquals(1, bytes.length);
        assertEquals((byte) 0xf6, bytes[0]);

        List<Object> decoded = Cbor.decodeAll(bytes);
        assertEquals(1, decoded.size());
        assertNull(decoded.get(0));
    }

    @Test
    void encodeDecodeBooleans_roundTrip() {
        byte[] bytes = Cbor.encodeAll(Arrays.asList(Boolean.TRUE, Boolean.FALSE));
        List<Object> decoded = Cbor.decodeAll(bytes);
        assertEquals(List.of(Boolean.TRUE, Boolean.FALSE), decoded);
    }

    @Test
    void decodeUndefinedSimpleValue_isNull() {
        // 0xf6 = major type 7 (simple/float), info 22 -> "null"
        // 0xf7 = major type 7, info 23 -> "undefined"; this decoder maps both to Java null.
        byte[] raw = {(byte) 0xf6, (byte) 0xf7};
        List<Object> decoded = Cbor.decodeAll(raw);
        assertEquals(2, decoded.size());
        assertNull(decoded.get(0));
        assertNull(decoded.get(1));
    }

    // ==================== integer length-prefix widths (encode + decode) ====================

    @Test
    void encodeDecodeIntegers_acrossAllLengthPrefixWidths() {
        // Spans every RFC 8949 additional-info width for both major type 0 (unsigned) and major
        // type 1 (negative): direct value (<24), 1-byte (24), 2-byte (25), 4-byte (26), and
        // 8-byte (27) -- exercising every branch of Cbor's private writeHead/readLength without
        // needing to allocate multi-gigabyte arrays, since only the *numeric magnitude* selects
        // the width, not the payload size.
        List<Long> values = Arrays.asList(
                0L, 1L, 23L,                     // direct (info < 24)
                24L, 100L, 255L,                 // 1-byte length (info 24)
                256L, 1000L, 65535L,             // 2-byte length (info 25)
                65536L, 1_000_000L, 4294967295L, // 4-byte length (info 26)
                4294967296L, Long.MAX_VALUE,     // 8-byte length (info 27)
                -1L, -23L, -24L,
                -25L, -100L, -256L,
                -257L, -1000L, -65536L,
                -65537L, -1_000_000L, -4294967296L,
                -4294967297L, Long.MIN_VALUE
        );

        byte[] bytes = Cbor.encodeAll(new ArrayList<>(values));
        List<Object> decoded = Cbor.decodeAll(bytes);

        assertEquals(values.size(), decoded.size());
        for (int i = 0; i < values.size(); i++) {
            assertEquals(values.get(i), ((Number) decoded.get(i)).longValue(),
                    "mismatch decoding value " + values.get(i));
        }
    }

    @Test
    void encodeDecodeInteger_acceptsNonLongNumberTypes() {
        // Real callers often hand in a plain int; writeInt only needs longValue().
        byte[] bytes = Cbor.encode(42);
        assertEquals(List.of(42L), Cbor.decodeAll(bytes));
    }

    @Test
    void encodeDecodeByteArray_directly() {
        // Cbor.encode(byte[]) is the major-type-2 top-level path; other tests here only exercise
        // byte[] indirectly (as a map value under a String key already covered elsewhere).
        byte[] value = {1, 2, 3, 4, 5};
        byte[] bytes = Cbor.encode(value);
        List<Object> decoded = Cbor.decodeAll(bytes);
        assertEquals(1, decoded.size());
        assertArrayEquals(value, (byte[]) decoded.get(0));
    }

    @Test
    void decodeTextString_majorType3_isSupportedThoughRealHgNeverEmitsIt() {
        // Major type 3 (UTF-8 text string), info 2 -> "hi". Real hg's cborutil always emits
        // major type 2 (byte string) for strings (see Cbor's class javadoc), but genuine CBOR
        // text strings are still valid input this decoder must handle correctly.
        byte[] raw = {0x62, 'h', 'i'};
        List<Object> decoded = Cbor.decodeAll(raw);
        assertEquals(List.of("hi"), decoded);
    }

    // ==================== unsupported encode type ====================

    @Test
    void encodeUnsupportedType_throwsIllegalArgumentException() {
        Object unsupported = new Object();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Cbor.encode(unsupported));
        assertTrue(ex.getMessage().contains("Cannot CBOR-encode"));
    }

    // ==================== tags (major type 6) ====================

    @Test
    void decodeTag_isTransparentlyUnwrapped() {
        // Major type 6 (tag), info 0 -> tag number 0 (encoded directly in the initial byte,
        // needing no extra length bytes), wrapping the unsigned integer 5 (major type 0, info 5).
        // RFC 8949 S3.4: a tag is "the number... followed by a single data item (the tag
        // content)". This decoder discards the tag number and returns the content unwrapped.
        byte[] raw = {(byte) 0xC0, 0x05};
        List<Object> decoded = Cbor.decodeAll(raw);
        assertEquals(List.of(5L), decoded);
    }

    @Test
    void decodeTag_withMultiByteTagNumber_isTransparentlyUnwrapped() {
        // Major type 6, info 25 (0xd9) -> 2-byte tag number follows (here: 258, real hg's "set"
        // tag on some capability descriptor fields), wrapping the byte string "x".
        byte[] raw = {(byte) 0xd9, 0x01, 0x02, 0x41, 0x78};
        List<Object> decoded = Cbor.decodeAll(raw);
        assertEquals(1, decoded.size());
        assertArrayEquals(new byte[]{0x78}, (byte[]) decoded.get(0));
    }

    // ==================== malformed / unsupported input ====================

    @Test
    void decodeIndefiniteLength_throwsIllegalArgumentException() {
        // Major type 2 (byte string), info 31 -> indefinite length. Real hg's
        // cborutil.streamencode always uses definite lengths for every command this client
        // talks to, so this decoder deliberately does not support the indefinite-length form.
        byte[] raw = {0x5F};
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Cbor.decodeAll(raw));
        assertTrue(ex.getMessage().contains("Indefinite-length"));
    }

    @Test
    void decodeFloat_throwsIllegalArgumentException() {
        // Major type 7, info 26 -> IEEE-754 single-precision float (~3.14). This protocol never
        // sends floats (see Cbor's class javadoc), and this decoder has no float support at all.
        byte[] raw = {(byte) 0xfa, 0x40, 0x48, (byte) 0xf5, (byte) 0xc3};
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Cbor.decodeAll(raw));
        assertTrue(ex.getMessage().contains("Unsupported CBOR simple/float type"));
    }

    @Test
    void decodeTruncatedInput_throwsRatherThanSilentlyReturning() {
        // Major type 2 (byte string), info 3 -> declares a 3-byte payload, but the buffer ends
        // right after the header. Confirms truncated input fails loudly instead of returning a
        // corrupt short byte[] or reading past the buffer silently.
        byte[] raw = {0x43};
        assertThrows(IndexOutOfBoundsException.class, () -> Cbor.decodeAll(raw));
    }

    // ==================== map keys ====================

    @Test
    void decodeMap_nonByteStringKey_isCoercedToString() {
        // Map {1: "x"}, but with the key encoded as an unsigned integer (major type 0) instead
        // of the byte-string keys real hg always uses. Cbor.Reader's key coercion falls back to
        // String.valueOf for any non-String key, so this should still produce map.get("1").
        byte[] raw = {(byte) 0xA1, 0x01, 0x41, 0x78};
        List<Object> decoded = Cbor.decodeAll(raw);
        Map<String, Object> map = Cbor.asMap(decoded.get(0));
        assertEquals("x", Cbor.asString(map.get("1")));
    }

    @Test
    void encodeDecodeMediumSizedList_roundTrips() {
        // 300 elements forces the array header's length prefix into the 2-byte (info 25) range,
        // exercising the major-type-4 array path with a length wider than a single byte -- akin
        // to a real manifest/changeset-list response with a few hundred entries.
        List<Object> list = new ArrayList<>();
        for (long i = 0; i < 300; i++) {
            list.add(i);
        }
        byte[] bytes = Cbor.encode(list);
        List<Object> decodedOuter = Cbor.decodeAll(bytes);
        List<Object> decoded = Cbor.asList(decodedOuter.get(0));
        assertEquals(300, decoded.size());
        assertEquals(0L, ((Number) decoded.get(0)).longValue());
        assertEquals(299L, ((Number) decoded.get(299)).longValue());
    }

    // ==================== map/list round trip with mixed value types ====================

    @Test
    void encodeDecodeMap_withNullAndListValues() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("present", "value");
        map.put("absent", null);
        map.put("items", Arrays.asList(1, 2, 3));

        byte[] bytes = Cbor.encode(map);
        Map<String, Object> decoded = Cbor.asMap(Cbor.decodeAll(bytes).get(0));

        assertEquals("value", Cbor.asString(decoded.get("present")));
        assertNull(decoded.get("absent"));
        assertEquals(List.of(1L, 2L, 3L), Cbor.asList(decoded.get("items")));
    }

    // ==================== asX accessor helpers ====================

    @Test
    void asString_handlesNullBytesAndOtherTypes() {
        assertNull(Cbor.asString(null));
        assertEquals("hi", Cbor.asString("hi"));
        assertEquals("hi", Cbor.asString("hi".getBytes(StandardCharsets.UTF_8)));
        assertEquals("42", Cbor.asString(42L));
    }

    @Test
    void asLong_handlesNumbersAndNonNumbers() {
        assertEquals(5L, Cbor.asLong(5L, -1));
        assertEquals(5L, Cbor.asLong(5, -1));
        assertEquals(-1L, Cbor.asLong("not-a-number", -1));
        assertEquals(-1L, Cbor.asLong(null, -1));
    }

    @Test
    void asBoolean_handlesBooleansAndNonBooleans() {
        assertTrue(Cbor.asBoolean(Boolean.TRUE, false));
        assertFalse(Cbor.asBoolean(Boolean.FALSE, true));
        assertTrue(Cbor.asBoolean("not-a-boolean", true));
        assertFalse(Cbor.asBoolean(null, false));
    }

    @Test
    void asBytes_returnsNullForNonByteArray() {
        assertNull(Cbor.asBytes("not-bytes"));
        assertNull(Cbor.asBytes(null));
        byte[] b = {1, 2, 3};
        assertSame(b, Cbor.asBytes(b));
    }

    @Test
    void asMapAsList_returnEmptyForWrongType() {
        assertTrue(Cbor.asMap("not-a-map").isEmpty());
        assertTrue(Cbor.asList("not-a-list").isEmpty());
    }
}
