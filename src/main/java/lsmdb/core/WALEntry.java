package lsmdb.core;

import java.util.Objects;

public record WALEntry(byte opcode, String key, String value) {

    public WALEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");

        if (opcode != WALCodec.OP_PUT && opcode != WALCodec.OP_DELETE) {
            throw new IllegalArgumentException("Unknown opcode: " + opcode);
        }

        if (opcode == WALCodec.OP_DELETE && !value.isEmpty()) {
            throw new IllegalArgumentException("DELETE value must be empty");
        }
    }

    public boolean isPut() {
        return opcode == WALCodec.OP_PUT;
    }

    public boolean isDelete() {
        return opcode == WALCodec.OP_DELETE;
    }
}