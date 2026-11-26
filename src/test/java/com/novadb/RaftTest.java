package com.novadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.novadb.engine.Command;
import com.novadb.engine.Command.Op;
import com.novadb.engine.Result;
import com.novadb.raft.RaftNode;
import com.novadb.raft.Storage;
import com.novadb.raft.Transport;

/** Runs a cluster over an in-memory network that can be cut or made lossy. */
public class RaftTest {

    private static final RaftNode.Config CONFIG = new RaftNode.Config(150, 30, 20);

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final Map<Integer, RaftNode> nodes = new ConcurrentHashMap<>();
    private final Set<Integer> isolated = ConcurrentHashMap.newKeySet();
    private volatile double lossRate;
    private int size;
    private long sequence;

    private void startCluster(int count) throws IOException {
        size = count;
        for (int id = 1; id <= count; id++) {
            startNode(id);
        }
    }

    private void startNode(int id) throws IOException {
        List<Integer> peers = new ArrayList<>();
        for (int other = 1; other <= size; other++) {
            if (other != id) {
                peers.add(other);
            }
        }
        Transport transport = (peer, request) -> {
            checkLink(id, peer);
            RaftNode target = nodes.get(peer);
            if (target == null) {
                throw new IOException("down");
            }
            var reply = target.handle(request);
            checkLink(peer, id); // the reply can be lost too
            return reply;
        };
        RaftNode node = new RaftNode(id, peers,
                new Storage(folder.getRoot().toPath().resolve("node" + id)),
                transport, CONFIG);
        nodes.put(id, node);
        node.start();
    }

    private void checkLink(int from, int to) throws IOException {
        if (isolated.contains(from) != isolated.contains(to)
                || Math.random() < lossRate) {
            throw new IOException("network cut");
        }
    }

    private void kill(int id) {
        nodes.remove(id).close();
    }

    @After
    public void stop() {
        nodes.values().forEach(RaftNode::close);
    }

    /** The leader on the connected side of the network. */
    private int awaitLeader() {
        int[] found = {-1};
        await(() -> {
            for (var entry : nodes.entrySet()) {
                if (!isolated.contains(entry.getKey())
                        && entry.getValue().status().role() == RaftNode.Role.LEADER) {
                    found[0] = entry.getKey();
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Condition not reached in time");
            }
            sleep(10);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    private Command command(Op op, String... args) {
        return new Command(op, List.of(args), "test", ++sequence);
    }

    /** Retries the same command until some leader answers. */
    private Result run(Command command) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                return nodes.get(awaitLeader()).propose(command).get(500, TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException | NullPointerException e) {
                sleep(20);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("No leader accepted " + command);
    }

    private List<String> rows() {
        Result result = run(command(Op.SELECT, "t"));
        assertTrue(result.error(), result.ok());
        return result.rows();
    }

    private void insert(int id) {
        Result result = run(command(Op.INSERT, "t", id + ",row" + id));
        assertTrue(result.error(), result.ok());
    }

    private void awaitConverged() {
        await(() -> nodes.size() == size && nodes.values().stream()
                .map(n -> n.status().commitIndex() + ":" + n.status().lastIndex())
                .distinct().count() == 1);
    }

    @Test
    public void singleLeaderPerTerm() throws IOException {
        startCluster(3);
        awaitLeader();
        sleep(500);
        Map<Long, Integer> leaders = new HashMap<>();
        for (RaftNode node : nodes.values()) {
            var s = node.status();
            if (s.role() == RaftNode.Role.LEADER) {
                assertEquals(null, leaders.put(s.term(), s.id()));
            }
        }
        assertEquals(1, leaders.size());
    }

    @Test
    public void leaderFailureKeepsCommittedWrites() throws IOException {
        startCluster(3);
        run(command(Op.CREATE, "t"));
        insert(1);
        int old = awaitLeader();
        kill(old);
        insert(2);
        assertEquals(List.of("1,row1", "2,row2"), rows());
        startNode(old);
        awaitConverged();
    }

    @Test
    public void isolatedLeaderCannotCommitAndIsOverwritten() throws Exception {
        startCluster(5);
        run(command(Op.CREATE, "t"));
        int old = awaitLeader();
        isolated.add(old);
        var stale = nodes.get(old).propose(command(Op.INSERT, "t", "100,lost"));
        sleep(300);
        assertFalse(stale.isDone() && !stale.isCompletedExceptionally());
        insert(1);
        isolated.clear();
        awaitConverged();
        assertEquals(List.of("1,row1"), rows());
        assertTrue(stale.isCompletedExceptionally());
    }

    @Test
    public void minorityCannotCommit() throws Exception {
        startCluster(3);
        run(command(Op.CREATE, "t"));
        int leader = awaitLeader();
        for (int id = 1; id <= 3; id++) {
            if (id != leader) {
                kill(id);
            }
        }
        var future = nodes.get(leader).propose(command(Op.INSERT, "t", "1,x"));
        sleep(400);
        assertFalse(future.isDone());
    }

    @Test
    public void retriedWriteIsAppliedOnce() throws IOException {
        startCluster(3);
        run(command(Op.CREATE, "t"));
        Command write = command(Op.INSERT, "t", "1,once");
        assertTrue(run(write).ok());
        kill(awaitLeader());
        assertTrue("a retry returns the first result", run(write).ok());
        assertEquals(List.of("1,once"), rows());
    }

    @Test
    public void fullRestartRecoversFromSnapshotAndLog() throws IOException {
        startCluster(3);
        run(command(Op.CREATE, "t"));
        for (int i = 0; i < 55; i++) {
            insert(i);
        }
        run(command(Op.DELETE, "t", "2"));
        run(command(Op.UPDATE, "t", "3", "1", "changed"));
        List<String> before = rows();
        assertTrue(nodes.get(awaitLeader()).status().snapshotIndex() > 0);
        for (int id = 1; id <= 3; id++) {
            kill(id);
        }
        startCluster(3);
        assertEquals(before, rows());
        assertEquals(54, before.size());
    }

    @Test
    public void laggingNodeCatchesUpThroughSnapshot() throws IOException {
        startCluster(3);
        run(command(Op.CREATE, "t"));
        int lagging = awaitLeader() % 3 + 1;
        kill(lagging);
        for (int i = 0; i < 60; i++) {
            insert(i);
        }
        startNode(lagging);
        awaitConverged();
        assertTrue(nodes.get(lagging).status().snapshotIndex() >= 40);
        // Force the node that lagged to take part in every later commit.
        int other = lagging % 3 + 1;
        kill(other);
        assertEquals(60, rows().size());
    }

    @Test
    public void survivesRandomCrashesPartitionsAndMessageLoss() throws IOException {
        startCluster(5);
        run(command(Op.CREATE, "t"));
        Random random = new Random(7);
        List<String> expected = new ArrayList<>();
        lossRate = 0.05;
        for (int round = 0; round < 25; round++) {
            int victim = random.nextInt(5) + 1;
            int second = victim % 5 + 1;
            if (random.nextBoolean()) {
                kill(victim);
                isolated.add(second);
            } else {
                isolated.add(victim);
                isolated.add(second);
            }
            for (int i = 0; i < 4; i++) {
                int id = round * 10 + i;
                insert(id);
                expected.add(id + ",row" + id);
            }
            isolated.clear();
            if (!nodes.containsKey(victim)) {
                startNode(victim);
            }
        }
        lossRate = 0;
        awaitConverged();
        assertEquals(expected, rows());
    }
}
