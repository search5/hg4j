package com.github.search5.hg4j.transport;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Streaming parser helper for handling application/mercurial-cbor framing streams
 * to extract sequential CBOR items/tokens dynamically.
 */
public class CborFrameParser {

    private final ObjectMapper mapper = new CBORMapper();
    private final CBORFactory factory = (CBORFactory) mapper.getFactory();

    /**
     * Parses the incoming stream and extracts a list of sequential CBOR frame objects.
     */
    public List<Object> parseFrames(InputStream in) throws IOException {
        List<Object> frames = new ArrayList<>();
        try (JsonParser parser = factory.createParser(in)) {
            // Keep parsing root-level values until EOF
            while (parser.nextToken() != null) {
                frames.add(parser.readValueAs(Object.class));
            }
        }
        return frames;
    }

    /**
     * Iterates over a CBOR array stream and executes callback per item to prevent OOM
     * when processing very large file/changeset listings.
     */
    public void parseArrayStream(InputStream in, FrameCallback callback) throws IOException {
        try (JsonParser parser = factory.createParser(in)) {
            JsonToken token = parser.nextToken();
            if (token == JsonToken.START_ARRAY) {
                while (parser.nextToken() != JsonToken.END_ARRAY && parser.currentToken() != null) {
                    Object item = parser.readValueAs(Object.class);
                    callback.onFrame(item);
                }
            } else if (token != null) {
                // If not an array, parse it as a single object frame
                callback.onFrame(parser.readValueAs(Object.class));
            }
        }
    }

    public interface FrameCallback {
        void onFrame(Object frame) throws IOException;
    }
}
