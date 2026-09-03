package io.github.search5.hg4j.bundle;

import org.junit.jupiter.api.Test;

import io.github.search5.hg4j.errors.HgCorruptDataException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class Bundle2ParserTest {

    private static byte[] partHeaderBlock(String partName, Map<String, String> mandatoryParams, Map<String, String> advisoryParams) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        byte[] nameBytes = partName.getBytes(StandardCharsets.US_ASCII);
        header.write(nameBytes.length);
        header.write(nameBytes);
        header.write(new byte[]{0, 0, 0, 1}); // part id
        header.write(mandatoryParams.size());
        header.write(advisoryParams.size());

        Map<String, String> allParams = new LinkedHashMap<>();
        allParams.putAll(mandatoryParams);
        allParams.putAll(advisoryParams);
        for (Map.Entry<String, String> e : allParams.entrySet()) {
            header.write(e.getKey().getBytes(StandardCharsets.US_ASCII).length);
            header.write(e.getValue().getBytes(StandardCharsets.US_ASCII).length);
        }
        for (Map.Entry<String, String> e : allParams.entrySet()) {
            header.write(e.getKey().getBytes(StandardCharsets.US_ASCII));
            header.write(e.getValue().getBytes(StandardCharsets.US_ASCII));
        }
        return header.toByteArray();
    }

    private static void writePart(DataOutputStream dos, String partName, Map<String, String> mandatoryParams, Map<String, String> advisoryParams, byte[]... chunks) throws IOException {
        byte[] headerBlock = partHeaderBlock(partName, mandatoryParams, advisoryParams);
        dos.writeInt(headerBlock.length);
        dos.write(headerBlock);
        for (byte[] chunk : chunks) {
            dos.writeInt(chunk.length);
            dos.write(chunk);
        }
        dos.writeInt(0); // end of this part's payload
    }

    private static void writeStreamHeader(DataOutputStream dos, String streamParams) throws IOException {
        dos.write("HG20".getBytes(StandardCharsets.US_ASCII));
        byte[] paramBytes = streamParams.getBytes(StandardCharsets.UTF_8);
        dos.writeInt(paramBytes.length);
        dos.write(paramBytes);
    }

    @Test
    public void defaultConstructorIsInstantiable() {
        assertNotNull(new Bundle2Parser());
    }

    @Test
    public void streamParamWithoutCompressionKeyIsIgnoredAndEmptyCompressionValueMeansUncompressed() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(out)) {
            // "foo=bar" hits the false branch of param.startsWith("Compression=");
            // the trailing empty "Compression=" hits compression != null && compression.isEmpty() == true,
            // so the payload below must be read uncompressed, exactly like when no Compression param exists at all.
            writeStreamHeader(dos, "foo=bar Compression=");
            writePart(dos, "CHANGEGROUP", Map.of(), Map.of(), "PlainPayload".getBytes(StandardCharsets.UTF_8));
            dos.writeInt(0); // end of bundle2
        }

        byte[] result = Bundle2Parser.extractChangegroup(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("PlainPayload", new String(result, StandardCharsets.UTF_8));
    }

    @Test
    public void unsupportedCompressionCodecThrows() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(out)) {
            writeStreamHeader(dos, "Compression=XZ");
        }

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        HgCorruptDataException ex = assertThrows(HgCorruptDataException.class, () -> Bundle2Parser.extractChangegroup(in));
        assertEquals("Unsupported bundle2 compression: XZ", ex.getMessage());
    }

    @Test
    public void nonChangegroupPartParamsAndPayloadAreSkippedWhileNonVersionParamOnRealPartIsIgnored() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(out)) {
            writeStreamHeader(dos, "");

            // isChangegroup == false here, so this part's params and payload chunk must both be
            // parsed but discarded rather than appended to the extracted changegroup bytes.
            writePart(dos, "REPLYCAPS", Map.of("foo", "bar"), Map.of(), "ignored-caps-payload".getBytes(StandardCharsets.UTF_8));

            Map<String, String> mandatory = new LinkedHashMap<>();
            mandatory.put("version", "01");
            mandatory.put("nbchanges", "5"); // isChangegroup == true but paramName != "version"
            writePart(dos, "CHANGEGROUP", mandatory, Map.of(), "RealPayload".getBytes(StandardCharsets.UTF_8));

            dos.writeInt(0); // end of bundle2
        }

        Bundle2Parser.ExtractedBundle2 result = Bundle2Parser.extractChangegroupDetailed(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("01", result.cgVersion);
        assertEquals("RealPayload", new String(result.changegroupBytes, StandardCharsets.UTF_8));
    }

    @Test
    public void nestedStreamInterruptChunkThrows() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(out)) {
            writeStreamHeader(dos, "");

            byte[] headerBlock = partHeaderBlock("CHANGEGROUP", Map.of(), Map.of());
            dos.writeInt(headerBlock.length);
            dos.write(headerBlock);
            dos.writeInt(-1); // interrupt / nested part marker
        }

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        HgCorruptDataException ex = assertThrows(HgCorruptDataException.class, () -> Bundle2Parser.extractChangegroup(in));
        assertEquals("Nested stream interrupts are not supported in this lightweight parser.", ex.getMessage());
    }

    @Test
    public void streamWithNoChangegroupPartThrows() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(out)) {
            writeStreamHeader(dos, "");
            writePart(dos, "REPLYCAPS", Map.of(), Map.of());
            dos.writeInt(0); // end of bundle2, no CHANGEGROUP part ever seen
        }

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        HgCorruptDataException ex = assertThrows(HgCorruptDataException.class, () -> Bundle2Parser.extractChangegroup(in));
        assertEquals("No CHANGEGROUP part found in the bundle2 stream.", ex.getMessage());
    }
}
