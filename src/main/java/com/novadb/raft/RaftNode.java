package com.novadb.raft;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import com.novadb.engine.Command;
import com.novadb.engine.Database;
import com.novadb.engine.Result;

/**
 * Raft consensus. All state is guarded by this object's lock; messages are
 * always sent without holding it.
 */
public class RaftNode implements AutoCloseable {

    public enum Role { FOLLOWER, CANDIDATE, LEADER }

    public record Config(int electionTimeoutMs, int heartbeatMs, int snapshotEvery) {
        public static Config defaults() {
            return new Config(400, 60, 1000);
        }
    }

    public record Status(int id, Role role, long term, int leader, long commitIndex,
                         long lastIndex, long snapshotIndex) {
    }

    /** This node cannot answer. leader is a hint, or -1. */
    public static class NotLeaderException extends RuntimeException {
        public final int leader;

        public NotLeaderException(int leader) {
            super("Not the leader");
            this.leader = leader;
        }
    }

    private record Pending(long term, CompletableFuture<Result> future) {
    }

    private static final int BATCH = 100;

    private final int id;
    private final List<Integer> peers;
    private final Storage storage;
    private final Transport transport;
    private final Config config;
    private final Database database = new Database();

    private final List<Entry> log = new ArrayList<>(); // entries after the snapshot
    private long snapshotIndex;
    private long snapshotTerm;

    private Role role = Role.FOLLOWER;
    private long term;
    private int votedFor;
    private int leader = -1;
    private long commitIndex;
    private long lastApplied;
    private long electionDeadline;
    private int votes;
    private boolean running = true;

    // Leader only
    private final Map<Integer, Long> nextIndex = new HashMap<>();
    private final Map<Integer, Long> matchIndex = new HashMap<>();
    private final Map<Integer, Long> lastSent = new HashMap<>();
    private final Map<Long, Pending> pending = new HashMap<>();
    private final List<Thread> threads = new ArrayList<>();

    public RaftNode(int id, List<Integer> peers, Storage storage,
                    Transport transport, Config config) {
        this.id = id;
        this.peers = List.copyOf(peers);
        this.storage = storage;
        this.transport = transport;
        this.config = config;

        term = storage.term();
        votedFor = storage.votedFor();
        Storage.Saved saved = storage.snapshot();
        snapshotIndex = saved.index();
        snapshotTerm = saved.term();
        if (saved.data() != null) {
            database.restore(saved.data());
        }
        log.addAll(storage.entries());
        // Entries after the snapshot are applied once a leader confirms them.
        commitIndex = lastApplied = snapshotIndex;
    }

    public synchronized void start() {
        resetElectionTimer();
        threads.add(new Thread(this::runElectionTimer, "raft-" + id + "-timer"));
        for (int peer : peers) {
            threads.add(new Thread(() -> replicate(peer), "raft-" + id + "-to-" + peer));
        }
        threads.forEach(thread -> {
            thread.setDaemon(true);
            thread.start();
        });
    }

    /**
     * Adds a command to the log. The future completes once a majority has stored
     * it and it has been applied. Reads go through the log too, so they can
     * never return stale data.
     */
    public synchronized CompletableFuture<Result> propose(Command command) {
        if (!running || role != Role.LEADER) {
            return CompletableFuture.failedFuture(
                    new NotLeaderException(leader == id ? -1 : leader));
        }
        CompletableFuture<Result> future = new CompletableFuture<>();
        long index = lastIndex() + 1;
        pending.put(index, new Pending(term, future));
        append(List.of(new Entry(index, term, command)));
        advanceCommit(); // matters for a single-node cluster
        notifyAll();
        return future;
    }

    public synchronized Status status() {
        return new Status(id, role, term, leader, commitIndex, lastIndex(), snapshotIndex);
    }

