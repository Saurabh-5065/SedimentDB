package lsmdb.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SSTableWriter + SSTableReader (no StorageEngine, no WAL).
 * Exercises the on-disk format, sparse index, point lookup, iteration,
 * tombstones, and corruption detection.
 */
class SSTableTest {

    @TempDir
    Path tempDir;

    // ───────────────────── helpers ─────────────────────

    /** Builds a Memtable from key-value pairs. Use Memtable.TOMBSTONE as value for deletes. */
    private Memtable buildMemtable(String... kvPairs) {
        Memtable m = new Memtable(Long.MAX_VALUE);
        for (int i = 0; i < kvPairs.length; i += 2) {
            String key = kvPairs[i];
            String value = kvPairs[i + 1];
            if (value == Memtable.TOMBSTONE) {
                m.delete(key);
            } else {
                m.put(key, value);
            }
        }
        return m;
    }

    private Path writeSSTable(Memtable m) throws IOException {
        return writeSSTable(m, 64);
    }

    private Path writeSSTable(Memtable m, int indexInterval) throws IOException {
        Path file = tempDir.resolve("test.sst");
        new SSTableWriter(indexInterval).write(file, m);
        return file;
    }

    // ───────────── Test 1: Single entry round-trip ──────────────

    @Test
    @DisplayName("Single entry: write and read back")
    void singleEntry() throws IOException {
        Memtable m = buildMemtable("key1", "value1");
        Path file = writeSSTable(m);

        try (SSTableReader reader = new SSTableReader(file)) {
            assertEquals("value1", reader.get("key1"));
            assertNull(reader.get("nonexistent"));
            assertEquals(1, reader.entryCount());
        }
    }

    // ───────────── Test 2: Multiple entries ──────────────

    @Test
    @DisplayName("Multiple entries: all retrievable, missing keys return null")
    void multipleEntries() throws IOException {
        Memtable m = buildMemtable("a", "1", "b", "2", "c", "3", "d", "4", "e", "5");
        Path file = writeSSTable(m);

        try (SSTableReader reader = new SSTableReader(file)) {
            assertEquals("1", reader.get("a"));
            assertEquals("2", reader.get("b"));
            assertEquals("3", reader.get("c"));
            assertEquals("4", reader.get("d"));
            assertEquals("5", reader.get("e"));

            // Keys between entries
            assertNull(reader.get("a5"), "Key between 'a' and 'b' should be null");
            assertNull(reader.get("b5"), "Key between 'b' and 'c' should be null");

            // Keys outside range
            assertNull(reader.get("0"), "Key before all entries should be null");
            assertNull(reader.get("z"), "Key after all entries should be null");

            assertEquals(5, reader.entryCount());
        }
    }

    // ───────────── Test 3: Tombstone on disk ──────────────

    @Test
    @DisplayName("Tombstone entries are written and read correctly")
    void tombstoneOnDisk() throws IOException {
        Memtable m = new Memtable(Long.MAX_VALUE);
        m.put("alive", "value");
        m.delete("dead");
        m.put("also_alive", "value2");

        Path file = writeSSTable(m);

        try (SSTableReader reader = new SSTableReader(file)) {
            assertEquals("value", reader.get("alive"));
            assertSame(Memtable.TOMBSTONE, reader.get("dead"),
                    "Deleted key should return TOMBSTONE sentinel");
            assertEquals("value2", reader.get("also_alive"));
            assertEquals(3, reader.entryCount());
        }
    }

    // ───────────── Test 4: Sparse index correctness ──────────────

    @Test
    @DisplayName("1000 entries with index interval 16: all lookups correct")
    void sparseIndexCorrectness() throws IOException {
        Memtable m = new Memtable(Long.MAX_VALUE);
        for (int i = 0; i < 1000; i++) {
            m.put(String.format("key%04d", i), "value" + i);
        }

        Path file = writeSSTable(m, 16);

        try (SSTableReader reader = new SSTableReader(file)) {
            // Verify index entry count: ceil(1000/16) = 63
            assertEquals(63, reader.indexEntryCount(),
                    "Should have ceil(1000/16)=63 index entries");
            assertEquals(1000, reader.entryCount());

            // Lookup every key
            for (int i = 0; i < 1000; i++) {
                String key = String.format("key%04d", i);
                assertEquals("value" + i, reader.get(key),
                        "Lookup failed for " + key);
            }

            // Lookup keys between entries
            assertNull(reader.get("key0000x"), "Key between entries should be null");
            assertNull(reader.get("key0500x"), "Key between entries should be null");
        }
    }

    // ───────────── Test 5: Large values ──────────────

    @Test
    @DisplayName("100KB value is stored and retrieved intact")
    void largeValue() throws IOException {
        String largeValue = "x".repeat(100_000);
        Memtable m = buildMemtable("big", largeValue);
        Path file = writeSSTable(m);

        try (SSTableReader reader = new SSTableReader(file)) {
            String retrieved = reader.get("big");
            assertEquals(100_000, retrieved.length());
            assertEquals(largeValue, retrieved);
        }
    }

