package lsmdb.core;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Verifier for crash recovery: opens the StorageEngine on the same data
 * directory the CrashTestWriter was using, and checks that:
 *   1. Every key from key-000000 to key-<lastAck> is present with the correct value
 *   2. The key immediately after lastAck is NOT present (no phantom writes)
 *
 * Usage:
 *   java CrashTestVerifier <dataDir> <lastAckedIndex>
 *
 * Example: if the last ACK line before kill was "ACK key-000347 = value-347",
 * run:  java CrashTestVerifier <dataDir> 347
 */
public class CrashTestVerifier {

    private static final long MEMTABLE_SIZE = 4096;
    private static final int INDEX_INTERVAL = 16;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java CrashTestVerifier <dataDir> <lastAckedIndex>");
            System.exit(1);
        }
        Path dataDir = Paths.get(args[0]);
        int lastAcked = Integer.parseInt(args[1]);

        System.out.println("=== Crash Recovery Verification ===");
        System.out.println("Data directory: " + dataDir);
        System.out.println("Last acknowledged index: " + lastAcked);
        System.out.println();

        int missing = 0;
        int corrupt = 0;
        int verified = 0;
        int phantoms = 0;

        try (StorageEngine engine = StorageEngine.open(dataDir, MEMTABLE_SIZE, INDEX_INTERVAL)) {

            // Phase 1: verify all acknowledged writes are present and correct
            System.out.println("Phase 1: Checking keys 0 through " + lastAcked + " ...");
            for (int i = 0; i <= lastAcked; i++) {
                String key = String.format("key-%06d", i);
                String expected = "value-" + i;
                String actual = engine.get(key);

                if (actual == null) {
                    if (missing < 10) {
                        System.out.println("  MISSING: " + key + " (expected: " + expected + ")");
                    }
                    missing++;
                } else if (!expected.equals(actual)) {
                    if (corrupt < 10) {
                        System.out.println("  CORRUPT: " + key + " expected=" + expected + " actual=" + actual);
                    }
                    corrupt++;
                } else {
                    verified++;
                }
            }

            // Phase 2: check that keys beyond lastAcked do NOT exist
            System.out.println("Phase 2: Checking for phantom writes beyond index " + lastAcked + " ...");
            for (int i = lastAcked + 1; i <= lastAcked + 100; i++) {
                String key = String.format("key-%06d", i);
                String actual = engine.get(key);
                if (actual != null) {
                    if (phantoms < 10) {
                        System.out.println("  PHANTOM: " + key + " = " + actual + " (should not exist)");
                    }
                    phantoms++;
                }
            }
        }

        // Summary
        System.out.println();
        System.out.println("=== Results ===");
        System.out.println("Verified OK : " + verified + " / " + (lastAcked + 1));
        System.out.println("Missing     : " + missing);
        System.out.println("Corrupt     : " + corrupt);
        System.out.println("Phantoms    : " + phantoms);
        System.out.println();

        if (missing == 0 && corrupt == 0 && phantoms == 0) {
            System.out.println("✅ PASS — All acknowledged writes survived the crash. No phantom writes.");
        } else {
            System.out.println("❌ FAIL — Data integrity violation detected.");
            System.exit(1);
        }
    }
}
