package lsmdb.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class SSTableWriter {
    private static final int MAGIC_NUMBER = 0x53535442;
    private static final byte VALUE_TYPE_REGULAR = 0x00;
    private static final byte VALUE_TYPE_TOMBSTONE = 0x01;

    /** Default buffer size for writing (64 KB). */
    private static final int BUFFER_SIZE = 64 * 1024;

    private final int sparseIndexInterval;

    public SSTableWriter(int sparseIndexInterval) {
        if (sparseIndexInterval <= 0) {
            throw new IllegalArgumentException("sparseIndexInterval must be positive");
        }
        this.sparseIndexInterval = sparseIndexInterval;
    }

    public SSTableWriter() {
        this(64);
    }


    /**
     * @param filePath destination file path
     * @param memtable source Memtable
     * @throws IOException if an I/O error occurs
     */

    public void write(Path filePath, Memtable memtable) throws IOException{
        try (FileChannel channel = FileChannel.open(filePath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)){

            ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);

            //sparse index entries (keys and their byte offsets)
            List<String> indexKeys = new ArrayList<>();
            List<Long> indexOffsets = new ArrayList<>();

            // Logical offset counter
            long currentOffset = 0;
            int entryCount = 0;

            Iterator<Map.Entry<String, String>> iterator = memtable.iterator();

            while(iterator.hasNext()){
                Map.Entry<String, String> entry = iterator.next();
                String key = entry.getKey();
                String value = entry.getValue();
                if (entryCount % sparseIndexInterval == 0) {
                    indexKeys.add(key);
                    indexOffsets.add(currentOffset);
                }
                byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

                putInt(buffer, channel, keyBytes.length);
                putBytes(buffer, channel, keyBytes);
                currentOffset += 4 + keyBytes.length;

                // Determine value type and write it
                if (value == Memtable.TOMBSTONE) {
                    putByte(buffer, channel, VALUE_TYPE_TOMBSTONE);
                    putInt(buffer, channel, 0);  // value_len = 0
                    currentOffset += 1 + 4;
                } else {
                    byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
                    putByte(buffer, channel, VALUE_TYPE_REGULAR);
                    putInt(buffer, channel, valueBytes.length);
                    putBytes(buffer, channel, valueBytes);
                    currentOffset += 1 + 4 + valueBytes.length;
                }

                entryCount++;
            }

            flushBuffer(buffer, channel);

            // Write index block
            long indexBlockOffset = currentOffset; // = data block size
            for (int i = 0; i < indexKeys.size(); i++) {
                byte[] keyBytes = indexKeys.get(i).getBytes(StandardCharsets.UTF_8);
                putInt(buffer, channel, keyBytes.length);
                putBytes(buffer, channel, keyBytes);
                putLong(buffer, channel, indexOffsets.get(i));
            }

            flushBuffer(buffer, channel);

            // Write footer (28 bytes)
            putLong(buffer, channel, indexBlockOffset);
            putLong(buffer, channel, currentOffset);   // data_block_size
            putInt(buffer, channel, entryCount);
            putInt(buffer, channel, indexKeys.size());
            putInt(buffer, channel, MAGIC_NUMBER);

            flushBuffer(buffer, channel);
            channel.force(true);   // ensures data is written to storage device
        }
    }

    private void putByte(ByteBuffer buffer, FileChannel channel, byte value) throws IOException {
        if (buffer.remaining() < 1) {
            flushBuffer(buffer, channel);
        }
        buffer.put(value);
    }

    private void putInt(ByteBuffer buffer, FileChannel channel, int value) throws IOException {
        if (buffer.remaining() < 4) {
            flushBuffer(buffer, channel);
        }
        buffer.putInt(value);
    }

    private void putLong(ByteBuffer buffer, FileChannel channel, long value) throws IOException {
        if (buffer.remaining() < 8) {
            flushBuffer(buffer, channel);
        }
        buffer.putLong(value);
    }

    private void putBytes(ByteBuffer buffer, FileChannel channel, byte[] bytes) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            if (!buffer.hasRemaining()) {
                flushBuffer(buffer, channel);
            }
            int chunk = Math.min(buffer.remaining(), bytes.length - offset);
            buffer.put(bytes, offset, chunk);
            offset += chunk;
        }
    }

    private void flushBuffer(ByteBuffer buffer, FileChannel channel) throws IOException {
        buffer.flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        buffer.clear();
    }

}
