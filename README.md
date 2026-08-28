# SedimentDB

A distributed key-value database built from scratch in Java 25 — developed phase by phase alongside *Designing Data-Intensive Applications* (Kleppmann). The core philosophy is to implement each layer only after deeply understanding the problem it solves, relying on first principles rather than black-box libraries.

## Current Status: Phase 1 (Core LSM-Tree Engine) — mostly complete 🚧

The single-node storage engine is functional, utilizing a Log-Structured Merge-tree (LSM-tree) architecture. 

### Implemented Features:
- **Write-Ahead Log (WAL)**: Ensures "at-least-once" durability. Features CRC32 checksumming, magic-byte framing, and crash recovery that accurately detects and truncates partial writes.
- **Memtable**: In-memory Red-Black Tree (`TreeMap`) that buffers recent writes. Uses sentinel-based tombstone deletion and exact byte-level size tracking to trigger memory-bounded flushes.
- **SSTables (Sorted String Tables)**: Custom binary format for immutable on-disk storage.
  - **Sparse Index**: Loaded into memory for $O(\log N)$ point lookups, ensuring we only scan small data blocks rather than the whole file.
  - **Sequential I/O**: Buffered direct I/O for fast writing, and positional `FileChannel` reads for concurrent access.
- **Storage Engine**: The facade that orchestrates the read/write paths, handling WAL replay on startup, memory thresholds, and multi-SSTable point lookups (newest-first with tombstone shadowing).
- **Compaction**: Size-tiered compaction using a K-way merge (via Min-Heap priority queue). Automatically resolves overlapping keys, purges obsolete tombstones, and reclaims disk space.

### Next up:
- **Bloom Filters**: To reduce read amplification by skipping SSTable disk seeks for keys that don't exist.

## Future Phases

- **Phase 2: Networking** — Custom binary wire protocol over TCP/NIO for client-server communication.
- **Phase 3: Replication** — Single-leader replication, sync/async modes, and automatic follower catch-up.
- **Phase 4: Partitioning & Consensus** — Consistent hashing, distributed cluster management, and Raft-based leader election.
- **Phase 5: Transactions** — MVCC with snapshot isolation.

## Project Structure

- `src/main/java/lsmdb/core/` — The core LSM-tree engine (WAL, Memtable, SSTable, Compactor, StorageEngine).
- `src/main/java/lsmdb/tools/` — CLI tools for simulating crashes and verifying database integrity (`CrashTestWriter`, `CrashTestVerifier`).
- `src/test/java/lsmdb/core/` — Comprehensive unit and integration tests for every component (67 passing tests).

## Why build this?

Built as a hands-on companion to DDIA. By reading a chapter and then implementing the corresponding piece from scratch, distributed systems concepts (read/write amplification, crash recovery, replication lag, split-brain, quorum) are learned by hitting them firsthand, rather than just reading theory.
