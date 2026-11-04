package io.github.search5.hg4j.bundle;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * A general reader and writer of the Mercurial bundle2 ({@code HG20}) container, after {@code mercurial/bundle2.py}.
 *
 * <p>Layout: {@code "HG20"}, {@code int32} size of the stream-parameter block and that block (space separated, url-quoted
 * {@code key[=value]}; a key starting with an upper-case letter is mandatory, {@code Compression} being the only one in use),
 * then parts, then {@code int32 0}. A part is {@code int32} header size and the header ({@code u8} type size, type,
 * {@code int32} id, {@code u8} mandatory and {@code u8} advisory parameter counts, a {@code (u8 key size, u8 value size)} pair
 * per parameter, then each key followed by its value), followed by payload chunks ({@code int32} size and data, ended by
 * {@code int32 0}). A <em>mandatory</em> part has its type written in upper case. When {@code Compression} is set everything
 * after the stream parameters is one compressed stream.</p>
 */
public final class Bundle2 {

    private Bundle2() {
    }

    /** One part of a bundle. {@code type} is lower case; {@code mandatory} says whether it was written in upper case. */
    public static final class Part {
        /**
         * Part type in lower case.
         */
        public final String type;
        /**
         * Part id, as assigned by the writer of the bundle.
         */
        public final int id;
        /**
         * True if the type was written in upper case, so a reader that does not know the type must refuse the bundle.
         */
        public final boolean mandatory;
        /** Mandatory parameters first, then the advisory ones, in the order they were written. */
        public final Map<String, String> params;
        /**
         * The payload bytes of the part, with the chunk framing removed. May be empty.
         */
        public final byte[] payload;
        /** How many of {@link #params}, from the front, were mandatory parameters. */
        final int mandatoryParamCount;

        Part(String type, int id, boolean mandatory, Map<String, String> params, byte[] payload, int mandatoryParamCount) {
            this.mandatoryParamCount = mandatoryParamCount;
            this.type = type;
            this.id = id;
            this.mandatory = mandatory;
            this.params = params;
            this.payload = payload;
        }

        /**
         * Returns the value of a part parameter, mandatory or advisory.
         * @param key the parameter name
         * @return the value, or null if the part has no such parameter
         */
        public String param(String key) {
            return params.get(key);
        }
    }

    /** True when {@code bytes} starts with the bundle2 magic. */
    public static boolean isBundle2(byte[] bytes) {
        return bytes != null && bytes.length >= 4 && bytes[0] == 'H' && bytes[1] == 'G' && bytes[2] == '2' && bytes[3] == '0';
    }

    // ---------------------------------------------------------------- reading