    // ───────────── Test 6: Magic number validation ──────────────

    @Test
    @DisplayName("Corrupt magic number causes reader to reject the file")
    void corruptMagicNumber() throws IOException {
        Memtable m = buildMemtable("key", "value");
        Path file = writeSSTable(m);

        // Corrupt the last 4 bytes (the magic number)
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(raf.length() - 4);
            raf.writeInt(0xDEADBEEF);
        }

        assertThrows(IOException.class, () -> new SSTableReader(file),
                "Should reject file with corrupt magic number");
    }

    // ───────────── Test 7: Empty Memtable ──────────────

    @Test
    @DisplayName("Flushing an empty Memtable produces a valid but empty SSTable")
    void emptyMemtable() throws IOException {
        Memtable m = new Memtable(Long.MAX_VALUE);
        // Don't add any entries
        Path file = writeSSTable(m);

        try (SSTableReader reader = new SSTableReader(file)) {
            assertEquals(0, reader.entryCount());
            assertEquals(0, reader.indexEntryCount());
            assertNull(reader.get("anything"));
        }
    }

    // ───────────── Test 8: Iterator returns all entries in order ──────────────

    @Test
    @DisplayName("Iterator yields all entries in sorted order including tombstones")
    void iteratorOrder() throws IOException {
        Memtable m = new Memtable(Long.MAX_VALUE);
        m.put("c", "3");
        m.put("a", "1");
        m.delete("b");
        m.put("d", "4");

        Path file = writeSSTable(m);

        try (SSTableReader reader = new SSTableReader(file)) {
            List<SSTableReader.SSTableEntry> entries = new ArrayList<>();
            Iterator<SSTableReader.SSTableEntry> it = reader.iterator();
            while (it.hasNext()) {
                entries.add(it.next());
            }

            assertEquals(4, entries.size());

            // Check sorted order
            assertEquals("a", entries.get(0).key());
            assertEquals("1", entries.get(0).value());
            assertFalse(entries.get(0).isTombstone());

            assertEquals("b", entries.get(1).key());
            assertTrue(entries.get(1).isTombstone());
            assertNull(entries.get(1).value());

            assertEquals("c", entries.get(2).key());
            assertEquals("3", entries.get(2).value());

            assertEquals("d", entries.get(3).key());
            assertEquals("4", entries.get(3).value());
        }
    }

    // ───────────── Test: Unicode keys/values on disk ──────────────

    @Test
    @DisplayName("Unicode keys and values survive SSTable round-trip")
    void unicodeRoundTrip() throws IOException {
        Memtable m = buildMemtable(
                "用户:1", "こんにちは",
                "emoji🔑", "value💾",
                "Ключ", "Значение"
        );
        Path file = writeSSTable(m);

        try (SSTableReader reader = new SSTableReader(file)) {
            assertEquals("こんにちは", reader.get("用户:1"));
            assertEquals("value💾", reader.get("emoji🔑"));
            assertEquals("Значение", reader.get("Ключ"));
        }
    }

    // ───────────── Test: File too small to be SSTable ──────────────

    @Test
    @DisplayName("File smaller than footer size is rejected")
    void fileTooSmall() throws IOException {
        Path file = tempDir.resolve("tiny.sst");
        Files.write(file, new byte[10]); // 10 bytes < 28-byte footer

        assertThrows(IOException.class, () -> new SSTableReader(file));
    }

    // ───────────── Test: Sparse index with interval=1 ──────────────

    @Test
    @DisplayName("Index interval 1 indexes every key")
    void indexIntervalOne() throws IOException {
        Memtable m = buildMemtable("a", "1", "b", "2", "c", "3", "d", "4", "e", "5");
        Path file = writeSSTable(m, 1);

        try (SSTableReader reader = new SSTableReader(file)) {
            assertEquals(5, reader.indexEntryCount(), "Every key should be indexed");
            assertEquals("1", reader.get("a"));
            assertEquals("5", reader.get("e"));
        }
    }

    // ───────────── Test: Sparse index with very large interval ──────────────

    @Test
    @DisplayName("Index interval larger than entry count: only first key indexed")
    void indexIntervalLargerThanEntries() throws IOException {
        Memtable m = buildMemtable("a", "1", "b", "2", "c", "3");
        Path file = writeSSTable(m, 1000);

        try (SSTableReader reader = new SSTableReader(file)) {
            assertEquals(1, reader.indexEntryCount(), "Only first key should be indexed");
            // All lookups should still work (scan from the one indexed position)
            assertEquals("1", reader.get("a"));
            assertEquals("2", reader.get("b"));
            assertEquals("3", reader.get("c"));
            assertNull(reader.get("d"));
        }
    }
}
