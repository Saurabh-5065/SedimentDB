package lsmdb.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for StorageEngine: WAL + Memtable + SSTables wired together.
 * Tests the full read/write/flush/recovery lifecycle.
 */
class StorageEngineTest {

    @TempDir
    Path tempDir;

    private static final long SMALL_MEMTABLE = 200;   // bytes — triggers flush quickly
    private static final long LARGE_MEMTABLE = 10_000_000; // 10MB — won't flush during test
    private static final int INDEX_INTERVAL = 16;

    // ───────────────────── helpers ─────────────────────

    private StorageEngine openEngine(long memtableMax) throws IOException {
        return StorageEngine.open(tempDir, memtableMax, INDEX_INTERVAL);
    }

    private long countSSTFiles() throws IOException {
        try (Stream<Path> files = Files.list(tempDir)) {
            return files.filter(p -> p.toString().endsWith(".sst")).count();
        }
    }

    // ───────── Test 9: Put then get (no flush) ─────────

    @Test
    @DisplayName("Put then get — served from Memtable, no SSTable involved")
    void putThenGet_noFlush() throws IOException {
        try (StorageEngine engine = openEngine(LARGE_MEMTABLE)) {
            engine.put("key1", "value1");
            engine.put("key2", "value2");

            assertEquals("value1", engine.get("key1"));
            assertEquals("value2", engine.get("key2"));
            assertNull(engine.get("missing"));

            assertEquals(0, countSSTFiles(), "No SSTable should exist yet");
        }
    }

    // ───────── Test 10: Put, flush, then get (from SSTable) ─────────

    @Test
    @DisplayName("Data survives flush — served from SSTable after Memtable clears")
    void putFlushGet() throws IOException {
        try (StorageEngine engine = openEngine(SMALL_MEMTABLE)) {
            // Write enough to trigger at least one flush
            for (int i = 0; i < 20; i++) {
                engine.put("key" + i, "value" + i);
            }

            assertTrue(countSSTFiles() > 0, "At least one SSTable should have been created");

            // All keys should still be retrievable (from SSTable or Memtable)
            for (int i = 0; i < 20; i++) {
                assertEquals("value" + i, engine.get("key" + i),
                        "Key 'key" + i + "' should be retrievable after flush");
            }
        }
    }

    // ───────── Test 11: Delete after flush ─────────

    @Test
    @DisplayName("Delete in Memtable shadows value in SSTable")
    void deleteAfterFlush() throws IOException {
        try (StorageEngine engine = openEngine(SMALL_MEMTABLE)) {
            // Write enough to force a flush
            engine.put("target", "original_value");
            for (int i = 0; i < 20; i++) {
                engine.put("pad" + i, "x".repeat(50));
            }

            assertTrue(countSSTFiles() > 0, "Should have flushed at least once");

            // Now delete the target key — tombstone goes to Memtable
            engine.delete("target");

            // Get should return null — tombstone in Memtable shadows SSTable value
            assertNull(engine.get("target"),
                    "Deleted key should return null even though it exists in SSTable");
        }
    }

    // ───────── Test 12: Delete, flush delete, then get ─────────

    @Test
    @DisplayName("Tombstone in newer SSTable shadows value in older SSTable")
    void deleteFlushThenGet() throws IOException {
        try (StorageEngine engine = openEngine(SMALL_MEMTABLE)) {
            // First: write the key and force a flush
            engine.put("victim", "alive");
            for (int i = 0; i < 20; i++) {
                engine.put("filler1_" + i, "x".repeat(50));
            }

            // Key is now in SSTable 1

            // Delete the key and force another flush
            engine.delete("victim");
            for (int i = 0; i < 20; i++) {
                engine.put("filler2_" + i, "x".repeat(50));
            }

            // Tombstone is now in SSTable 2 (newer)

            assertTrue(countSSTFiles() >= 1, "Should have at least 1 SSTable");

            // The tombstone should shadow the value regardless of whether
            // the SSTables were compacted into one or remain separate.
            assertNull(engine.get("victim"),
                    "Tombstone should shadow value (even after compaction)");
        }
    }

    // ───────── Test 13: Overwrite across SSTables ─────────

    @Test
    @DisplayName("Newer SSTable value shadows older SSTable value")
    void overwriteAcrossSSTables() throws IOException {
        try (StorageEngine engine = openEngine(SMALL_MEMTABLE)) {
            // Write "old" and flush
            engine.put("key", "old");
            for (int i = 0; i < 20; i++) {
                engine.put("pad1_" + i, "x".repeat(50));
            }

            // Write "new" and flush
            engine.put("key", "new");
            for (int i = 0; i < 20; i++) {
                engine.put("pad2_" + i, "x".repeat(50));
            }

            assertTrue(countSSTFiles() >= 1);
            assertEquals("new", engine.get("key"),
                    "Newer value should shadow older one (even after compaction)");
        }
    }