    /** Reads every part of a bundle2 stream, un-compressing it first when the stream parameters say so. */
    public static List<Part> read(byte[] bundle) throws IOException {
        if (!isBundle2(bundle)) {
            throw new IOException("not a bundle2 stream");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bundle, 4, bundle.length - 4));
        int paramsSize = readInt(in);
        if (paramsSize < 0 || paramsSize > in.available()) {
            throw new IOException("illegal bundle2 stream parameter size " + paramsSize);
        }
        String compression = null;
        if (paramsSize > 0) {
            byte[] block = new byte[paramsSize];
            readExactly(in, block);
            for (String piece : new String(block, StandardCharsets.US_ASCII).split(" ")) {
                String[] kv = piece.split("=", 2);
                String key = URLDecoder.decode(kv[0], StandardCharsets.ISO_8859_1);
                String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.ISO_8859_1) : null;
                if (Character.isUpperCase(key.charAt(0))) {
                    if (!"Compression".equals(key)) {
                        throw new IOException("unsupported mandatory bundle2 stream parameter: " + key);
                    }
                    compression = value;
                }
            }
        }
        InputStream rest = in;
        if (compression != null) {
            switch (compression) {
                case "GZ" -> rest = new InflaterInputStream(in);
                case "BZ" -> rest = new BZip2CompressorInputStream(in);
                case "ZS" -> rest = new ZstdInputStream(in);
                case "UN" -> { }
                default -> throw new IOException("unknown bundle2 compression: " + compression);
            }
        }
        DataInputStream parts = new DataInputStream(rest);
        List<Part> result = new ArrayList<>();
        while (true) {
            int headerSize = readInt(parts);
            if (headerSize == 0) {
                return result;
            }
            if (headerSize < 0) {
                throw new IOException("illegal bundle2 part header size " + headerSize);
            }
            byte[] header = new byte[headerSize];
            readExactly(parts, header);
            result.add(parsePart(header, readPayload(parts)));
        }
    }

    /** mercurial's {@code readexactly}: a short stream is "stream ended unexpectedly (got N bytes, expected M)". */
    private static void readExactly(DataInputStream in, byte[] into) throws IOException {
        int got = 0;
        while (got < into.length) {
            int n = in.read(into, got, into.length - got);
            if (n < 0) {
                throw new IOException("stream ended unexpectedly (got " + got + " bytes, expected " + into.length + ")");
            }
            got += n;
        }
    }

    private static int readInt(DataInputStream in) throws IOException {
        byte[] four = new byte[4];
        readExactly(in, four);
        return ByteBuffer.wrap(four).getInt();
    }

    private static Part parsePart(byte[] header, byte[] payload) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(header);
        int typeSize = b.get() & 0xFF;
        byte[] typeBytes = new byte[typeSize];
        b.get(typeBytes);
        String rawType = new String(typeBytes, StandardCharsets.ISO_8859_1);
        int id = b.getInt();
        int mandatoryCount = b.get() & 0xFF;
        int advisoryCount = b.get() & 0xFF;
        int total = mandatoryCount + advisoryCount;
        int[] keySizes = new int[total];
        int[] valueSizes = new int[total];
        for (int i = 0; i < total; i++) {
            keySizes[i] = b.get() & 0xFF;
            valueSizes[i] = b.get() & 0xFF;
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < total; i++) {
            byte[] k = new byte[keySizes[i]];
            byte[] v = new byte[valueSizes[i]];
            b.get(k);
            b.get(v);
            params.put(new String(k, StandardCharsets.ISO_8859_1), new String(v, StandardCharsets.ISO_8859_1));
        }
        return new Part(rawType.toLowerCase(java.util.Locale.ROOT), id, !rawType.equals(rawType.toLowerCase(java.util.Locale.ROOT)),
                params, payload, mandatoryCount);
    }

    private static byte[] readPayload(DataInputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int size = readInt(in);
            if (size == 0) {
                return out.toByteArray();
            }
            if (size < 0) {
                // -1 is the "interrupt" marker real hg uses to push an out-of-band part inside a payload; it is not
                // produced by any client this server talks to, so it is refused instead of guessed at.
                throw new IOException("bundle2 interrupt chunks are not supported (chunk size " + size + ")");
            }
            byte[] chunk = new byte[size];
            readExactly(in, chunk);
            out.write(chunk);
        }
    }

    // ---------------------------------------------------------------- writing

    /** Builds a bundle2 stream part by part. */
    public static final class Writer {
        private final ByteArrayOutputStream parts = new ByteArrayOutputStream();
        private int nextId;

        /**
         * Creates a writer whose first part gets id 0.
         */
        public Writer() {
        }

        /** A writer whose part ids continue after an existing bundle's parts. */
        public Writer(int firstId) {
            this.nextId = firstId;
        }

        /** The bytes of the parts added so far (without the stream header or the end marker). */
        byte[] partsBytes() {
            return parts.toByteArray();
        }

        /**
         * Adds a part and returns its id.
         *
         * @param mandatory  whether a reader that does not know the type must refuse the bundle (upper-case type on the wire)
         * @param mandatoryParams parameters a reader must understand
         * @param advisoryParams  parameters a reader may ignore
         */
        public int addPart(String type, boolean mandatory, Map<String, String> mandatoryParams,
                           Map<String, String> advisoryParams, byte[] payload) {
            try {
                int id = nextId++;
                byte[] typeBytes = (mandatory ? type.toUpperCase(java.util.Locale.ROOT) : type.toLowerCase(java.util.Locale.ROOT))
                        .getBytes(StandardCharsets.ISO_8859_1);
                ByteArrayOutputStream header = new ByteArrayOutputStream();
                header.write(typeBytes.length);
                header.write(typeBytes);
                header.write(ByteBuffer.allocate(4).putInt(id).array());
                header.write(mandatoryParams.size());
                header.write(advisoryParams.size());
                List<byte[]> keysAndValues = new ArrayList<>();
                for (Map<String, String> group : List.of(mandatoryParams, advisoryParams)) {
                    for (Map.Entry<String, String> e : group.entrySet()) {
                        byte[] k = e.getKey().getBytes(StandardCharsets.ISO_8859_1);
                        byte[] v = e.getValue().getBytes(StandardCharsets.ISO_8859_1);
                        header.write(k.length);
                        header.write(v.length);
                        keysAndValues.add(k);
                        keysAndValues.add(v);
                    }
                }
                for (byte[] kv : keysAndValues) {
                    header.write(kv);
                }
                writeInt(parts, header.size());
                parts.write(header.toByteArray());
                if (payload.length > 0) {
                    writeInt(parts, payload.length);
                    parts.write(payload);
                }
                writeInt(parts, 0);
                return id;
            } catch (IOException e) {
                throw new IllegalStateException(e); // a ByteArrayOutputStream does not fail
            }
        }

        /**
         * Adds a mandatory part with only mandatory parameters; same as the five-argument overload with
         * {@code mandatory} true and no advisory parameters.
         * @param type the part type; written in upper case
         * @param params the mandatory parameters; written as ISO-8859-1; must not be null
         * @param payload the payload bytes; must not be null (use an empty array for none)
         * @return the id given to the part
         * @throws IllegalStateException if writing fails
         */
        public int addPart(String type, Map<String, String> params, byte[] payload) {
            return addPart(type, true, params, Map.of(), payload);
        }

        /** The finished stream; {@code compression} is {@code null}, {@code "GZ"}, {@code "BZ"} or {@code "ZS"}. */
        public byte[] toBytes(String compression) throws IOException {
            ByteArrayOutputStream rest = new ByteArrayOutputStream();
            rest.write(parts.toByteArray());
            writeInt(rest, 0);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write("HG20".getBytes(StandardCharsets.US_ASCII));
            if (compression == null || compression.isEmpty()) {
                writeInt(out, 0);
                out.write(rest.toByteArray());
                return out.toByteArray();
            }
            byte[] param = ("Compression=" + compression).getBytes(StandardCharsets.US_ASCII);
            writeInt(out, param.length);
            out.write(param);
            OutputStream compressed = switch (compression) {
                case "GZ" -> new DeflaterOutputStream(out, new Deflater());
                case "BZ" -> new BZip2CompressorOutputStream(out);
                case "ZS" -> new ZstdOutputStream(out);
                default -> throw new IllegalArgumentException("unsupported bundle2 compression: " + compression);
            };
            try (compressed) {
                compressed.write(rest.toByteArray());
            }
            return out.toByteArray();
        }
    }

    /**
     * Returns {@code bundle} with the parts of {@code extra} added after its own. The result is not compressed, whatever the
     * original was, which every reader accepts.
     */
    public static byte[] appendParts(byte[] bundle, Writer extra) throws IOException {
        List<Part> existing = read(bundle);
        Writer all = new Writer(0);
        for (Part part : existing) {
            Map<String, String> mandatory = new LinkedHashMap<>();
            Map<String, String> advisory = new LinkedHashMap<>();
            int index = 0;
            for (Map.Entry<String, String> e : part.params.entrySet()) {
                (index++ < part.mandatoryParamCount ? mandatory : advisory).put(e.getKey(), e.getValue());
            }
            all.addPart(part.type, part.mandatory, mandatory, advisory, part.payload);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("HG20".getBytes(StandardCharsets.US_ASCII));
        writeInt(out, 0);
        out.write(all.partsBytes());
        out.write(extra.partsBytes());
        writeInt(out, 0);
        return out.toByteArray();
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write(ByteBuffer.allocate(4).putInt(value).array());
    }
}
