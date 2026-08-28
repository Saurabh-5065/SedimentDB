package lsmdb.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;

/**
 * Reads an SSTable file written by {@link SSTableWriter}.
 * Supports two access patterns:
 *   - get(key): point lookup using the sparse index + a short sequential scan
 *   - iterator(): full sorted scan of every entry (used later by compaction)
 * File layout on disk (written by SSTableWriter):
 *   [ data block  ] entries: key_len(4) key value_type(1) value_len(4) value
 *   [ index block ] entries: key_len(4) key offset(8)
 *   [ footer, 28 bytes fixed at end of file ]
 *       indexBlockOffset (8) | dataBlockSize (8) | entryCount (4)
 *       | indexEntryCount (4) | magicNumber (4)
 */
public class SSTableReader implements AutoCloseable {

    private static final int MAGIC_NUMBER = 0x53535442;
    private static final int FOOTER_SIZE = 28;
    private static final byte VALUE_TYPE_REGULAR = 0x00;
    private static final byte VALUE_TYPE_TOMBSTONE = 0x01;

    private final Path filePath;
    private final FileChannel channel;

    // Footer fields, kept around for bookkeeping / debugging.
    private final long indexBlockOffset;
    private final long dataBlockSize;
    private final int entryCount;
    private final int indexEntryCount;



    private final TreeMap<String, Long> sparseIndex;

    /**
     * Opens the SSTable file and loads its footer + sparse index into memory.
     * The data block itself is left on disk and read on demand.
     */
    public SSTableReader(Path filePath) throws IOException {
        this.filePath = filePath;
        this.channel = FileChannel.open(filePath, StandardOpenOption.READ);

        try {
            long fileSize = channel.size();
            if (fileSize < FOOTER_SIZE) {
                throw new IOException("File too small to be a valid SSTable: " + filePath);
            }

            // --- 1 & 2: read + validate the footer -----------------------------
            ByteBuffer footer = readFully(fileSize - FOOTER_SIZE, FOOTER_SIZE);
            this.indexBlockOffset = footer.getLong();
            this.dataBlockSize = footer.getLong();
            this.entryCount = footer.getInt();
            this.indexEntryCount = footer.getInt();
            int magic = footer.getInt();

            if (magic != MAGIC_NUMBER) {
                throw new IOException("Bad magic number - not an SSTable file: " + filePath);
            }

            // read + parse the index block into memory ---------------
            long indexBlockSize = (fileSize - FOOTER_SIZE) - indexBlockOffset;
            ByteBuffer indexBuf = readFully(indexBlockOffset, (int) indexBlockSize);

            this.sparseIndex = new TreeMap<>();
            for (int i = 0; i < indexEntryCount; i++) {
                int keyLen = indexBuf.getInt();
                byte[] keyBytes = new byte[keyLen];
                indexBuf.get(keyBytes);
                long offset = indexBuf.getLong();
                sparseIndex.put(new String(keyBytes, StandardCharsets.UTF_8), offset);
            }
        } catch (IOException e) {
            // Constructor failed partway through - don't leak the open file handle.
            channel.close();
            throw e;
        }
    }

