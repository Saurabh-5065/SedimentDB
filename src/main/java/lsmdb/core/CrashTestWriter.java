package lsmdb.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A long-running writer process that writes numbered entries to a StorageEngine
 * and prints each acknowledged write to stdout. Designed to be killed mid-write
 * with "kill -9" (or taskkill /F) to test crash recovery.
 *
 * Usage:
 *   java CrashTestWriter <dataDir>
 *
 * It writes keys "key-000000" through "key-999999", printing each acknowledged
 * write. After kill, the verifier reads back the data and checks that:
 *   - Every key up to the last printed line is present and correct
 *   - No key beyond the last printed line exists
 */
public class CrashTestWriter {

    private static final long MEMTABLE_SIZE = 4096;  // 4 KB — frequent flushes
    private static final int INDEX_INTERVAL = 16;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java CrashTestWriter <dataDir>");
            System.exit(1);
        }
        Path dataDir = Paths.get(args[0]);
        Files.createDirectories(dataDir);

        try (StorageEngine engine = StorageEngine.open(dataDir, MEMTABLE_SIZE, INDEX_INTERVAL)) {
            for (int i = 0; i < 1_000_000; i++) {
                String key = String.format("key-%06d", i);
                String value = "value-" + i;
                engine.put(key, value);

                // Print AFTER the put() returns — at this point the WAL has
                // been fsynced, so this write is durable. If the process is
                // killed before this println, the write may or may not be
                // durable (it's in the WAL buffer but not necessarily fsynced
                // in older WAL implementations — ours fsyncs inside append(),
                // so it should survive).
                System.out.println("ACK " + key + " = " + value);
                System.out.flush();  // ensure the ACK line actually makes it out
            }
        }
        System.out.println("DONE — all 1,000,000 entries written.");
    }
}
