package lsmdb.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class WALCodecTest {

    @Test
    @DisplayName("Round-trip PUT entry preserves all fields")
    void roundTripPut() {
        // Given
        String key = "user:123";
        String value = "{\"name\":\"John\",\"age\":30}";

        // When
        byte[] encoded = WALCodec.encode(WALCodec.OP_PUT, key, value);
        WALEntry decoded = WALCodec.decode(encoded);

        // Then
        assertEquals(WALCodec.OP_PUT, decoded.opcode());
        assertEquals(key, decoded.key());
        assertEquals(value, decoded.value());
        assertTrue(decoded.isPut());
        assertFalse(decoded.isDelete());
    }

    @Test
    @DisplayName("Round-trip DELETE entry has empty value")
    void roundTripDelete() {
        // Given
        String key = "session:abc";
        String value = "should-be-ignored";  // encode() should ignore this

        // When
        byte[] encoded = WALCodec.encode(WALCodec.OP_DELETE, key, value);
        WALEntry decoded = WALCodec.decode(encoded);

        // Then
        assertEquals(WALCodec.OP_DELETE, decoded.opcode());
        assertEquals(key, decoded.key());
        assertEquals("", decoded.value());
        assertTrue(decoded.isDelete());
        assertFalse(decoded.isPut());
    }

    @Test
    @DisplayName("Empty value PUT round-trips as empty string, not null")
    void emptyValuePut() {
        // Given
        String key = "empty";
        String value = "";

        // When
        byte[] encoded = WALCodec.encode(WALCodec.OP_PUT, key, value);
        WALEntry decoded = WALCodec.decode(encoded);

        // Then
        assertEquals(WALCodec.OP_PUT, decoded.opcode());
        assertEquals(key, decoded.key());
        assertNotNull(decoded.value());
        assertEquals("", decoded.value());
    }

    @Test
    @DisplayName("Unicode key and value round-trip correctly")
    void unicodeKeyAndValue() {
        // Given
        String key = "ユーザー:東京";
        String value = "こんにちは世界！🎉 中文测试";

        // When
        byte[] encoded = WALCodec.encode(WALCodec.OP_PUT, key, value);
        WALEntry decoded = WALCodec.decode(encoded);

        // Then
        assertEquals(WALCodec.OP_PUT, decoded.opcode());
        assertEquals(key, decoded.key());
        assertEquals(value, decoded.value());

        // Verify UTF-8 byte lengths are used correctly
        assertEquals(key.getBytes(StandardCharsets.UTF_8).length,
                getKeyLengthFromPayload(encoded));
    }

    @Test
    @DisplayName("Large 100KB value round-trips intact")
    void largeValue() {
        // Given
        String key = "large-key";
        StringBuilder sb = new StringBuilder(102400);
        Random random = new Random(42); // Fixed seed for reproducibility
        for (int i = 0; i < 102400; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        String value = sb.toString();

        // When
        byte[] encoded = WALCodec.encode(WALCodec.OP_PUT, key, value);
        WALEntry decoded = WALCodec.decode(encoded);

        // Then
        assertEquals(WALCodec.OP_PUT, decoded.opcode());
        assertEquals(key, decoded.key());
        assertEquals(value.length(), decoded.value().length());
        assertEquals(value, decoded.value());

        // Verify the encoded payload size
        int expectedSize = 1 + 1 + 4 + key.getBytes(StandardCharsets.UTF_8).length
                + 4 + value.getBytes(StandardCharsets.UTF_8).length;
        assertEquals(expectedSize, encoded.length);
    }

    @Test
    @DisplayName("Decode rejects unknown opcode")
    void invalidOpcode() {
        // Given - construct a valid-looking payload but with opcode = 99
        String key = "test";
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 4 + keyBytes.length + 4);
        buffer.put(WALCodec.VERSION);
        buffer.put((byte) 99);  // Invalid opcode
        buffer.putInt(keyBytes.length);
        buffer.put(keyBytes);
        buffer.putInt(0);  // Empty value

        byte[] invalidPayload = buffer.array();

        // When & Then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> WALCodec.decode(invalidPayload)
        );
        assertTrue(exception.getMessage().contains("unknown opcode"));
    }

    @Test
    @DisplayName("Decode rejects unsupported version byte")
    void unsupportedVersion() {
        // Given
        String key = "test";
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 4 + keyBytes.length + 4);
        buffer.put((byte) 99);  // Invalid version
        buffer.put(WALCodec.OP_PUT);
        buffer.putInt(keyBytes.length);
        buffer.put(keyBytes);
        buffer.putInt(0);

        byte[] invalidPayload = buffer.array();

        // When & Then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> WALCodec.decode(invalidPayload)
        );
        assertTrue(exception.getMessage().contains("version"));
    }

    @Test
    @DisplayName("Decode rejects truncated payload")
    void truncatedPayload() {
        // Given - create a valid payload but cut it short
        String key = "truncated-key";
        String value = "truncated-value";
        byte[] fullPayload = WALCodec.encode(WALCodec.OP_PUT, key, value);
        byte[] truncatedPayload = Arrays.copyOf(fullPayload, fullPayload.length - 3);

        // When & Then
        assertThrows(
                IllegalArgumentException.class,
                () -> WALCodec.decode(truncatedPayload)
        );
    }

    @Test
    @DisplayName("Decode rejects DELETE entry with non-empty value")
    void deleteWithNonEmptyValue() {
        // Given - manually construct DELETE entry with value
        String key = "key";
        String value = "should-not-be-here";
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);

        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 4 + keyBytes.length + 4 + valueBytes.length);
        buffer.put(WALCodec.VERSION);
        buffer.put(WALCodec.OP_DELETE);
        buffer.putInt(keyBytes.length);
        buffer.put(keyBytes);
        buffer.putInt(valueBytes.length);
        buffer.put(valueBytes);

        byte[] invalidPayload = buffer.array();

        // When & Then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> WALCodec.decode(invalidPayload)
        );
        assertTrue(exception.getMessage().contains("DELETE"));
    }

    @Test
    @DisplayName("Encode rejects null key")
    void encodeNullKey() {
        assertThrows(
                NullPointerException.class,
                () -> WALCodec.encode(WALCodec.OP_PUT, null, "value")
        );
    }

    @Test
    @DisplayName("Encode rejects null value for PUT")
    void encodeNullValueForPut() {
        assertThrows(
                NullPointerException.class,
                () -> WALCodec.encode(WALCodec.OP_PUT, "key", null)
        );
    }

    @Test
    @DisplayName("Encode rejects invalid opcode")
    void encodeInvalidOpcode() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WALCodec.encode((byte) 42, "key", "value")
        );
    }

    @Test
    @DisplayName("Multiple round-trips are consistent")
    void multipleRoundTrips() {
        String[][] testData = {
                {"key1", "value1"},
                {"key2", ""},
                {"", "value3"},
                {"", ""},
                {"key with spaces", "value with spaces"},
                {"ключ", "значение"},
                {"🔑", "💾"},
        };

        for (String[] pair : testData) {
            // PUT round-trip
            byte[] encodedPut = WALCodec.encode(WALCodec.OP_PUT, pair[0], pair[1]);
            WALEntry decodedPut = WALCodec.decode(encodedPut);
            assertEquals(WALCodec.OP_PUT, decodedPut.opcode());
            assertEquals(pair[0], decodedPut.key());
            assertEquals(pair[1], decodedPut.value());

            // DELETE round-trip
            byte[] encodedDelete = WALCodec.encode(WALCodec.OP_DELETE, pair[0], "");
            WALEntry decodedDelete = WALCodec.decode(encodedDelete);
            assertEquals(WALCodec.OP_DELETE, decodedDelete.opcode());
            assertEquals(pair[0], decodedDelete.key());
            assertEquals("", decodedDelete.value());
        }
    }

    // Helper method to extract key length from payload for verification
    private int getKeyLengthFromPayload(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.get(); // Skip version
        buffer.get(); // Skip opcode
        return buffer.getInt();
    }
}