package lsmdb.core;

// These tests use JUnit 5 (Jupiter). If it's not already on your classpath,
// add the junit-jupiter dependency (and junit-platform-console-standalone or
// your build tool's test runner) to run them.

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the Compactor's k-way merge in isolation: every input SSTable here is
 * built directly from a Memtable, and the output is inspected directly via
 * SSTableReader. No WAL, no StorageEngine - just the merge algorithm.
 */
class CompactorTest {

    // Small on purpose, so the sparse index is actually exercised (multiple
    // buckets) even with the modest entry counts most of these tests use.
    private static final int SPARSE_INDEX_INTERVAL = 4;

    @TempDir
    Path tempDir;

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Builds one SSTable file from a set of puts and deletes (tombstones), via a single Memtable flush. */
    private SSTableReader writeSSTable(String fileName, Map<String, String> puts, Set<String> deletes)
            throws IOException {
        Memtable memtable = new Memtable(Long.MAX_VALUE); // large threshold - never auto-flushes here
        for (Map.Entry<String, String> entry : puts.entrySet()) {
            memtable.put(entry.getKey(), entry.getValue());
        }
        for (String key : deletes) {
            memtable.delete(key);
        }
        Path path = tempDir.resolve(fileName);
        new SSTableWriter(SPARSE_INDEX_INTERVAL).write(path, memtable);
        return new SSTableReader(path);
    }

    private SSTableReader writeSSTable(String fileName, Map<String, String> puts) throws IOException {
        return writeSSTable(fileName, puts, Set.of());
    }

    private static List<SSTableReader.SSTableEntry> collect(SSTableReader reader) {
        List<SSTableReader.SSTableEntry> result = new ArrayList<>();
        Iterator<SSTableReader.SSTableEntry> it = reader.iterator();
        while (it.hasNext()) {
            result.add(it.next());
        }
        return result;
    }

    private static void closeAll(SSTableReader... readers) throws IOException {
        for (SSTableReader r : readers) {
            r.close();
        }
    }

    // ---------------------------------------------------------------------
    // Test 1 - basic merge of two non-overlapping SSTables
    // ---------------------------------------------------------------------
    @Test
    void basicMergeOfTwoSSTables() throws IOException {
        SSTableReader a = writeSSTable("a.sst", Map.of("a", "1", "c", "3", "e", "5"));
        SSTableReader b = writeSSTable("b.sst", Map.of("b", "2", "d", "4"));

        Path outputPath = tempDir.resolve("merged.sst");
        // B is newer, so it goes first in the newest-first input list.
        SSTableReader output = new Compactor(SPARSE_INDEX_INTERVAL).compact(List.of(b, a), outputPath, true);

        List<SSTableReader.SSTableEntry> entries = collect(output);
        assertEquals(5, entries.size());
        assertEquals(List.of("a", "b", "c", "d", "e"),
                entries.stream().map(SSTableReader.SSTableEntry::key).toList());

        assertEquals("1", output.get("a"));
        assertEquals("2", output.get("b"));
        assertEquals("3", output.get("c"));
        assertEquals("4", output.get("d"));
        assertEquals("5", output.get("e"));

        closeAll(a, b, output);
    }

    // ---------------------------------------------------------------------
    // Test 2 - duplicate key resolution: newer SSTable wins
    // ---------------------------------------------------------------------
    @Test
    void duplicateKeyResolutionNewerWins() throws IOException {
        SSTableReader a = writeSSTable("a.sst", Map.of("x", "old")); // older
        SSTableReader b = writeSSTable("b.sst", Map.of("x", "new")); // newer

        Path outputPath = tempDir.resolve("merged.sst");
        SSTableReader output = new Compactor(SPARSE_INDEX_INTERVAL).compact(List.of(b, a), outputPath, true);

        List<SSTableReader.SSTableEntry> entries = collect(output);
        assertEquals(1, entries.size());
        assertEquals("new", entries.get(0).value());
        assertEquals("new", output.get("x"));

        closeAll(a, b, output);
    }

    // ---------------------------------------------------------------------
    // Test 3 - tombstone purging when compacting ALL SSTables
    // ---------------------------------------------------------------------
    @Test
    void tombstonePurgingWhenCompactingAll() throws IOException {
        SSTableReader a = writeSSTable("a.sst", Map.of("a", "1", "b", "2")); // older
        SSTableReader b = writeSSTable("b.sst", Map.of(), Set.of("b"));      // newer, tombstones "b"

        Path outputPath = tempDir.resolve("merged.sst");
        SSTableReader output = new Compactor(SPARSE_INDEX_INTERVAL).compact(List.of(b, a), outputPath, true);

        List<SSTableReader.SSTableEntry> entries = collect(output);
        assertEquals(1, entries.size());
        assertEquals("a", entries.get(0).key());

        assertEquals("1", output.get("a"));
        assertNull(output.get("b")); // gone entirely - not even a tombstone remains

        closeAll(a, b, output);
    }

