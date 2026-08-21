package lsmdb.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

public class WAL implements AutoCloseable{

    // Record Layout
    // [2 bytes Magic] + [4 bytes Length] + [N bytes payload] + [8 bytes CRC32]

    private static final byte[] MAGIC_BYTES= new byte[] {0x57, 0x41}; // 'W', 'A'
    private static final int HEADER_SIZE = MAGIC_BYTES.length + 4;
    private static final int CHECKSUM_SIZE = 8;

    private final FileChannel channel;

    public WAL(Path filePath) throws IOException{
        this.channel = FileChannel.open(filePath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);

        this.channel.position(this.channel.size());
    }

    /**
     * Appends a record to the WAL and flushes to disk.
     */
    public synchronized void append(byte[] payload) throws IOException{
        // Calculate checksum
        CRC32 crc = new CRC32();
        crc.update(payload);
        long checksum = crc.getValue();

        // Allocate and build record buffer
        int recordLength = HEADER_SIZE + payload.length + CHECKSUM_SIZE;

        ByteBuffer buffer = ByteBuffer.allocate(recordLength);

        buffer.put(MAGIC_BYTES);
        buffer.putInt(payload.length);
        buffer.put(payload);
        buffer.putLong(checksum);

        // 3. Write to FileChannel and force flush to disk
        buffer.flip();
        while(buffer.hasRemaining()){
             channel.write(buffer);
        }

        // Force metadata and content sync to underlying storage device
        channel.force(false);

    }


    /**
     * Reads and recovers all valid records from the beginning of the log.
     * Stops cleanly if a partial write or checksum mismatch is encountered at EOF.
     */
    public List<byte[]> recover() throws IOException{
        List<byte[]> records = new ArrayList<>();
        channel.position(0);
        ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE);

        while(channel.position() < channel.size()){
            long recordStartPos = channel.position();

            headerBuffer.clear();
            if(fillBuffer(headerBuffer)){
                // Unexpected EOF inside header (torn write)
                channel.truncate(recordStartPos);
                break;
            }

            headerBuffer.flip();
            byte[] magic = new byte[MAGIC_BYTES.length];
            headerBuffer.get(magic);

            // Validate Magic Bytes
            if(!Arrays.equals(magic, MAGIC_BYTES)){
                throw new IOException("WAL Corruption: Invalid magic bytes at position " + recordStartPos);
            }


            int payloadSize = headerBuffer.getInt();
            if(payloadSize < 0){
                throw new IOException("WAL Corruption: Negative payload length at position " + recordStartPos);
            }

            // Reading the payload;
            ByteBuffer payloadBuffer = ByteBuffer.allocate(payloadSize + CHECKSUM_SIZE);
            if(fillBuffer(payloadBuffer)){
                // Unexpected EOF inside header (torn write)
                channel.truncate(recordStartPos);
                break;
            }

            payloadBuffer.flip();
            byte[] data = new byte[payloadSize];

            payloadBuffer.get(data);

            long storedChecksum = payloadBuffer.getLong();

            // 3. Verify Integrity
            CRC32 calCrc = new CRC32();
            calCrc.update(data);
            long newChecksum = calCrc.getValue();
            if(newChecksum != storedChecksum){
                throw new IOException("WAL Corruption: Checksum mismatch at position " + recordStartPos);
            }

            records.add(data);
        }
        // Reset channel position back to end of file for subsequent appends
        channel.position(channel.size());
        return records;
    }

    boolean fillBuffer(ByteBuffer buffer) throws IOException{
        while (buffer.hasRemaining()) {
            int bytesRead = channel.read(buffer);
            if (bytesRead == -1) {
                return true; // EOF reached before buffer was completely filled
            }
        }
        return false;
    }

    @Override
    public void close() throws Exception {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
    }
}
