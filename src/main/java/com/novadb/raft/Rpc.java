package com.novadb.raft;

import java.util.List;

/** The three Raft messages and their replies (figures 2 and 13 of the paper). */
public final class Rpc {

    private Rpc() {
    }

    public sealed interface Request permits Vote, Append, Snapshot {
    }

    public record Vote(long term, int candidate, long lastIndex, long lastTerm)
            implements Request {
    }

    public record Append(long term, int leader, long prevIndex, long prevTerm,
                         List<Entry> entries, long leaderCommit) implements Request {
    }

    public record Snapshot(long term, int leader, long lastIndex, long lastTerm,
                           byte[] data) implements Request {
    }

    /**
     * success means "vote granted" or "entries accepted". index is the last
     * matching index on success, or where the leader should retry on failure.
     */
    public record Reply(long term, boolean success, long index) {
    }
}
