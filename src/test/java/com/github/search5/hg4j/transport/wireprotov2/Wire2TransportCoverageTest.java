package com.github.search5.hg4j.transport.wireprotov2;

import com.github.search5.hg4j.errors.HgProtocolException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage-focused unit tests for {@link Wire2Transport}, exercising the frame-assembly paths
 * that {@link Wire2CommandsTest} (which goes through real repositories) and
 * {@code HgHttpTransportV2RoundtripTest} (which only ever sends one small command per request)
 * never reach: multi-command batching, request continuation frames, large chunked responses, and
 * the two error envelopes (the {@code {status:"error"}} COMMAND_RESPONSE body built by
 * {@link Wire2Transport#buildCommandErrorResponse}, and the real-hg-only {@code ERROR_RESPONSE}
 * frame type -- see {@code mercurial/wireprotoframing.py}'s {@code createerrorframe}, used for
 * unhandled server-side exceptions, which this project's own server never emits but a real
 * Mercurial 6.0 server can).
 */
public class Wire2TransportCoverageTest {

    // ==================== buildCommandErrorResponse ====================

    @Test
    public void buildCommandErrorResponseEncodesAStatusErrorEnvelopeInASingleEosFrame() throws IOException {
        byte[] encoded = Wire2Transport.buildCommandErrorResponse(7, "boom");

        Wire2Frame frame = Wire2Frame.read(new ByteArrayInputStream(encoded));
        assertNotNull(frame);
        assertEquals(7, frame.requestId);
        assertEquals(Wire2Frame.TYPE_COMMAND_RESPONSE, frame.typeId);
        assertTrue(frame.hasFlag(Wire2Frame.FLAG_COMMAND_RESPONSE_EOS));
        assertFalse(frame.hasStreamFlag(Wire2Frame.STREAM_FLAG_BEGIN),
                "an error response never opens the stream itself -- buildStreamSettingsFrame always does that first");

        List<Object> objs = Cbor.decodeAll(frame.payload);
        Map<String, Object> body = Cbor.asMap(objs.get(0));
        assertEquals("error", Cbor.asString(body.get("status")));
        Map<String, Object> error = Cbor.asMap(body.get("error"));
        assertEquals("boom", Cbor.asString(error.get("message")));
    }

    /** Confirms the two ends of the wire agree: what the server builds, the client rejects. */
    @Test
    public void readCommandResponseThrowsForAServerBuiltErrorResponse() {
        byte[] encoded = Wire2Transport.buildCommandErrorResponse(3, "no such command");

        HgProtocolException e = assertThrows(HgProtocolException.class,
                () -> Wire2Transport.readCommandResponse(new ByteArrayInputStream(encoded)));
        assertTrue(e.getMessage().contains("no such command"), e.getMessage());
    }

    // ==================== readCommandResponse: ERROR_RESPONSE frame type ====================

    @Test
    public void readCommandResponseDecodesAnErrorResponseFramePayloadIntoItsPlainMessageText() {
        // Mirrors real hg's wireprotoframing.createerrorframe payload shape exactly:
        // {type: ..., message: [{msg: ...}]} -- used for unhandled server exceptions, distinct
        // from the {status:"error"} COMMAND_RESPONSE envelope used for expected command errors.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "server");
        body.put("message", List.of(Map.of("msg", "unexpected failure in command handler")));
        byte[] payload = Cbor.encode(body);
        byte[] encoded = new Wire2Frame(1, 2, 0, Wire2Frame.TYPE_ERROR_RESPONSE, 0, payload).encode();

        HgProtocolException e = assertThrows(HgProtocolException.class,
                () -> Wire2Transport.readCommandResponse(new ByteArrayInputStream(encoded)));
        assertEquals("Transport error for 'wireprotov2': Protocol-level error: unexpected failure in command handler",
                e.getMessage(), "the raw CBOR structure bytes must not leak into the human-readable message");
    }

    @Test
    public void readCommandResponseJoinsMultipleMessageAtomsWithASpace() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "server");
        body.put("message", List.of(Map.of("msg", "first"), Map.of("msg", "second")));
        byte[] payload = Cbor.encode(body);
        byte[] encoded = new Wire2Frame(1, 2, 0, Wire2Frame.TYPE_ERROR_RESPONSE, 0, payload).encode();

        HgProtocolException e = assertThrows(HgProtocolException.class,
                () -> Wire2Transport.readCommandResponse(new ByteArrayInputStream(encoded)));
        assertEquals("Transport error for 'wireprotov2': Protocol-level error: first second", e.getMessage());
    }

    @Test
    public void readCommandResponseFallsBackToRawBytesWhenAnErrorFramePayloadIsNotDecodableCbor() {
        byte[] garbage = "not cbor".getBytes(StandardCharsets.UTF_8);
        byte[] encoded = new Wire2Frame(2, 2, 0, Wire2Frame.TYPE_ERROR_RESPONSE, 0, garbage).encode();

        HgProtocolException e = assertThrows(HgProtocolException.class,
                () -> Wire2Transport.readCommandResponse(new ByteArrayInputStream(encoded)));
        assertTrue(e.getMessage().contains("not cbor"), e.getMessage());
    }

    @Test
    public void readCommandResponseFallsBackToRawBytesWhenAnErrorFramePayloadDecodesToNoCborObjectsAtAll() {
        byte[] encoded = new Wire2Frame(4, 2, 0, Wire2Frame.TYPE_ERROR_RESPONSE, 0, new byte[0]).encode();

        HgProtocolException e = assertThrows(HgProtocolException.class,
                () -> Wire2Transport.readCommandResponse(new ByteArrayInputStream(encoded)));
        assertEquals("Transport error for 'wireprotov2': Protocol-level error: ", e.getMessage());
    }

    @Test
    public void readCommandResponseFallsBackToRawBytesWhenNoMessageAtomCarriesAMsgField() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "server");
        body.put("message", List.of(Map.of("unrelated", "field")));
        byte[] payload = Cbor.encode(body);
        byte[] encoded = new Wire2Frame(5, 2, 0, Wire2Frame.TYPE_ERROR_RESPONSE, 0, payload).encode();

        HgProtocolException e = assertThrows(HgProtocolException.class,
                () -> Wire2Transport.readCommandResponse(new ByteArrayInputStream(encoded)));
        assertEquals("Transport error for 'wireprotov2': Protocol-level error: "
                + new String(payload, StandardCharsets.UTF_8), e.getMessage());
    }

    // ==================== readCommandResponse: empty/edge bodies ====================

    @Test
    public void readCommandResponseOnAStreamWithNoFramesAtAllReturnsAnEmptyList() throws IOException {
        List<Object> result = Wire2Transport.readCommandResponse(new ByteArrayInputStream(new byte[0]));
        assertTrue(result.isEmpty());
    }

    // ==================== buildCommandRequest: null args ====================

    @Test
    public void buildCommandRequestOmitsTheArgsFieldWhenArgsIsNull() throws IOException {
        byte[] encoded = Wire2Transport.buildCommandRequest(5, "heads", null);

        Wire2Frame frame = Wire2Frame.read(new ByteArrayInputStream(encoded));
        List<Object> objs = Cbor.decodeAll(frame.payload);
        Map<String, Object> data = Cbor.asMap(objs.get(0));
        assertEquals("heads", Cbor.asString(data.get("name")));
        assertFalse(data.containsKey("args"), "no args map was supplied at all, so 'args' must be absent, not empty");
    }

    // ==================== readAllCommandRequests ====================

    @Test
    public void readAllCommandRequestsOnEmptyInputReturnsAnEmptyList() throws IOException {
        assertTrue(Wire2Transport.readAllCommandRequests(new ByteArrayInputStream(new byte[0])).isEmpty());
    }

    @Test
    public void readAllCommandRequestsSkipsALeadingSenderProtocolSettingsFrame() throws IOException {
        // Real hg's default client always sends this frame first, declaring compression
        // encoders this server ignores (it always replies identity) -- see the class javadoc.
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        byte[] settingsPayload = Cbor.encode(Map.of("contentencodings", List.of("identity")));
        new Wire2Frame(1, 1, Wire2Frame.STREAM_FLAG_BEGIN, Wire2Frame.TYPE_SENDER_PROTOCOL_SETTINGS, 0, settingsPayload)
                .write(stream);
        stream.write(Wire2Transport.buildCommandRequest(1, "heads", null));

        List<Wire2Transport.ParsedCommandRequest> result =
                Wire2Transport.readAllCommandRequests(new ByteArrayInputStream(stream.toByteArray()));

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).requestId);
        assertEquals("heads", result.get(0).name);
    }

    @Test
    public void readAllCommandRequestsToleratesACommandRequestFrameWithACompletelyEmptyPayload() throws IOException {
        // A zero-length payload decodes to zero CBOR objects (not a CBOR null/empty map), so the
        // "objs.isEmpty() ? null : objs.get(0)" guard is what keeps this from NPEing on asMap.
        byte[] encoded = new Wire2Frame(6, 1, Wire2Frame.STREAM_FLAG_BEGIN, Wire2Frame.TYPE_COMMAND_REQUEST,
                Wire2Frame.FLAG_COMMAND_REQUEST_NEW, new byte[0]).encode();

        List<Wire2Transport.ParsedCommandRequest> result =
                Wire2Transport.readAllCommandRequests(new ByteArrayInputStream(encoded));

        assertEquals(1, result.size());
        assertEquals(6, result.get(0).requestId);
        assertNull(result.get(0).name);
        assertTrue(result.get(0).args.isEmpty());
    }

    @Test
    public void readAllCommandRequestsRejectsAnyFrameTypeOtherThanCommandRequestOrSenderSettings() {
        byte[] bogus = new Wire2Frame(1, 1, Wire2Frame.STREAM_FLAG_BEGIN, Wire2Frame.TYPE_TEXT_OUTPUT, 0, new byte[0])
                .encode();
        ByteArrayInputStream in = new ByteArrayInputStream(bogus);

        HgProtocolException e = assertThrows(HgProtocolException.class, () -> Wire2Transport.readAllCommandRequests(in));
        assertTrue(e.getMessage().contains("Expected COMMAND_REQUEST"), e.getMessage());
    }

    @Test
    public void readAllCommandRequestsReassemblesACommandSplitAcrossMoreFramesContinuationFrames() throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "known");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("nodes", List.of((Object) new byte[20]));
        data.put("args", args);
        byte[] fullPayload = Cbor.encode(data);
        int split = fullPayload.length / 2;
        byte[] first = Arrays.copyOfRange(fullPayload, 0, split);
        byte[] second = Arrays.copyOfRange(fullPayload, split, fullPayload.length);

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        new Wire2Frame(9, 1, Wire2Frame.STREAM_FLAG_BEGIN, Wire2Frame.TYPE_COMMAND_REQUEST,
                Wire2Frame.FLAG_COMMAND_REQUEST_NEW | Wire2Frame.FLAG_COMMAND_REQUEST_MORE_FRAMES, first)
                .write(stream);
        new Wire2Frame(9, 1, 0, Wire2Frame.TYPE_COMMAND_REQUEST,
                Wire2Frame.FLAG_COMMAND_REQUEST_CONTINUATION, second)
                .write(stream);

        List<Wire2Transport.ParsedCommandRequest> result =
                Wire2Transport.readAllCommandRequests(new ByteArrayInputStream(stream.toByteArray()));

        assertEquals(1, result.size());
        assertEquals(9, result.get(0).requestId);
        assertEquals("known", result.get(0).name);
        assertNotNull(result.get(0).args.get("nodes"));
    }

    @Test
    public void readAllCommandRequestsThrowsOnEofWhileWaitingForAPromisedContinuationFrame() {
        byte[] onlyFrame = new Wire2Frame(1, 1, Wire2Frame.STREAM_FLAG_BEGIN, Wire2Frame.TYPE_COMMAND_REQUEST,
                Wire2Frame.FLAG_COMMAND_REQUEST_NEW | Wire2Frame.FLAG_COMMAND_REQUEST_MORE_FRAMES, new byte[]{1, 2, 3})
                .encode();
        ByteArrayInputStream in = new ByteArrayInputStream(onlyFrame); // no continuation frame ever arrives

        HgProtocolException e = assertThrows(HgProtocolException.class, () -> Wire2Transport.readAllCommandRequests(in));
        assertTrue(e.getMessage().contains("Unexpected EOF"), e.getMessage());
    }

    @Test
    public void readAllCommandRequestsParsesMultipleIndependentCommandsFromOneBatchedMultirequest() throws IOException {
        // Real hg's client batches multiple commands (e.g. heads + known) into one multirequest
        // POST during clone -- see the class javadoc.
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(Wire2Transport.buildCommandRequest(1, "heads", null));
        Map<String, Object> knownArgs = new LinkedHashMap<>();
        knownArgs.put("nodes", List.of((Object) new byte[20]));
        stream.write(Wire2Transport.buildCommandRequest(3, "known", knownArgs));

        List<Wire2Transport.ParsedCommandRequest> result =
                Wire2Transport.readAllCommandRequests(new ByteArrayInputStream(stream.toByteArray()));

        assertEquals(2, result.size());
        assertEquals(1, result.get(0).requestId);
        assertEquals("heads", result.get(0).name);
        assertEquals(3, result.get(1).requestId);
        assertEquals("known", result.get(1).name);
    }

    // ==================== writeChunked (via buildCommandResponseFrames) ====================

    @Test
    public void buildCommandResponseFramesSplitsALargePayloadAcrossMultipleContinuationFrames() throws IOException {
        // writeChunked's frame cap is 32768 bytes; force several frames.
        byte[] bigBlob = new byte[80_000];
        new Random(42).nextBytes(bigBlob);
        byte[] encoded = Wire2Transport.buildCommandResponseFrames(11, List.of((Object) bigBlob));

        List<Wire2Frame> frames = new ArrayList<>();
        ByteArrayInputStream in = new ByteArrayInputStream(encoded);
        Wire2Frame f;
        while ((f = Wire2Frame.read(in)) != null) {
            frames.add(f);
        }
        assertTrue(frames.size() >= 3, "an 80KB body over a 32KB frame cap must split into several frames, got " + frames.size());

        for (int i = 0; i < frames.size(); i++) {
            Wire2Frame frame = frames.get(i);
            boolean isLast = i == frames.size() - 1;
            assertEquals(11, frame.requestId);
            assertEquals(Wire2Frame.TYPE_COMMAND_RESPONSE, frame.typeId);
            assertTrue(frame.hasStreamFlag(Wire2Frame.STREAM_FLAG_ENCODING_APPLIED));
            assertEquals(isLast, frame.hasFlag(Wire2Frame.FLAG_COMMAND_RESPONSE_EOS),
                    "only the last chunk carries EOS, frame " + i);
            assertEquals(!isLast, frame.hasFlag(Wire2Frame.FLAG_COMMAND_RESPONSE_CONTINUATION),
                    "every non-final chunk carries CONTINUATION, frame " + i);
        }

        // Round-trips through the reading side too, covering readCommandResponse's
        // continuation-frame accumulation loop (a non-EOS COMMAND_RESPONSE frame keeps reading).
        List<Object> result = Wire2Transport.readCommandResponse(new ByteArrayInputStream(encoded));
        assertEquals(1, result.size());
        assertArrayEquals(bigBlob, (byte[]) result.get(0));
    }
}
