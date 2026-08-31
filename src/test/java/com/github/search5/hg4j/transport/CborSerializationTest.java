package com.github.search5.hg4j.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Jackson CBOR Serialization & Deserialization Basic Tests")
public class CborSerializationTest {

    @Test
    @DisplayName("Successfully serializes a Java Map into CBOR bytes and deserializes it back")
    void testMapSerialization() throws IOException {
        ObjectMapper mapper = new CBORMapper();

        Map<String, Object> payload = new HashMap<>();
        payload.put("command", "capabilities");
        payload.put("args", Arrays.asList("heads", "lookup"));
        payload.put("v2", true);

        // Serialize
        byte[] serialized = mapper.writeValueAsBytes(payload);
        assertNotNull(serialized);
        assertTrue(serialized.length > 0);

        // Deserialize
        @SuppressWarnings("unchecked")
        Map<String, Object> deserialized = mapper.readValue(serialized, Map.class);
        assertNotNull(deserialized);
        assertEquals("capabilities", deserialized.get("command"));
        assertEquals(true, deserialized.get("v2"));
        
        List<?> args = (List<?>) deserialized.get("args");
        assertEquals(2, args.size());
        assertEquals("heads", args.get(0));
    }
}
