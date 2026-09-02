package com.github.search5.hg4j.transport.wireprotov2;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit tests for {@link Wire2Frame}'s header encode/decode, targeting paths that
 * {@code Wire2CommandsTest} and {@code Wire2TransportCoverageTest} only exercise incidentally
 * through {@link Wire2Transport}: clean-EOF vs. truncated-header vs. truncated-payload
 * distinctions in {@link Wire2Frame#read}, the individual flag/nibble bit packing in
 * {@link Wire2Frame#encode}, and {@link Wire2Frame#concatenatePayloads}.
 */
public class Wire2FrameTest {

    @Test
    public void encodeThenReadRoundTripsAllHeaderFields() throws IOException {
        byte[] payload = {1, 2, 3, 4, 5};
        Wire2Frame original = new Wire2Frame(0x1234, 0x56, 0x78, Wire2Frame.TYPE_COMMAND_REQUEST,
                Wire2Frame.FLAG_COMMAND_REQUEST_NEW, payload);

        byte[] encoded = original.encode();
        assertEquals(Wire2Frame.HEADER_SIZE + payload.length, encoded.length);

        Wire2Frame decoded = Wire2Frame.read(new ByteArrayInputStream(encoded));
        assertEquals(original.requestId, decoded.requestId);
        assertEquals(original.streamId, decoded.streamId);
        assertEquals(original.streamFlags, decoded.streamFlags);
        assertEquals(original.typeId, decoded.typeId);
        assertEquals(original.flags, decoded.flags);
        assertArrayEquals(payload, decoded.payload);
    }

    @Test
    public void encodePacksTypeIdAndFlagsIntoOppositeNibblesOfTheLastHeaderByte() {
        // typeId in the high nibble, flags in the low nibble -- verify both boundaries at once
        // by using the max representable value (0x0F) for each field.
        Wire2Frame frame = new Wire2Frame(0, 0, 0, 0x0F, 0x0F, new byte[0]);
        byte[] encoded = frame.encode();
        assertEquals((byte) 0xFF, encoded[7]);
    }

    @Test
    public void encodeMasksTypeIdAndFlagsToFourBitsEach() {
        // Real callers never pass out-of-range values, but encode() explicitly masks with 0x0F,
        // so verify that masking actually happens rather than assuming it from the source.
        Wire2Frame frame = new Wire2Frame(0, 0, 0, 0xF3, 0xF7, new byte[0]);
        byte[] encoded = frame.encode();
        assertEquals((byte) 0x37, encoded[7]);
    }

    @Test
    public void encodeWritesPayloadLengthAndRequestIdLittleEndianAcrossTwoAndThreeBytesRespectively() throws IOException {
        int len24BitBoundary = 0x030201; // exercises all three payload-length bytes distinctly
        byte[] payload = new byte[len24BitBoundary];
        int requestId16Bit = 0xBEEF; // exercises both request-id bytes distinctly
        Wire2Frame frame = new Wire2Frame(requestId16Bit, 9, 0, Wire2Frame.TYPE_TEXT_OUTPUT, 0, payload);

        byte[] encoded = frame.encode();
        assertEquals((byte) 0x01, encoded[0]);
        assertEquals((byte) 0x02, encoded[1]);
        assertEquals((byte) 0x03, encoded[2]);
        assertEquals((byte) 0xEF, encoded[3]);
        assertEquals((byte) 0xBE, encoded[4]);

        Wire2Frame decoded = Wire2Frame.read(new ByteArrayInputStream(encoded));
        assertEquals(requestId16Bit, decoded.requestId);
        assertEquals(len24BitBoundary, decoded.payload.length);
    }

    @Test
    public void readReturnsNullOnCleanEofBeforeAnyHeaderByteIsRead() throws IOException {
        Wire2Frame frame = Wire2Frame.read(new ByteArrayInputStream(new byte[0]));
        assertNull(frame);
    }

    @Test
    public void readThrowsEofExceptionWhenStreamEndsPartwayThroughTheHeader() {
        byte[] partialHeader = {1, 2, 3}; // fewer than HEADER_SIZE (8) bytes, then EOF
        EOFException ex = assertThrows(EOFException.class,
                () -> Wire2Frame.read(new ByteArrayInputStream(partialHeader)));
        assertTrue(ex.getMessage().contains("header"), "message should identify a truncated header: " + ex.getMessage());
    }

    @Test
    public void readThrowsEofExceptionWhenStreamEndsPartwayThroughThePayload() {
        byte[] full = new Wire2Frame(1, 1, 0, Wire2Frame.TYPE_COMMAND_DATA, 0, new byte[10]).encode();
        byte[] truncated = Arrays.copyOf(full, Wire2Frame.HEADER_SIZE + 4); // header intact, only 4 of 10 payload bytes

        EOFException ex = assertThrows(EOFException.class,
                () -> Wire2Frame.read(new ByteArrayInputStream(truncated)));
        assertTrue(ex.getMessage().contains("payload"), "message should identify a truncated payload: " + ex.getMessage());
    }

    @Test
    public void readHandlesAZeroLengthPayloadWithoutTouchingTheStreamFurther() throws IOException {
        byte[] encoded = new Wire2Frame(3, 1, 0, Wire2Frame.TYPE_PROGRESS, 0, new byte[0]).encode();
        Wire2Frame frame = Wire2Frame.read(new ByteArrayInputStream(encoded));
        assertEquals(0, frame.payload.length);
    }

    @Test
    public void readAssemblesHeaderAndPayloadAcrossMultipleShortUnderlyingReads() throws IOException {
        byte[] encoded = new Wire2Frame(0x0102, 4, 5, Wire2Frame.TYPE_STREAM_SETTINGS,
                Wire2Frame.FLAG_STREAM_SETTINGS_EOS, new byte[]{9, 8, 7, 6, 5}).encode();

        InputStream oneByteAtATime = new InputStream() {
            int pos = 0;

            @Override
            public int read() {
                return pos < encoded.length ? (encoded[pos++] & 0xFF) : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (pos >= encoded.length) {
                    return -1;
                }
                b[off] = encoded[pos++];
                return 1;
            }
        };

        Wire2Frame frame = Wire2Frame.read(oneByteAtATime);
        assertEquals(0x0102, frame.requestId);
        assertEquals(4, frame.streamId);
        assertEquals(5, frame.streamFlags);
        assertArrayEquals(new byte[]{9, 8, 7, 6, 5}, frame.payload);
    }

    @Test
    public void hasStreamFlagAndHasFlagDistinguishSetFromUnsetBits() {
        Wire2Frame frame = new Wire2Frame(0, 0,
                Wire2Frame.STREAM_FLAG_BEGIN | Wire2Frame.STREAM_FLAG_ENCODING_APPLIED,
                Wire2Frame.TYPE_COMMAND_RESPONSE, Wire2Frame.FLAG_COMMAND_RESPONSE_CONTINUATION, new byte[0]);

        assertTrue(frame.hasStreamFlag(Wire2Frame.STREAM_FLAG_BEGIN));
        assertTrue(frame.hasStreamFlag(Wire2Frame.STREAM_FLAG_ENCODING_APPLIED));
        assertFalse(frame.hasStreamFlag(Wire2Frame.STREAM_FLAG_END));

        assertTrue(frame.hasFlag(Wire2Frame.FLAG_COMMAND_RESPONSE_CONTINUATION));
        assertFalse(frame.hasFlag(Wire2Frame.FLAG_COMMAND_RESPONSE_EOS));
    }

    @Test
    public void writeSendsExactlyTheEncodedBytesToTheOutputStream() throws IOException {
        Wire2Frame frame = new Wire2Frame(1, 1, 0, Wire2Frame.TYPE_COMMAND_DATA, 0, new byte[]{42});
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        frame.write(out);

        assertArrayEquals(frame.encode(), out.toByteArray());
    }

    @Test
    public void concatenatePayloadsJoinsFramePayloadsInListOrder() throws IOException {
        List<Wire2Frame> frames = new ArrayList<>();
        frames.add(new Wire2Frame(1, 1, 0, Wire2Frame.TYPE_COMMAND_RESPONSE, 0, new byte[]{1, 2}));
        frames.add(new Wire2Frame(1, 1, 0, Wire2Frame.TYPE_COMMAND_RESPONSE, 0, new byte[0]));
        frames.add(new Wire2Frame(1, 1, 0, Wire2Frame.TYPE_COMMAND_RESPONSE, 0, new byte[]{3, 4, 5}));

        byte[] joined = Wire2Frame.concatenatePayloads(frames);

        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, joined);
    }

    @Test
    public void concatenatePayloadsOfEmptyListIsEmpty() throws IOException {
        assertArrayEquals(new byte[0], Wire2Frame.concatenatePayloads(new ArrayList<>()));
    }
}
