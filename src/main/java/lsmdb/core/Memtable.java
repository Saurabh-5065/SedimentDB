package lsmdb.core;

import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.Objects;

/**
 * Sorted in-memory key-value store with size tracking.
 * Keys are ordered naturally via TreeMap.
 * Deleted keys are represented by a unique TOMBSTONE sentinel.
 */
public class Memtable {
    /**
     * Unique tombstone sentinel. Created with new String deliberately so that
     * identity comparison (==) can distinguish it from a user‑stored string
     * that happens to have the same characters.
     */
    public static final String TOMBSTONE = new String("__TOMBSTONE__");

    /** Approximate per‑entry overhead for TreeMap node and String objects. */
    private static final long OVERHEAD = 128; // bytes

    private final TreeMap<String, String> data;
    private final long maxSizeBytes;
    private long currentSizeBytes;

    /**
     * @param maxSizeBytes approximate memory threshold at which {@link #shouldFlush()} returns true
     */
    public Memtable(long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
        this.data = new TreeMap<>();
        this.currentSizeBytes = 0;
    }

    /**
     * Inserts or updates a key‑value pair.
     *
     * @param key   non‑null key
     * @param value non‑null value (may be {@link #TOMBSTONE} for deletes)
     */
    public void put(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");

        // If key already exists, subtract the size of the old entry
        String oldValue = data.get(key);
        if (oldValue != null) {
            if (oldValue == TOMBSTONE) {
                // Tombstones only counted key + overhead (no value length)
                currentSizeBytes -= (key.length() * 2L + OVERHEAD);
            } else {
                currentSizeBytes -= entrySize(key, oldValue);
            }
        }

        // Add the new entry's size
        currentSizeBytes += entrySize(key, value);
        data.put(key, value);
    }


    /**
     * Marks a key as deleted by inserting the TOMBSTONE sentinel.
     * Uses more accurate size estimation - tombstones don't count
     * the TOMBSTONE string length.
     */
    public void delete(String key) {
        Objects.requireNonNull(key, "key");

        // If key already exists, subtract the size of the old entry.
        String oldValue = data.get(key);
        if (oldValue != null) {
            currentSizeBytes -= entrySize(key, oldValue);
        }

        // Tombstone size: just key + overhead (TOMBSTONE sentinel is shared constant)
        currentSizeBytes += key.length() * 2L + OVERHEAD;
        data.put(key, TOMBSTONE);
    }


    /**
     * Retrieves the current value associated with a key.
     *
     * @return
     * <ul>
     *   <li>{@code null} – key is not present in this Memtable (caller should search further)</li>
     *   <li>{@link #TOMBSTONE} – key was explicitly deleted (caller should stop searching)</li>
     *   <li>any other String – the actual stored value</li>
     * </ul>
     */
    public String get(String key) {
        return data.get(key);
    }

    /**
     * @return true if the accumulated estimated size has reached the flush threshold
     */
    public boolean shouldFlush() {
        return currentSizeBytes >= maxSizeBytes;
    }

    /**
     * Returns an iterator over all entries (including tombstones) in sorted key order.
     * The iterator is live: modifications to the Memtable may be reflected unpredictably,
     * so the caller should copy or finish using it before further writes.
     */
    public Iterator<Map.Entry<String, String>> iterator() {
        return data.entrySet().iterator();
    }

    /**
     * @return number of entries, including tombstones
     */
    public int size() {
        return data.size();
    }

    /**
     * @return current estimated memory usage in bytes
     */
    public long getCurrentSizeBytes() {
        return currentSizeBytes;
    }

    /**
     * Removes all entries and resets the size counter.
     * Typically called after a successful flush to SSTable.
     */
    public void clear() {
        data.clear();
        currentSizeBytes = 0;
    }

    /**
     * Estimates the memory footprint of a single entry.
     * Java characters are 2 bytes (UTF‑16), hence the factor 2.
     * The {@code OVERHEAD} constant accounts for the TreeMap node and String object headers.
     */
    private long entrySize(String key, String value) {
        return key.length() * 2L + value.length() * 2L + OVERHEAD;
    }
}