    /**
     * Point lookup.
     *
     * @return {@code null} if the key isn't in this SSTable, {@link Memtable#TOMBSTONE}
     *         if the key was deleted, or the stored value string otherwise.
     */
    public String get(String key) throws IOException {
        // Step 1: binary search (via floorEntry) for where to start scanning.
        Map.Entry<String, Long> floor = sparseIndex.floorEntry(key);
        if (floor == null) {
            // Every indexed key is greater than our target. Since the very first
            // data entry is always indexed (see SSTableWriter: entryCount % N == 0
            // includes entryCount == 0), this means the key can't exist here at all.
            return null;
        }

        long scanStart = floor.getValue();

        // Step 2: figure out where to stop. If there's a next indexed key, its
        // offset is our upper bound; otherwise we scan to the end of the data block.
        Map.Entry<String, Long> ceiling = sparseIndex.higherEntry(floor.getKey());
        long scanLimit = (ceiling != null) ? ceiling.getValue() : dataBlockSize;

        // Step 3: sequential scan within [scanStart, scanLimit).
        long pos = scanStart;
        while (pos < scanLimit) {
            // -- read this entry's key --
            int keyLen = readFully(pos, 4).getInt();
            pos += 4;

            ByteBuffer keyBuf = readFully(pos, keyLen);
            String entryKey = new String(keyBuf.array(), 0, keyLen, StandardCharsets.UTF_8);
            pos += keyLen;

            // -- read value_type + value_len together (1 + 4 bytes) --
            ByteBuffer header = readFully(pos, 5);
            byte valueType = header.get();
            int valueLen = header.getInt();
            pos += 5;

            int cmp = entryKey.compareTo(key);
            if (cmp == 0) {
                // Found it.
                if (valueType == VALUE_TYPE_TOMBSTONE) {
                    return Memtable.TOMBSTONE;
                }
                ByteBuffer valueBuf = readFully(pos, valueLen);
                return new String(valueBuf.array(), 0, valueLen, StandardCharsets.UTF_8);
            }
            if (cmp > 0) {
                // Entries are stored in sorted order. We've already scanned past
                // where "key" would be, so it isn't in this SSTable. Stop early
                // instead of scanning the rest of the bucket for nothing.
                return null;
            }

            // Not a match yet - skip over this entry's value bytes and keep scanning.
            pos += valueLen;
        }

        return null;
    }

    /**
     * Returns a lazy, memory-efficient iterator over every entry in the data
     * block, in sorted key order. Used by compaction to merge-sort several
     * SSTables together. Unlike get(), this ignores the sparse index entirely
     * and just walks the data block start to finish.
     */
    public Iterator<SSTableEntry> iterator() {
        return new Iterator<>() {
            private long pos = 0;

            @Override
            public boolean hasNext() {
                return pos < dataBlockSize;
            }

            @Override
            public SSTableEntry next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                try {
                    int keyLen = readFully(pos, 4).getInt();
                    pos += 4;

                    ByteBuffer keyBuf = readFully(pos, keyLen);
                    String key = new String(keyBuf.array(), 0, keyLen, StandardCharsets.UTF_8);
                    pos += keyLen;

                    ByteBuffer header = readFully(pos, 5);
                    byte valueType = header.get();
                    int valueLen = header.getInt();
                    pos += 5;

                    boolean isTombstone = (valueType == VALUE_TYPE_TOMBSTONE);
                    String value = null;
                    if (!isTombstone) {
                        ByteBuffer valueBuf = readFully(pos, valueLen);
                        value = new String(valueBuf.array(), 0, valueLen, StandardCharsets.UTF_8);
                    }
                    pos += valueLen;

                    return new SSTableEntry(key, value, isTombstone);
                } catch (IOException e) {
                    // Iterator's next() can't declare checked exceptions, so we
                    // wrap it. Callers can catch UncheckedIOException if they need to.
                    throw new UncheckedIOException("Failed to read SSTable entry at offset " + pos, e);
                }
            }
        };
    }

    /** The file this reader was opened from — needed to delete it after compaction. */
    public Path getFilePath() {
        return filePath;
    }

    public int entryCount() {
        return entryCount;
    }

    public int indexEntryCount() {
        return indexEntryCount;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    /**
     * Reads exactly {@code length} bytes starting at {@code position}, without
     * disturbing the FileChannel's own position field. We use the positional
     * read(dst, position) overload rather than channel.position(x) + read(dst)
     * for two reasons:
     *   1. It's simpler - no shared mutable "current position" to manage.
     *   2. It's safe to call from multiple threads concurrently (per FileChannel's
     *      javadoc), which matters once several lookups can run at once.
     */
    private ByteBuffer readFully(long position, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length);
        long pos = position;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, pos);
            if (n == -1) {
                throw new IOException("Unexpected EOF while reading SSTable at position " + pos);
            }
            pos += n;
        }
        buf.flip();
        return buf;
    }

    /**
     * One decoded entry from the data block, used by {@link #iterator()}.
     * {@code value} is null when {@code isTombstone} is true, since the writer
     * doesn't store any value bytes for deletes.
     */
    public record SSTableEntry(String key, String value, boolean isTombstone) {
    }
}