package lsmdb.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        try {
            Path walPath = Files.createTempFile("wal_test_", ".log");

            // --- Phase 1: Write entries to WAL ---
            System.out.println("Writing logs to: " + walPath);
            try (WAL wal = new WAL(walPath)) {
                wal.append("SET key1 = val1".getBytes(StandardCharsets.UTF_8));
                wal.append("SET key2 = val2".getBytes(StandardCharsets.UTF_8));
                wal.append("DELETE key1".getBytes(StandardCharsets.UTF_8));
            }

            // --- Phase 2: Simulate Database Startup / Crash Recovery ---
            System.out.println("\nSimulating Database Recovery...");
            try (WAL wal = new WAL(walPath)) {
                List<byte[]> recoveredPayloads = wal.recover();

                for (int i = 0; i < recoveredPayloads.size(); i++) {
                    String entry = new String(recoveredPayloads.get(i), StandardCharsets.UTF_8);
                    System.out.printf("Recovered Entry [%d]: %s%n", i + 1, entry);
                }

                // Append new mutation post-recovery
                wal.append("SET key3 = val3".getBytes(StandardCharsets.UTF_8));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}