    // ───────── Test 14: Recovery with SSTables ─────────

    @Test
    @DisplayName("Close and reopen recovers all data from SSTables + WAL")
    void recoveryWithSSTables() throws IOException {
        // Session 1: write data, some flushed, some not
        try (StorageEngine engine = openEngine(SMALL_MEMTABLE)) {
            // These will trigger flushes (go to SSTables)
            for (int i = 0; i < 20; i++) {
                engine.put("flushed" + i, "value" + i);
            }
            // This write should remain in the WAL (unflushed)
            engine.put("unflushed", "still_here");
        }

        // Session 2: reopen — should recover everything
        try (StorageEngine engine = openEngine(SMALL_MEMTABLE)) {
            // Flushed keys — loaded from SSTables
            for (int i = 0; i < 20; i++) {
                assertEquals("value" + i, engine.get("flushed" + i),
                        "Flushed key 'flushed" + i + "' should survive restart");
            }

            // Unflushed key — recovered from WAL replay
            assertEquals("still_here", engine.get("unflushed"),
                    "Unflushed key should be recovered from WAL");
        }
    }

    // ───────── Test 15: Multiple SSTables accumulate ─────────

    @Test
    @DisplayName("Multiple flushes create multiple SSTables, all keys retrievable")
    void multipleSSTables() throws IOException {
        try (StorageEngine engine = openEngine(SMALL_MEMTABLE)) {
            // Write enough to create several SSTables
            for (int i = 0; i < 100; i++) {
                engine.put("key" + String.format("%03d", i), "value" + i);
            }

            long sstCount = countSSTFiles();
            assertTrue(sstCount >= 1, "Should have at least 1 SSTable, got " + sstCount);

            // Every key should be retrievable
            for (int i = 0; i < 100; i++) {
                assertEquals("value" + i, engine.get("key" + String.format("%03d", i)));
            }
        }
    }

    // ───────── Test: Delete of nonexistent key ─────────

    @Test
    @DisplayName("Deleting a key that never existed returns null on get")
    void deleteNonexistent() throws IOException {
        try (StorageEngine engine = openEngine(LARGE_MEMTABLE)) {
            engine.delete("ghost");
            assertNull(engine.get("ghost"));
        }
    }

    // ───────── Test: Empty engine ─────────

    @Test
    @DisplayName("Fresh engine with no writes returns null for any key")
    void emptyEngine() throws IOException {
        try (StorageEngine engine = openEngine(LARGE_MEMTABLE)) {
            assertNull(engine.get("anything"));
            assertEquals(0, countSSTFiles());
        }
    }

    // ───────── Test: Reopen empty engine ─────────

    @Test
    @DisplayName("Opening a previously opened but empty directory works cleanly")
    void reopenEmptyEngine() throws IOException {
        try (StorageEngine engine = openEngine(LARGE_MEMTABLE)) {
            // just open and close
        }
        // Reopen — no crash, no corruption
        try (StorageEngine engine = openEngine(LARGE_MEMTABLE)) {
            assertNull(engine.get("anything"));
        }
    }

    // ───────── Test: Recovery preserves deletes ─────────

    @Test
    @DisplayName("Deletes in WAL are replayed correctly on recovery")
    void recoveryPreservesDeletes() throws IOException {
        try (StorageEngine engine = openEngine(SMALL_MEMTABLE)) {
            engine.put("keep", "yes");
            for (int i = 0; i < 20; i++) {
                engine.put("pad" + i, "x".repeat(50));
            }
            // "keep" should be in an SSTable now

            // Delete it — tombstone goes to WAL + Memtable
            engine.delete("keep");
            // Don't flush — tombstone is only in WAL
        }

        // Reopen — WAL replay should re-apply the delete
        try (StorageEngine engine = openEngine(SMALL_MEMTABLE)) {
            assertNull(engine.get("keep"),
                    "Delete from WAL should be replayed on recovery, shadowing SSTable value");
        }
    }

    // ───────── Test: Put, delete, put same key ─────────

    @Test
    @DisplayName("Put-delete-put cycle returns the final value")
    void putDeletePutCycle() throws IOException {
        try (StorageEngine engine = openEngine(LARGE_MEMTABLE)) {
            engine.put("key", "first");
            engine.delete("key");
            engine.put("key", "second");

            assertEquals("second", engine.get("key"));
        }
    }

    // ───────── Test: Many overwrites of same key ─────────

    @Test
    @DisplayName("1000 overwrites of the same key: only the last value visible")
    void manyOverwrites() throws IOException {
        try (StorageEngine engine = openEngine(SMALL_MEMTABLE)) {
            for (int i = 0; i < 1000; i++) {
                engine.put("counter", String.valueOf(i));
            }
            assertEquals("999", engine.get("counter"));
        }
    }
}
