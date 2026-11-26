# NovaDB

A replicated table store written in Java 17 with no runtime dependencies. It uses
the [Raft consensus algorithm](https://raft.github.io/raft.pdf), so a cluster of
three nodes keeps working when one fails.

## Features

- Raft leader election, log replication and majority commit
- Snapshots for log compaction and for catching up lagging nodes
- Durable storage: checksums, fsync, atomic file replacement, torn-write recovery
- Safe client retries: a repeated write is applied once
- Reads go through the log, so they are never stale
- Fault tests: partitions, message loss, crashes, and force-killed processes

## Architecture

```text
 CLI / benchmark
       |  TCP (finds the leader, retries)
       v
 +-----------+   AppendEntries   +-----------+
 |  Leader   | ----------------> | Follower  |  x2
 |  Raft     |   RequestVote     |  Raft     |
 |  log      |   InstallSnapshot |  log      |
 |  Database |                   |  Database |
 +-----------+                   +-----------+
```

A write: the leader appends it to its log and forces it to disk, sends it to the
followers, commits once a majority has stored it, applies it, then replies.

| Package | Contents |
|---|---|
| `engine` | Tables and commands (the state machine) |
| `raft` | Consensus and disk storage |
| `net` | Wire protocol, node server, client |
| `cli` | Console and benchmark |

## Run locally

```bash
mvn compile
```

In three terminals, replacing `N` with 1, 2 and 3:

```bash
java -cp target/classes com.novadb.NovaDBApplication node cluster.properties N data/N
```

In a fourth:

```bash
java -cp target/classes com.novadb.NovaDBApplication cli cluster.properties
```

Try `STATUS`, stop the leader with Ctrl+C, then run `SELECT` and `STATUS` again.

## Run with Docker

```bash
docker compose up -d --build
docker compose run --rm cli
docker compose stop node1
docker compose down        # add -v to delete the data
```

## Test and benchmark

```bash
mvn test
java -cp target/classes com.novadb.NovaDBApplication bench cluster.properties 8 2000
```

## Results

Measured on a MacBook Air (Apple M5, 10 cores) with all three nodes and the
clients on the same machine, 2,000 inserts per run, one run each. Your numbers
will differ.

| Clients | Writes/sec | p50 | p99 |
|---|---|---|---|
| 1 | 90 | 11.9 ms | 15.9 ms |
| 8 | 168 | 47.1 ms | 76.0 ms |
| 32 | 221 | 145.0 ms | 232.9 ms |

In five forced leader kills, writes resumed after 0.53 to 0.70 s (as seen by the
client, with a 400 to 800 ms election timeout).

## Design decisions

| Choice | Reason | Cost |
|---|---|---|
| Reads through the log | Simple and linearizable | Every read waits for disk and a majority |
| One lock per Raft node | Easier to reason about | Disk writes limit concurrency |
| One fsync per proposed entry on the leader | Simple durability | Group commit would raise throughput |
| Full snapshot in one message | Simple | Limited to 64 MB |

## Limits

- All data is on every node (no sharding) and must fit in memory.
- Fixed cluster membership.
- No pre-vote or check-quorum: a node rejoining after a partition can force an
  election, and a cut-off leader still shows itself as leader (it cannot commit).
- One session record is kept per client and never expires.
- No authentication or encryption. Use only on a trusted network.
- Row values cannot contain commas. A very large `SELECT` can exceed the 64 MB message limit.
