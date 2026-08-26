package lsmdb.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WALIntegrationTest {

    @TempDir
    Path tempDir;

    private Path walFilePath;

    @BeforeEach
    void setUp() {
        walFilePath = tempDir.resolve("test.wal");
    }

    // Helper: create WAL, encode+append entries, close, return file path (already have)
    private void appendEntries(byte opcode, String key, String value) throws Exception {
        try (WAL wal = new WAL(walFilePath)) {
            byte[] payload = WALCodec.encode(opcode, key, value);
            wal.append(payload);
        }
    }

    // Helper: simulate crash by opening WAL (for recovery only) and replaying to a new Memtable
    private Memtable recoverToNewMemtable() throws Exception {
        Memtable memtable = new Memtable(1024 * 1024);
        try (WAL wal = new WAL(walFilePath)) {
            List<byte[]> payloads = wal.recover();
            for (byte[] payload : payloads) {
                WALEntry entry = WALCodec.decode(payload);
                if (entry.isPut()) {
                    memtable.put(entry.key(), entry.value());
                } else if (entry.isDelete()) {
                    memtable.delete(entry.key());
                }
            }
        }
        return memtable;
    }

    @Test
    @DisplayName("Basic round-trip through WAL: 3 PUTs survive recovery")
    void basicRoundTrip() throws Exception {
        // Write path
        appendEntries(WALCodec.OP_PUT, "user:1", "Alice");
        appendEntries(WALCodec.OP_PUT, "user:2", "Bob");
        appendEntries(WALCodec.OP_PUT, "user:3", "Charlie");

        // Simulate crash: discard old memtable, create new via recovery
        Memtable recovered = recoverToNewMemtable();

        assertEquals("Alice", recovered.get("user:1"));
        assertEquals("Bob", recovered.get("user:2"));
        assertEquals("Charlie", recovered.get("user:3"));
        assertEquals(3, recovered.size());
    }

    @Test
    @DisplayName("Deletes survive recovery")
    void deletesSurviveRecovery() throws Exception {
        appendEntries(WALCodec.OP_PUT, "a", "1");
        appendEntries(WALCodec.OP_PUT, "b", "2");
        appendEntries(WALCodec.OP_DELETE, "a", ""); // value ignored

        Memtable recovered = recoverToNewMemtable();

        assertSame(Memtable.TOMBSTONE, recovered.get("a"), "Key 'a' should be tombstone");
        assertEquals("2", recovered.get("b"));
        assertEquals(2, recovered.size()); // 'a' tombstone + 'b' live entry
    }

    @Test
    @DisplayName("Overwrites resolve correctly during recovery")
    void overwritesResolve() throws Exception {
        appendEntries(WALCodec.OP_PUT, "x", "old");
        appendEntries(WALCodec.OP_PUT, "x", "new");

        Memtable recovered = recoverToNewMemtable();

        assertEquals("new", recovered.get("x"));
        assertEquals(1, recovered.size());
    }

    @Test
    @DisplayName("Append after recovery: WAL remains appendable")
    void appendAfterRecovery() throws Exception {
        // First session: append two entries
        appendEntries(WALCodec.OP_PUT, "key1", "value1");
        appendEntries(WALCodec.OP_PUT, "key2", "value2");

        // Close (simulated crash/restart) - already closed by helper
        // Reopen and recover, then append one more entry
        try (WAL wal = new WAL(walFilePath)) {
            List<byte[]> payloads = wal.recover();
            assertEquals(2, payloads.size());

            // Append third entry
            byte[] payload = WALCodec.encode(WALCodec.OP_PUT, "key3", "value3");
            wal.append(payload);
        } // closes

        // Reopen again and recover all three
        Memtable recovered = recoverToNewMemtable();
        assertEquals("value1", recovered.get("key1"));
        assertEquals("value2", recovered.get("key2"));
        assertEquals("value3", recovered.get("key3"));
        assertEquals(3, recovered.size());
    }

    @Test
    @DisplayName("Idempotent replay: applying WAL to already-populated Memtable")
    void idempotentReplay() throws Exception {
        // Write entries to WAL
        appendEntries(WALCodec.OP_PUT, "k1", "v1");
        appendEntries(WALCodec.OP_PUT, "k2", "v2");

        // Create a Memtable that already has the data (simulates crash after flush but before WAL truncate)
        Memtable existing = new Memtable(1024 * 1024);
        existing.put("k1", "v1");
        existing.put("k2", "v2");

        // Now replay WAL into it
        try (WAL wal = new WAL(walFilePath)) {
            List<byte[]> payloads = wal.recover();
            for (byte[] payload : payloads) {
                WALEntry entry = WALCodec.decode(payload);
                if (entry.isPut()) {
                    existing.put(entry.key(), entry.value());
                } else if (entry.isDelete()) {
                    existing.delete(entry.key());
                }
            }
        }

        assertEquals("v1", existing.get("k1"));
        assertEquals("v2", existing.get("k2"));
        assertEquals(2, existing.size());
    }

    @Test
    @DisplayName("Empty WAL recovery yields empty Memtable")
    void emptyWalRecovery() throws Exception {
        // Create WAL file but do not append anything
        try (WAL wal = new WAL(walFilePath)) {
            // just open and close
        }

        Memtable recovered = recoverToNewMemtable();

        assertEquals(0, recovered.size());
        assertNull(recovered.get("anyKey"));
    }
}