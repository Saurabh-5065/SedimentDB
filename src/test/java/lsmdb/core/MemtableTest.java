package lsmdb.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemtableTest {

    private Memtable memtable;

    @BeforeEach
    void setUp() {
        memtable = new Memtable(1024 * 1024); // 1MB default for most tests
    }

    @Test
    @DisplayName("Basic put/get returns stored value")
    void basicPutGet() {
        memtable.put("key1", "value1");
        assertEquals("value1", memtable.get("key1"));
    }

    @Test
    @DisplayName("Overwriting a key returns the new value")
    void overwriteKey() {
        memtable.put("key1", "original");
        memtable.put("key1", "updated");

        assertEquals("updated", memtable.get("key1"));
        assertEquals(1, memtable.size(), "Should still have only one entry");
    }

    @Test
    @DisplayName("Delete produces TOMBSTONE sentinel")
    void deleteProducesTombstone() {
        memtable.put("key1", "value1");
        memtable.delete("key1");

        String result = memtable.get("key1");
        assertNotNull(result, "Deleted key should return a value, not null");
        assertSame(Memtable.TOMBSTONE, result, "Should return the exact TOMBSTONE sentinel");

        // Verify identity comparison works
        assertTrue(result == Memtable.TOMBSTONE, "Identity comparison should work");
    }

    @Test
    @DisplayName("Delete unknown key creates tombstone")
    void deleteUnknownKey() {
        memtable.delete("never-existed");

        String result = memtable.get("never-existed");
        assertSame(Memtable.TOMBSTONE, result,
                "Deleting unknown key should create a tombstone");
        assertEquals(1, memtable.size(), "Tombstone should be stored in memtable");
    }

    @Test
    @DisplayName("Re-create after delete returns new value")
    void recreateAfterDelete() {
        memtable.put("key1", "original");
        memtable.delete("key1");
        memtable.put("key1", "recreated");

        String result = memtable.get("key1");
        assertNotSame(Memtable.TOMBSTONE, result, "Should not be tombstone after re-creation");
        assertEquals("recreated", result, "Should return the new value");
        assertEquals(1, memtable.size(), "Should have only one entry");
    }

    @Test
    @DisplayName("null is distinguishable from TOMBSTONE")
    void nullVsTombstone() {
        // Never inserted key
        assertNull(memtable.get("never-inserted"), "Never inserted key should return null");

        // Deleted key
        memtable.put("deleted-key", "value");
        memtable.delete("deleted-key");

        String deletedResult = memtable.get("deleted-key");
        assertNotNull(deletedResult, "Deleted key should not return null");
        assertSame(Memtable.TOMBSTONE, deletedResult, "Deleted key should return TOMBSTONE");

        // Verify they are distinguishable
        assertNotEquals(memtable.get("never-inserted"), memtable.get("deleted-key"));
    }

    @Test
    @DisplayName("Size tracking is positive and proportional")
    void sizeTracking() {
        long initialSize = memtable.getCurrentSizeBytes();
        assertEquals(0, initialSize, "Empty memtable should have zero size");

        // Add 100 entries
        for (int i = 0; i < 100; i++) {
            memtable.put("key" + i, "value" + i);
        }

        long sizeAfter100 = memtable.getCurrentSizeBytes();
        assertTrue(sizeAfter100 > 0, "Size should be positive after adding entries");

        // Each entry is roughly "keyX" (4 chars) + "valueX" (6 chars) = 10 chars * 2 bytes = 20 bytes
        // Plus overhead of 128 bytes each = ~148 bytes per entry
        long expectedApprox = 100 * 148;
        assertTrue(Math.abs(sizeAfter100 - expectedApprox) < 1000,
                "Size should be roughly proportional. Expected ~" + expectedApprox + ", got " + sizeAfter100);

        // Overwrite should change size (key3 is 4 chars, key333 is 6 chars)
        long sizeBeforeOverwrite = memtable.getCurrentSizeBytes();
        memtable.put("key3", "value3");  // Same key
        memtable.put("key3", "different-value");  // Longer value

        long sizeAfterOverwrite = memtable.getCurrentSizeBytes();
        assertTrue(sizeAfterOverwrite > sizeBeforeOverwrite,
                "Size should increase when value gets longer");

        // Overwrite with shorter value should decrease size
        memtable.put("key3", "short");
        long sizeAfterShorter = memtable.getCurrentSizeBytes();
        assertTrue(sizeAfterShorter < sizeAfterOverwrite,
                "Size should decrease when value gets shorter");
    }

    @Test
    @DisplayName("shouldFlush returns true when size threshold reached")
    void shouldFlush() {
        Memtable smallMemtable = new Memtable(100); // Very small threshold

        assertFalse(smallMemtable.shouldFlush(), "Should not flush when empty");

        // Add entries until we cross the threshold
        int entriesAdded = 0;
        while (!smallMemtable.shouldFlush() && entriesAdded < 10) {
            smallMemtable.put("key" + entriesAdded, "value" + entriesAdded);
            entriesAdded++;
        }

        assertTrue(smallMemtable.shouldFlush(),
                "Should flush after adding " + entriesAdded + " entries");
        assertTrue(entriesAdded <= 2,
                "Should hit threshold quickly with 100 byte limit, took " + entriesAdded + " entries");
    }

    @Test
    @DisplayName("Iterator returns keys in sorted order")
    void iteratorSortedOrder() {
        memtable.put("c", "value-c");
        memtable.put("a", "value-a");
        memtable.put("b", "value-b");

        List<String> keys = new ArrayList<>();
        Iterator<Map.Entry<String, String>> iterator = memtable.iterator();
        while (iterator.hasNext()) {
            keys.add(iterator.next().getKey());
        }

        assertEquals(List.of("a", "b", "c"), keys, "Keys should be sorted");
    }

    @Test
    @DisplayName("Iterator includes tombstones in sorted order")
    void iteratorIncludesTombstones() {
        memtable.put("a", "value-a");
        memtable.put("b", "value-b");
        memtable.delete("b");
        memtable.put("c", "value-c");

        List<Map.Entry<String, String>> entries = new ArrayList<>();
        Iterator<Map.Entry<String, String>> iterator = memtable.iterator();
        while (iterator.hasNext()) {
            entries.add(iterator.next());
        }

        assertEquals(3, entries.size(), "Should have 3 entries including tombstone");

        // Check order
        assertEquals("a", entries.get(0).getKey());
        assertEquals("value-a", entries.get(0).getValue());

        assertEquals("b", entries.get(1).getKey());
        assertSame(Memtable.TOMBSTONE, entries.get(1).getValue(),
                "Entry 'b' should be a tombstone");

        assertEquals("c", entries.get(2).getKey());
        assertEquals("value-c", entries.get(2).getValue());
    }

    @Test
    @DisplayName("Clear empties memtable and resets size")
    void clear() {
        memtable.put("key1", "value1");
        memtable.put("key2", "value2");
        memtable.delete("key3");

        assertTrue(memtable.size() > 0, "Should have entries before clear");
        assertTrue(memtable.getCurrentSizeBytes() > 0, "Should have size before clear");

        memtable.clear();

        assertEquals(0, memtable.size(), "Size should be 0 after clear");
        assertEquals(0, memtable.getCurrentSizeBytes(), "Byte size should be 0 after clear");
        assertNull(memtable.get("key1"), "Keys should be gone after clear");
        assertFalse(memtable.shouldFlush(), "Should not flush after clear");
    }

    @Test
    @DisplayName("Size tracking for tombstones")
    void sizeTrackingTombstones() {
        long sizeBefore = memtable.getCurrentSizeBytes();

        memtable.put("key1", "value1");
        long sizeAfterPut = memtable.getCurrentSizeBytes();
        assertTrue(sizeAfterPut > sizeBefore);

        memtable.delete("key1");
        long sizeAfterDelete = memtable.getCurrentSizeBytes();
        assertTrue(sizeAfterDelete > 0, "Tombstone should still take space");
        assertTrue(sizeAfterDelete < sizeAfterPut,
                "Tombstone should take less space than full entry");
    }

    @Test
    @DisplayName("Size tracking for tombstones with short values")
    void sizeTrackingTombstonesWithShortValues() {
        // Even with very short values, tombstone should still be smaller
        memtable.put("a", "b");  // Very short value
        long sizeAfterPut = memtable.getCurrentSizeBytes();

        memtable.delete("a");
        long sizeAfterDelete = memtable.getCurrentSizeBytes();

        // With Option B implementation, this should always be true
        assertTrue(sizeAfterDelete < sizeAfterPut,
                "Tombstone should always be smaller than entry with any non-empty value");
    }

    @Test
    @DisplayName("Multiple operations maintain correct size")
    void multipleOperationsSize() {
        // Add 10 entries
        for (int i = 0; i < 10; i++) {
            memtable.put("key" + i, "value" + i);
        }
        long sizeAfterAdds = memtable.getCurrentSizeBytes();

        // Delete 5 entries
        for (int i = 0; i < 5; i++) {
            memtable.delete("key" + i);
        }
        long sizeAfterDeletes = memtable.getCurrentSizeBytes();
        assertTrue(sizeAfterDeletes < sizeAfterAdds,
                "Size should decrease after converting entries to tombstones");

        // Re-add 3 entries
        for (int i = 0; i < 3; i++) {
            memtable.put("key" + i, "new-value" + i);
        }
        long sizeAfterReadd = memtable.getCurrentSizeBytes();
        assertTrue(sizeAfterReadd > sizeAfterDeletes,
                "Size should increase after re-adding deleted keys");

        // Verify final count
        assertEquals(10, memtable.size(), "Should still have 10 total entries");
    }

    @Test
    @DisplayName("Unicode keys are handled correctly")
    void unicodeKeys() {
        String unicodeKey = "用户:信息";
        String unicodeValue = "こんにちは世界";

        memtable.put(unicodeKey, unicodeValue);

        assertEquals(unicodeValue, memtable.get(unicodeKey));
        assertTrue(memtable.getCurrentSizeBytes() > 0);

        // Unicode strings use more bytes in UTF-16
        long unicodeSize = memtable.getCurrentSizeBytes();
        memtable.clear();

        memtable.put("simple", "value");
        long simpleSize = memtable.getCurrentSizeBytes();

        assertTrue(unicodeSize > simpleSize,
                "Unicode strings should use more space");
    }
}