# SedimentDB

A distributed key-value database built from scratch in Java — developed phase by phase alongside *Designing Data-Intensive Applications* (Kleppmann). Each layer is implemented only after understanding the problem it solves, not before.

## Features (by phase)

- **Storage engine** — LSM-tree with WAL, memtable, SSTables, compaction, bloom filters
- **Networking** — custom binary wire protocol over TCP
- **Replication** — single-leader, sync/async, automatic follower catch-up
- **Partitioning** — consistent hashing across nodes
- **Consensus** — Raft-based automatic leader election
- **Transactions** — MVCC with snapshot isolation
- **Ops** — Docker Compose deployment, Prometheus/Grafana metrics, fault injection

## Status

🚧 Work in progress

## Why
Built as a hands-on companion to DDIA — reading a chapter, then implementing the corresponding piece, so distributed systems concepts (replication lag, split-brain, quorum, rebalancing) are learned by hitting them firsthand, not just reading about them.
