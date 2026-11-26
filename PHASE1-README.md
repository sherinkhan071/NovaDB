# NovaDB

A small replicated database written in Java 17 to learn how distributed storage works.

## What it does

- Runs three nodes, each with its own TCP server and log (currently threads in one process)
- Keeps a full copy of the data on every node
- Sends each write to the row's primary (`id % 3`) first, then to the other nodes
- Logs every change and forces it to disk before applying it
- Rebuilds each node's tables from its log on restart
- Reports a write as successful only when all three nodes apply it

## Current limits

- No consensus or failover. A write fails if any node is down.
- A partial write leaves copies different; it is reported but not repaired.
- Reads come from the first reachable node, so they can be stale after a partial write.
- No sharding. The log is never compacted and has no checksums.
- Values cannot contain `,`, `|`, `;` or line breaks.

## Build, test and run

```bash
mvn test
mvn compile
java -cp target/classes com.novadb.NovaDBApplication
```

## Commands

```text
CREATE TABLE <name>
DROP TABLE <name>
INSERT INTO <table> <id>,<name>,<age>,<city>
SELECT * FROM <table>
UPDATE <table> SET <column> = <value> WHERE id = <id>
DELETE FROM <table> WHERE id = <id>
SHOW TABLES | HEALTH | STATS | BENCHMARK | HELP | EXIT
```

`UPDATE` knows the columns `name`, `age` and `city`.

## Roadmap

1. Correctness and tests (done)
2. One process per node and a safer message format
3. Checksums, snapshots and crash tests
4. Raft consensus
5. Fault tests and benchmarks
6. Docker Compose and design notes
