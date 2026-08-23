package lsmdb.core;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class WALCodec {
    public static final byte VERSION = 0x01;
    public static final byte OP_PUT = 1;
    public static final byte OP_DELETE = 2;

    private WALCodec(){
    }

    public static byte[] encode(byte opcode, String key, String value){
        if(opcode != OP_PUT && opcode != OP_DELETE){
            throw new IllegalArgumentException("Unknown opcode: " + opcode);
        }

        Objects.requireNonNull(key, "key");

        if(opcode == OP_PUT){
            Objects.requireNonNull(value, "value");
        }

        // converting into bytes ENCODING

        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = (opcode == OP_DELETE) ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);

        ByteBuffer buffer = ByteBuffer.allocate(
                1 + 1 + Integer.BYTES + keyBytes.length + Integer.BYTES + valueBytes.length
        );

        buffer.put(VERSION);
        buffer.put(opcode);
        buffer.putInt(keyBytes.length);
        buffer.put(keyBytes);
        buffer.putInt(valueBytes.length);
        buffer.put(valueBytes);

        return buffer.array();
    }

    public WALEntry decode(byte[] payload){
        Objects.requireNonNull(payload, "payload");
        try{
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte version = buffer.get();

            if(version != VERSION){
                throw new IllegalArgumentException("Unsupported WAL payload version: " + version);
            }

            byte opcode = buffer.get();
            if(opcode != OP_PUT && opcode != OP_DELETE){
                throw new IllegalArgumentException("Corrupt WAL entry: unknown opcode " + opcode);
            }

            int keyLength = buffer.get();
            if (keyLength < 0 || keyLength > buffer.remaining()) {
                throw new IllegalArgumentException("Corrupt WAL entry: invalid key length " + keyLength);
            }

            byte[] keyBytes = new byte[keyLength];
            buffer.get(keyBytes);
            String key = new String(keyBytes, StandardCharsets.UTF_8);

            int valueLength = buffer.getInt();
            if (valueLength < 0 || valueLength > buffer.remaining()) {
                throw new IllegalArgumentException("Corrupt WAL entry: invalid value length " + valueLength);
            }

            byte[] valueBytes = new byte[valueLength];
            buffer.get(valueBytes);
            String value = new String(valueBytes, StandardCharsets.UTF_8);

            if (opcode == OP_DELETE && valueLength != 0) {
                throw new IllegalArgumentException("Corrupt WAL entry: DELETE entry has a value");
            }

            if (buffer.remaining() != 0) {
                throw new IllegalArgumentException("Corrupt WAL entry: trailing bytes");
            }

            return new WALEntry(opcode, key, value);
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("Corrupt WAL entry: truncated payload", e);
        }
    }
}