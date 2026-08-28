package lsmdb.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Public entry point that wires WAL + Memtable + SSTables together into a
 * single embedded key-value store.
 *
 * Read path (get):  Memtable -> SSTables, newest to oldest. First hit wins.
 * Write path (put/delete): WAL append (durability) -> Memtable update -> maybe flush.
 *
 * Concurrency model for Phase 1: every public method is `synchronized`. This
 * is the "block writes during flush" option from the spec, just applied a bit
 * more broadly - it also blocks reads during a flush. That's the simplest
 * thing that is obviously correct: the Memtable and the sstables list both
 * get swapped out atomically during flush(), so nothing else may observe them
 * mid-swap. Flushes are sequential I/O and should be fast, so the pause is
 * brief. Real double-buffering (swap in a new Memtable, flush the old one on
 * a background thread) is the natural next step once this becomes a
 * bottleneck - deliberately not done here.
 */
public class StorageEngine implements AutoCloseable {

    private static final String WAL_FILE_NAME = "wal.log";
    private static final String SSTABLE_SUFFIX = ".sst";
    private static final String SSTABLE_NAME_FORMAT = "%06d.sst";

    /** Compact (merge all SSTables into one) once this many SSTables have piled up. */
    private static final int COMPACTION_THRESHOLD = 4;

    private final Path dataDirectory;
    private final Path walPath;
    private final int sparseIndexInterval;
    private final Compactor compactor;

    private WAL wal;
    private Memtable memtable;

    // Ordered newest-first: sstables.get(0) is the most recently flushed table.
    private final List<SSTableReader> sstables;

    private int nextSSTableId;

    /**
     * Private - use {@link #open} to construct an engine, since opening
     * involves recovery steps (scanning for SSTables, replaying the WAL)
     * that shouldn't live in a constructor.
     */
    private StorageEngine(Path dataDirectory,
                          Path walPath,
                          WAL wal,
                          Memtable memtable,
                          List<SSTableReader> sstables,
                          int nextSSTableId,
                          int sparseIndexInterval) {
        this.dataDirectory = dataDirectory;
        this.walPath = walPath;
        this.wal = wal;
        this.memtable = memtable;
        this.sstables = sstables;
        this.nextSSTableId = nextSSTableId;
        this.sparseIndexInterval = sparseIndexInterval;
        this.compactor = new Compactor(sparseIndexInterval);
    }

