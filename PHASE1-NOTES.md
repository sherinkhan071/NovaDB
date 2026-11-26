# Phase 1: what changed and why

| Problem | Cause | Fix |
|---|---|---|
| Delete + restart lost other rows | Data file and log were both replayed; replayed delete matched any column | The log is the only stored copy; delete matches the id |
| Changes applied before being logged | Delete and update changed memory first | Validate, log, then apply |
| Logged data could be lost on power failure | `flush()` does not force data to disk | `FileChannel.force` before applying |
| `DROP TABLE` did nothing | Node had no handler | Added, with an error for unknown operations |
| Failures reported as success | Replies were ignored | Every reply is checked; stop if the primary fails |
| Duplicate ids | No check | Rejected, and ids are normalised (`05` = `5`) |
| `UPDATE assets` failed | Parser searched for the text `set` | Pattern matching on the whole command |
| `;` split rows | Used as the row separator | Rejected (proper encoding is phase 2) |
| `exit` did not quit | Server threads kept running | Nodes are closed on exit |
| Benchmark skipped replication | Wrote to one node | Times 500 replicated inserts, reports p50 and p99 |

Removed: the `failover` command (it never failed a node), unused test methods,
`FileManager`, `ShardManager`, `NodeMessage`, the empty `CommandParser` and `novadb.txt`.

Old `.data` and `.wal` files use the previous format. Delete them before running.

## Questions to be ready for

1. **Why log before applying?** If the process dies in between, replay still has the change.
2. **What does fsync cost?** Each write waits for the disk. Batching writes (group commit) reduces this.
3. **What if the crash happens mid-write?** The last line has no newline, so it is discarded.
4. **Is a logged but unacknowledged write possible?** Yes. After a timeout the client cannot know the outcome.
5. **What if a replica fails?** Copies differ. Retrying hits "duplicate id" on the primary. Raft in phase 4 addresses this.
