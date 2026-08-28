package lsmdb.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

/**
 * Merges several sorted SSTables into one, keeping only the newest version of
 * each key. This is a standalone class (rather than methods on StorageEngine)
 * specifically so the merge algorithm can be unit tested against SSTableReaders
 * directly, with no WAL/Memtable/StorageEngine involved.
 */

public class Compactor {

    private final int sparseIndexInterval;

    public Compactor(int sparseIndexInterval) {
        this.sparseIndexInterval = sparseIndexInterval;
    }

    public Compactor() {
        this(64);
    }

    /**
     * Merges {@code inputs} into a single new SSTable at {@code outputPath}
     * and returns a reader opened on it.
     *
     * @param inputs          SSTables to merge, ordered NEWEST FIRST (index 0 =
     *                        newest). This ordering is what the merge's
     *                        tie-breaking relies on - see {@link #mergeSorted}.
     * @param outputPath      where to write the merged SSTable
     * @param purgeTombstones if true, tombstones are dropped from the output
     *                        instead of written. Only safe when {@code inputs}
     *                        is every SSTable the engine has - see spec Step 3.
     */
    public SSTableReader compact(List<SSTableReader> inputs, Path outputPath, boolean purgeTombstones)
            throws IOException {
        Iterator<SSTableReader.SSTableEntry> merged = mergeSorted(inputs, purgeTombstones);
        new SSTableWriter(sparseIndexInterval).write(outputPath, merged);
        return new SSTableReader(outputPath);
    }

    /**
     * The k-way merge itself, exposed separately from {@link #compact} so it
     * can be tested in memory without touching disk for the output file.
     *
     * Returns entries in ascending key order, at most one per key, representing
     * the newest version found across all inputs (with stale older versions of
     * the same key discarded).
     */
    public Iterator<SSTableReader.SSTableEntry> mergeSorted(List<SSTableReader> inputs, boolean purgeTombstones) {
        // Min-heap ordered by (key, sstableIndex). For a given key, the entry
        // from the lowest sstableIndex (i.e. the newest SSTable, since inputs
        // is newest-first) always sorts first - that's the tie-breaker the
        // spec describes, and it's what lets computeNext() below simply keep
        // the first version of each key it sees and discard the rest.
        PriorityQueue<MergeEntry> heap = new PriorityQueue<>(
                Comparator.<MergeEntry, String>comparing(m -> m.entry.key())
                        .thenComparingInt(m -> m.sstableIndex));

        // Seed the heap with one entry (the first) from each input SSTable.
        for (int i = 0; i < inputs.size(); i++) {
            Iterator<SSTableReader.SSTableEntry> source = inputs.get(i).iterator();
            if (source.hasNext()) {
                heap.add(new MergeEntry(source.next(), i, source));
            }
        }

        return new MergeIterator(heap, purgeTombstones);
    }

    /** One in-flight candidate in the merge: an entry, which SSTable it came from, and how to get the next one. */
    private record MergeEntry(SSTableReader.SSTableEntry entry, int sstableIndex,
                              Iterator<SSTableReader.SSTableEntry> source) {
    }

    /**
     * Lazily drains the heap, one output entry at a time, applying
     * deduplication and (optionally) tombstone purging along the way.
     * This is a standard "lookahead" iterator: since finding the next entry
     * to emit might require polling several heap entries in a row (skipping
     * duplicates, skipping a purged tombstone), we can't decide hasNext()
     * without doing that work - so we do it eagerly and cache the result.
     */
    private static final class MergeIterator implements Iterator<SSTableReader.SSTableEntry> {
        private final PriorityQueue<MergeEntry> heap;
        private final boolean purgeTombstones;

        // The last key we made a final decision on (emit or purge). Any later
        // heap entry with this same key is a stale, older version - skip it.
        private String lastKeyDecided = null;

        private SSTableReader.SSTableEntry peeked;
        private boolean peekedValid = false;

        MergeIterator(PriorityQueue<MergeEntry> heap, boolean purgeTombstones) {
            this.heap = heap;
            this.purgeTombstones = purgeTombstones;
        }

        @Override
        public boolean hasNext() {
            if (!peekedValid) {
                peeked = computeNext();
                peekedValid = true;
            }
            return peeked != null;
        }

        @Override
        public SSTableReader.SSTableEntry next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            peekedValid = false;
            SSTableReader.SSTableEntry result = peeked;
            peeked = null;
            return result;
        }

        /**
         * Advances through the heap until it finds an entry worth emitting,
         * or the heap runs dry. Mirrors spec Step 2's algorithm exactly.
         */
        private SSTableReader.SSTableEntry computeNext() {
            while (!heap.isEmpty()) {
                MergeEntry top = heap.poll();

                // Keep that SSTable's iterator moving: push its next entry (if any).
                if (top.source().hasNext()) {
                    heap.add(new MergeEntry(top.source().next(), top.sstableIndex(), top.source()));
                }

                String key = top.entry().key();
                if (key.equals(lastKeyDecided)) {
                    // We already decided this key's fate (from a newer SSTable) -
                    // this is a stale older version. Discard and keep looking.
                    continue;
                }
                lastKeyDecided = key;

                if (top.entry().isTombstone() && purgeTombstones) {
                    // Case 1 from spec Step 3: compacting everything, so no older
                    // SSTable is left for this tombstone to shadow. Drop it -
                    // but lastKeyDecided is already set, so older versions of
                    // this same key (if any remain in the heap) still get skipped.
                    continue;
                }

                return top.entry();
            }
            return null;
        }
    }
}