    // ---------------------------------------------------------------------
    // Test 4 - tombstone preservation when purging is disabled
    // ---------------------------------------------------------------------
    @Test
    void tombstonePreservationWhenNotPurging() throws IOException {
        SSTableReader a = writeSSTable("a.sst", Map.of("a", "1", "b", "2")); // older
        SSTableReader b = writeSSTable("b.sst", Map.of(), Set.of("b"));      // newer, tombstones "b"

        Path outputPath = tempDir.resolve("merged.sst");
        SSTableReader output = new Compactor(SPARSE_INDEX_INTERVAL).compact(List.of(b, a), outputPath, false);

        List<SSTableReader.SSTableEntry> entries = collect(output);
        assertEquals(2, entries.size());
        assertEquals("a", entries.get(0).key());
        assertFalse(entries.get(0).isTombstone());
        assertEquals("b", entries.get(1).key());
        assertTrue(entries.get(1).isTombstone());

        assertEquals("1", output.get("a"));
        // Identity match against Memtable.TOMBSTONE, per SSTableReader's documented contract.
        assertEquals(Memtable.TOMBSTONE, output.get("b"));

        closeAll(a, b, output);
    }

    // ---------------------------------------------------------------------
    // Test 5 - three-way merge with overlapping key "d"
    // ---------------------------------------------------------------------
    @Test
    void threeWayMerge() throws IOException {
        SSTableReader a = writeSSTable("a.sst", Map.of("a", "1", "d", "4"));   // oldest
        SSTableReader b = writeSSTable("b.sst", Map.of("b", "2", "d", "40"));  // middle
        SSTableReader c = writeSSTable("c.sst", Map.of("c", "3", "d", "400")); // newest

        Path outputPath = tempDir.resolve("merged.sst");
        SSTableReader output = new Compactor(SPARSE_INDEX_INTERVAL).compact(List.of(c, b, a), outputPath, true);

        List<SSTableReader.SSTableEntry> entries = collect(output);
        assertEquals(4, entries.size());
        assertEquals("1", output.get("a"));
        assertEquals("2", output.get("b"));
        assertEquals("3", output.get("c"));
        assertEquals("400", output.get("d")); // newest SSTable's value wins

        closeAll(a, b, c, output);
    }

    // ---------------------------------------------------------------------
    // Test 6 - purging every entry (all tombstones) yields an empty SSTable
    // ---------------------------------------------------------------------
    @Test
    void allTombstonesPurgeAllProducesEmptyOutput() throws IOException {
        SSTableReader a = writeSSTable("a.sst", Map.of("x", "value")); // older
        SSTableReader b = writeSSTable("b.sst", Map.of(), Set.of("x")); // newer, tombstones "x"

        Path outputPath = tempDir.resolve("merged.sst");
        SSTableReader output = new Compactor(SPARSE_INDEX_INTERVAL).compact(List.of(b, a), outputPath, true);

        assertEquals(0, output.entryCount());
        assertFalse(output.iterator().hasNext());
        assertNull(output.get("x"));

        closeAll(a, b, output);
    }

    // ---------------------------------------------------------------------
    // Test 7 - large merge: 1000 non-overlapping entries across 5 SSTables
    // ---------------------------------------------------------------------
    @Test
    void largeMergeAcrossFiveSSTables() throws IOException {
        List<SSTableReader> tables = new ArrayList<>();
        for (int t = 0; t < 5; t++) {
            Map<String, String> puts = new LinkedHashMap<>();
            for (int i = t * 200; i < (t + 1) * 200; i++) {
                puts.put(String.format("key%04d", i), "value" + i);
            }
            tables.add(writeSSTable("t" + t + ".sst", puts));
        }
        // No key overlap between these tables, so their relative input order
        // doesn't affect correctness here - only Tests 2/3/4/5/8 exercise that.

        Path outputPath = tempDir.resolve("merged.sst");
        SSTableReader output = new Compactor(SPARSE_INDEX_INTERVAL).compact(tables, outputPath, true);

        List<SSTableReader.SSTableEntry> entries = collect(output);
        assertEquals(1000, entries.size());

        for (int i = 1; i < entries.size(); i++) {
            assertTrue(entries.get(i - 1).key().compareTo(entries.get(i).key()) < 0,
                    "entries must be strictly increasing by key");
        }

        // Spot-check point lookups too, not just the full scan.
        assertEquals("value0", output.get("key0000"));
        assertEquals("value199", output.get("key0199"));
        assertEquals("value500", output.get("key0500"));
        assertEquals("value999", output.get("key0999"));

        for (SSTableReader t : tables) {
            t.close();
        }
        output.close();
    }

    // ---------------------------------------------------------------------
    // Test 8 - the same key overwritten across 4 SSTables: newest wins
    // ---------------------------------------------------------------------
    @Test
    void overlappingKeyAcrossManySSTablesNewestWins() throws IOException {
        SSTableReader t1 = writeSSTable("t1.sst", Map.of("counter", "1")); // oldest
        SSTableReader t2 = writeSSTable("t2.sst", Map.of("counter", "2"));
        SSTableReader t3 = writeSSTable("t3.sst", Map.of("counter", "3"));
        SSTableReader t4 = writeSSTable("t4.sst", Map.of("counter", "4")); // newest

        Path outputPath = tempDir.resolve("merged.sst");
        // Newest-first input order, matching StorageEngine's convention.
        SSTableReader output = new Compactor(SPARSE_INDEX_INTERVAL)
                .compact(List.of(t4, t3, t2, t1), outputPath, true);

        List<SSTableReader.SSTableEntry> entries = collect(output);
        assertEquals(1, entries.size());
        assertEquals("4", output.get("counter"));

        closeAll(t1, t2, t3, t4, output);
    }
}