    /**
     * Opens (or creates) a storage engine backed by {@code dataDirectory}.
     * This runs the full startup/recovery sequence from spec 5.4:
     *
     *   1. Scan the directory for existing *.sst files
     *   2. Sort them newest-first
     *   3. Open an SSTableReader for each
     *   4. Open the WAL (creates an empty one if this is a fresh directory)
     *   5. Replay the WAL into a fresh Memtable
     *
     * @param memtableMaxBytes    flush threshold, passed straight to Memtable
     * @param sparseIndexInterval index interval used for any *new* SSTables
     *                            this engine flushes (existing files on disk
     *                            already have their own interval baked in -
     *                            the reader doesn't need to know it)
     */
    public static StorageEngine open(Path dataDirectory,
                                     long memtableMaxBytes,
                                     int sparseIndexInterval) throws IOException {
        Files.createDirectories(dataDirectory);

        // --- 1-3: discover existing SSTables, newest first -------------------
        List<Path> sstableFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDirectory, "*" + SSTABLE_SUFFIX)) {
            for (Path p : stream) {
                sstableFiles.add(p);
            }
        }
        sstableFiles.sort(Comparator.comparingInt(StorageEngine::extractSSTableId).reversed());

        List<SSTableReader> sstables = new ArrayList<>();
        int maxId = -1;
        for (Path p : sstableFiles) {
            maxId = Math.max(maxId, extractSSTableId(p));
            sstables.add(new SSTableReader(p));
        }
        int nextSSTableId = maxId + 1;

        // --- 4: open the WAL ---------------------------------------------------
        Path walPath = dataDirectory.resolve(WAL_FILE_NAME);
        WAL wal = new WAL(walPath);

        // --- 5: replay the WAL into a fresh Memtable ----------------------------
        Memtable memtable = new Memtable(memtableMaxBytes);
        for (byte[] payload : wal.recover()) {
            WALEntry entry = WALCodec.decode(payload);
            if (entry.isPut()) {
                memtable.put(entry.key(), entry.value());
            } else {
                memtable.delete(entry.key());
            }
        }

        StorageEngine engine = new StorageEngine(
                dataDirectory, walPath, wal, memtable, sstables, nextSSTableId, sparseIndexInterval);

        // Edge case: replaying a large WAL can leave the Memtable already over
        // its flush threshold. Flush immediately rather than serving traffic
        // from an oversized, un-flushed Memtable.
        if (memtable.shouldFlush()) {
            engine.flush();
        } else if (engine.sstables.size() >= COMPACTION_THRESHOLD) {
            // flush() would normally trigger this check - if we didn't flush,
            // do it explicitly in case a prior run left extra SSTables on disk
            // (e.g. a crash between compaction's swap and delete steps).
            engine.compactIfNeeded();
        }

        return engine;
    }

    /**
     * Durably writes key -> value: WAL first (so it survives a crash), then
     * the Memtable, then flushes if the Memtable has grown past its threshold.
     */
    public synchronized void put(String key, String value) throws IOException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");

        wal.append(WALCodec.encode(WALCodec.OP_PUT, key, value));
        memtable.put(key, value);
        maybeFlush();
    }

    /**
     * Durably marks key as deleted (tombstone), following the same
     * WAL-then-Memtable order as put().
     */
    public synchronized void delete(String key) throws IOException {
        Objects.requireNonNull(key, "key");

        wal.append(WALCodec.encode(WALCodec.OP_DELETE, key, ""));
        memtable.delete(key);
        maybeFlush();
    }

    /**
     * Read path from spec 5.2: check the Memtable first, then SSTables from
     * newest to oldest, stopping at the first hit (real value or tombstone).
     * This ordering is what makes both overwrites and deletes work correctly -
     * see the class-level SSTableReader/Memtable javadoc for why.
     */
    public synchronized String get(String key) throws IOException {
        Objects.requireNonNull(key, "key");

        String memValue = memtable.get(key);
        if (memValue != null) {
            return (memValue == Memtable.TOMBSTONE) ? null : memValue;
        }

        for (SSTableReader reader : sstables) {
            String value = reader.get(key);
            if (value != null) {
                return (value == Memtable.TOMBSTONE) ? null : value;
            }
            // value == null means "not in this SSTable" - keep checking older ones.
        }

        return null;
    }

    /**
     * Flushes the current Memtable if it has reached its size threshold.
     * Called after every put()/delete() - a no-op the vast majority of the time.
     */
    private void maybeFlush() throws IOException {
        if (memtable.shouldFlush()) {
            flush();
        }
    }

    /**
     * Flush sequence from spec 5.1:
     *   1. (Memtable is already "frozen" in the sense that we're holding the
     *      lock for this entire method - no concurrent writes can sneak in.)
     *   2-5. Write every Memtable entry out to a new SSTable file via SSTableWriter.
     *   6-7. Open a reader on the new file, add it to the front of the list
     *        (front = newest, matching the read path's newest-first order).
     *   8. Clear the Memtable.
     *   9. Rotate the WAL, since everything in it is now safely on disk.
     */
    private void flush() throws IOException {
        if (memtable.size() == 0) {
            return; // nothing to do - see Step 6 Test 7 discussion
        }

        Path sstablePath = dataDirectory.resolve(String.format(SSTABLE_NAME_FORMAT, nextSSTableId));
        nextSSTableId++;

        new SSTableWriter(sparseIndexInterval).write(sstablePath, memtable);

        SSTableReader reader = new SSTableReader(sstablePath);
        sstables.add(0, reader); // newest first

        memtable.clear();

        rotateWal();

        compactIfNeeded();
    }

    /**
     * Simplified size-tiered trigger from spec Step 4: once the SSTable count
     * crosses a threshold, merge ALL of them into one. Crude (no tiering by
     * size, no partial merges) but correct, and it keeps the merge algorithm
     * itself decoupled from the trigger policy - a smarter trigger can replace
     * this method later without touching Compactor at all.
     */
    private void compactIfNeeded() throws IOException {
        if (sstables.size() < COMPACTION_THRESHOLD) {
            return;
        }

        Path outputPath = dataDirectory.resolve(String.format(SSTABLE_NAME_FORMAT, nextSSTableId));
        nextSSTableId++;

        // We're compacting every SSTable at once, so no older SSTable survives
        // to be shadowed - purging tombstones here is always safe (spec Step 3,
        // Case 1). A future partial/tiered compaction would need to pass false
        // whenever some inputs are left out of the merge.
        SSTableReader compacted = compactor.compact(sstables, outputPath, true);

        // Swap first, then close/delete the old files - so if we crash between
        // swap and delete, both old and new files exist on disk. That's fine:
        // on restart the read path's "newest wins" rule makes the duplicates
        // harmless, just wasted space until the next compaction (spec Step 4).
        List<SSTableReader> oldReaders = new ArrayList<>(sstables);
        sstables.clear();
        sstables.add(compacted);

        for (SSTableReader old : oldReaders) {
            old.close();
        }
        for (SSTableReader old : oldReaders) {
            Files.deleteIfExists(old.getFilePath());
        }
    }

    /**
     * Everything in the WAL has just been durably persisted into an SSTable,
     * so the WAL can be thrown away and started fresh. Closing + deleting +
     * reopening (rather than e.g. truncating) keeps this simple and matches
     * how WAL's constructor already knows how to create a file from scratch.
     */
    private void rotateWal() throws IOException {
        try {
            wal.close();
        } catch (Exception e) {
            throw new IOException("Failed to close WAL during rotation", e);
        }
        Files.deleteIfExists(walPath);
        wal = new WAL(walPath);
    }

    /**
     * Flushes any pending data, then closes the WAL and every SSTable reader.
     * Safe to treat this as "shut down cleanly" - a subsequent open() will
     * find everything on disk via the normal recovery path.
     */
    @Override
    public synchronized void close() throws IOException {
        if (memtable.size() > 0) {
            flush();
        }
        try {
            wal.close();
        } catch (Exception e) {
            throw new IOException("Failed to close WAL", e);
        }
        for (SSTableReader reader : sstables) {
            reader.close();
        }
    }

    /** Parses the numeric id out of a filename like "000004.sst" -> 4. */
    private static int extractSSTableId(Path path) {
        String name = path.getFileName().toString();
        String idPart = name.substring(0, name.length() - SSTABLE_SUFFIX.length());
        return Integer.parseInt(idPart);
    }
}