    public synchronized Rpc.Reply handle(Rpc.Request request) throws IOException {
        if (!running) {
            throw new IOException("Node " + id + " is stopped");
        }
        if (request instanceof Rpc.Vote vote) {
            return onVote(vote);
        }
        if (request instanceof Rpc.Append append) {
            return onAppend(append);
        }
        return onSnapshot((Rpc.Snapshot) request);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (!running) {
                return;
            }
            running = false;
            failPending();
            notifyAll();
        }
        for (Thread thread : threads) {
            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            try {
                storage.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    // ---------------- elections ----------------

    private synchronized void runElectionTimer() {
        while (running) {
            if (role != Role.LEADER && System.nanoTime() >= electionDeadline) {
                startElection();
            }
            pause(10);
        }
    }

    private void startElection() {
        persistVote(term + 1, id);
        role = Role.CANDIDATE;
        leader = -1;
        votes = 1;
        resetElectionTimer();
        if (isMajority(votes)) {
            becomeLeader();
        }
        Rpc.Vote request = new Rpc.Vote(term, id, lastIndex(), termAt(lastIndex()));
        for (int peer : peers) {
            Thread thread = new Thread(() -> requestVote(peer, request));
            thread.setDaemon(true);
            thread.start();
        }
    }

    private void requestVote(int peer, Rpc.Vote request) {
        Rpc.Reply reply;
        try {
            reply = transport.send(peer, request);
        } catch (IOException e) {
            return; // an unreachable peer simply does not vote
        }
        synchronized (this) {
            if (!running) {
                return;
            }
            if (reply.term() > term) {
                stepDown(reply.term());
            } else if (role == Role.CANDIDATE && term == request.term()
                    && reply.success() && isMajority(++votes)) {
                becomeLeader();
            }
        }
    }

    private Rpc.Reply onVote(Rpc.Vote vote) {
        if (vote.term() > term) {
            stepDown(vote.term());
        }
        // Only vote for a candidate whose log is at least as complete as ours.
        long myTerm = termAt(lastIndex());
        boolean upToDate = vote.lastTerm() > myTerm
                || (vote.lastTerm() == myTerm && vote.lastIndex() >= lastIndex());
        boolean grant = vote.term() == term && upToDate
                && (votedFor == -1 || votedFor == vote.candidate());
        if (grant) {
            persistVote(term, vote.candidate()); // saved before replying
            resetElectionTimer();
        }
        return new Rpc.Reply(term, grant, 0);
    }

    private void becomeLeader() {
        role = Role.LEADER;
        leader = id;
        for (int peer : peers) {
            nextIndex.put(peer, lastIndex() + 1);
            matchIndex.put(peer, 0L);
            lastSent.put(peer, 0L);
        }
        // A new leader may only commit entries of its own term, so it adds one.
        append(List.of(new Entry(lastIndex() + 1, term, Command.noop())));
        advanceCommit();
        notifyAll();
    }

    private void stepDown(long newTerm) {
        if (newTerm > term) {
            persistVote(newTerm, -1);
            leader = -1;
        }
        if (role == Role.LEADER) {
            resetElectionTimer();
            failPending();
        }
        role = Role.FOLLOWER;
    }

    // ---------------- replication ----------------

    private void replicate(int peer) {
        while (true) {
            Rpc.Request request;
            synchronized (this) {
                while (running && !shouldSend(peer)) {
                    pause(config.heartbeatMs() / 4 + 1);
                }
                if (!running) {
                    return;
                }
                lastSent.put(peer, System.nanoTime());
                request = nextRequest(peer);
            }
            try {
                Rpc.Reply reply = transport.send(peer, request);
                synchronized (this) {
                    onReply(peer, request, reply);
                }
            } catch (IOException e) {
                synchronized (this) {
                    if (running) {
                        pause(config.heartbeatMs());
                    }
                }
            }
        }
    }

    private boolean shouldSend(int peer) {
        return role == Role.LEADER && (nextIndex.get(peer) <= lastIndex()
                || System.nanoTime() - lastSent.get(peer)
                >= config.heartbeatMs() * 1_000_000L);
    }

    private Rpc.Request nextRequest(int peer) {
        long next = nextIndex.get(peer);
        if (next <= snapshotIndex) { // the entries it needs are gone
            Storage.Saved saved = storage.snapshot();
            return new Rpc.Snapshot(term, id, saved.index(), saved.term(), saved.data());
        }
        long end = Math.min(lastIndex(), next + BATCH - 1);
        List<Entry> batch = List.copyOf(log.subList(
                (int) (next - snapshotIndex - 1), (int) (end - snapshotIndex)));
        return new Rpc.Append(term, id, next - 1, termAt(next - 1), batch, commitIndex);
    }

    private void onReply(int peer, Rpc.Request request, Rpc.Reply reply) {
        if (!running) {
            return;
        }
        if (reply.term() > term) {
            stepDown(reply.term());
            return;
        }
        long sentTerm = request instanceof Rpc.Append a
                ? a.term() : ((Rpc.Snapshot) request).term();
        if (role != Role.LEADER || sentTerm != term) {
            return; // a late reply from an earlier term
        }
        if (reply.success()) {
            matchIndex.put(peer, Math.max(matchIndex.get(peer), reply.index()));
            nextIndex.put(peer, matchIndex.get(peer) + 1);
            advanceCommit();
        } else {
            nextIndex.put(peer, Math.max(1, Math.min(reply.index(), nextIndex.get(peer) - 1)));
        }
    }

    private Rpc.Reply onAppend(Rpc.Append append) {
        if (append.term() < term) {
            return new Rpc.Reply(term, false, 0);
        }
        stepDown(append.term());
        leader = append.leader();
        resetElectionTimer();

        long previous = append.prevIndex();
        if (previous > lastIndex()) {
            return new Rpc.Reply(term, false, lastIndex() + 1);
        }
        if (previous > snapshotIndex && termAt(previous) != append.prevTerm()) {
            // Skip back over the whole conflicting term at once.
            long conflict = termAt(previous);
            long first = previous;
            while (first - 1 > snapshotIndex && termAt(first - 1) == conflict) {
                first--;
            }
            return new Rpc.Reply(term, false, first);
        }

        List<Entry> added = new ArrayList<>();
        for (Entry entry : append.entries()) {
            if (entry.index() <= snapshotIndex) {
                continue;
            }
            if (added.isEmpty() && entry.index() <= lastIndex()) {
                if (termAt(entry.index()) == entry.term()) {
                    continue; // already stored
                }
                truncateFrom(entry.index());
            }
            added.add(entry);
        }
        append(added);

        long lastNew = previous + append.entries().size();
        long commit = Math.min(append.leaderCommit(), lastNew);
        if (commit > commitIndex) {
            commitIndex = commit;
            applyCommitted();
        }
        return new Rpc.Reply(term, true, lastNew);
    }

    private Rpc.Reply onSnapshot(Rpc.Snapshot request) throws IOException {
        if (request.term() < term) {
            return new Rpc.Reply(term, false, 0);
        }
        stepDown(request.term());
        leader = request.leader();
        resetElectionTimer();

        long index = request.lastIndex();
        if (index > commitIndex) {
            List<Entry> keep = new ArrayList<>();
            if (index <= lastIndex() && termAt(index) == request.lastTerm()) {
                keep.addAll(log.subList((int) (index - snapshotIndex), log.size()));
            }
            try {
                storage.saveSnapshot(new Storage.Saved(
                        index, request.lastTerm(), request.data()), keep);
            } catch (IOException e) {
                throw halt(e);
            }
            log.clear();
            log.addAll(keep);
            snapshotIndex = index;
            snapshotTerm = request.lastTerm();
            database.restore(request.data());
            commitIndex = lastApplied = index;
        }
        return new Rpc.Reply(term, true, index);
    }

    private void advanceCommit() {
        for (long index = lastIndex(); index > commitIndex; index--) {
            if (termAt(index) != term) {
                break; // older entries commit only along with a newer one
            }
            int stored = 1;
            for (int peer : peers) {
                if (matchIndex.get(peer) >= index) {
                    stored++;
                }
            }
            if (isMajority(stored)) {
                commitIndex = index;
                applyCommitted();
                break;
            }
        }
    }

    private void applyCommitted() {
        while (lastApplied < commitIndex) {
            Entry entry = log.get((int) (++lastApplied - snapshotIndex - 1));
            Result result = database.apply(entry.command());
            Pending waiting = pending.remove(entry.index());
            if (waiting != null) {
                if (waiting.term() == entry.term()) {
                    waiting.future().complete(result);
                } else {
                    waiting.future().completeExceptionally(new NotLeaderException(leader));
                }
            }
        }
        if (lastApplied - snapshotIndex >= config.snapshotEvery()) {
            takeSnapshot();
        }
    }

    private void takeSnapshot() {
        long index = lastApplied;
        long indexTerm = termAt(index);
        List<Entry> keep = new ArrayList<>(
                log.subList((int) (index - snapshotIndex), log.size()));
        try {
            storage.saveSnapshot(
                    new Storage.Saved(index, indexTerm, database.snapshot()), keep);
        } catch (IOException e) {
            throw halt(e);
        }
        log.clear();
        log.addAll(keep);
        snapshotIndex = index;
        snapshotTerm = indexTerm;
    }

    // ---------------- helpers ----------------

    private long lastIndex() {
        return snapshotIndex + log.size();
    }

    private long termAt(long index) {
        return index == snapshotIndex
                ? snapshotTerm : log.get((int) (index - snapshotIndex - 1)).term();
    }

    private void append(List<Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        try {
            storage.append(entries);
        } catch (IOException e) {
            throw halt(e);
        }
        log.addAll(entries);
    }

    private void truncateFrom(long index) {
        try {
            storage.truncateFrom(index);
        } catch (IOException e) {
            throw halt(e);
        }
        log.subList((int) (index - snapshotIndex - 1), log.size()).clear();
    }

    private void persistVote(long newTerm, int candidate) {
        try {
            storage.saveVote(newTerm, candidate);
        } catch (IOException e) {
            throw halt(e);
        }
        term = newTerm;
        votedFor = candidate;
    }

    /** A node that cannot write to disk must stop taking part. */
    private UncheckedIOException halt(IOException e) {
        running = false;
        failPending();
        System.err.println("Node " + id + " stopped, disk error: " + e.getMessage());
        return new UncheckedIOException(e);
    }

    private void failPending() {
        List<Pending> waiting = new ArrayList<>(pending.values());
        pending.clear();
        waiting.forEach(p -> p.future().completeExceptionally(new NotLeaderException(-1)));
    }

    private boolean isMajority(int count) {
        return count > (peers.size() + 1) / 2;
    }

    private void resetElectionTimer() {
        // Random timeouts make it unlikely that two nodes stand at once.
        int timeout = config.electionTimeoutMs();
        electionDeadline = System.nanoTime()
                + ThreadLocalRandom.current().nextInt(timeout, timeout * 2) * 1_000_000L;
    }

    private void pause(long millis) {
        try {
            wait(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
