package com.novadb;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.novadb.engine.Command.Op;
import com.novadb.net.Client;
import com.novadb.net.ClusterConfig;

/** Real processes over TCP, force-killed while a client is writing. */
public class ProcessCrashTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final Map<Integer, Process> processes = new TreeMap<>();
    private Path config;

    @After
    public void stop() {
        processes.values().forEach(Process::destroyForcibly);
    }

    private void start(int id) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        processes.put(id, new ProcessBuilder(java, "-cp",
                System.getProperty("java.class.path"), "com.novadb.NovaDBApplication",
                "node", config.toString(), "" + id,
                folder.getRoot().toPath().resolve("data" + id).toString())
                .redirectErrorStream(true)
                .redirectOutput(new File(folder.getRoot(), "node" + id + ".out"))
                .start());
    }

    private void kill(int id) throws InterruptedException {
        processes.remove(id).destroyForcibly().waitFor();
    }

    private static int leader(Client client) {
        for (int id : client.nodeIds()) {
            String status = client.status(id);
            if (status != null && status.contains("role=LEADER")) {
                return id;
            }
        }
        return -1;
    }

    private static int awaitLeader(Client client) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        int id;
        while ((id = leader(client)) < 0) {
            assertTrue("no leader", System.currentTimeMillis() < deadline);
            Thread.sleep(10);
        }
        return id;
    }

    @Test(timeout = 120_000)
    public void noAcknowledgedWriteIsLostWhenProcessesAreKilled() throws Exception {
        StringBuilder text = new StringBuilder();
        List<ServerSocket> sockets = new ArrayList<>();
        for (int id = 1; id <= 3; id++) {
            sockets.add(new ServerSocket(0));
            text.append("node.").append(id).append("=127.0.0.1:")
                    .append(sockets.get(id - 1).getLocalPort()).append('\n');
        }
        for (ServerSocket socket : sockets) {
            socket.close();
        }
        config = folder.getRoot().toPath().resolve("cluster.properties");
        Files.writeString(config, text);
        Map<Integer, InetSocketAddress> cluster = ClusterConfig.load(config);
        for (int id : cluster.keySet()) {
            start(id);
        }

        Set<String> acknowledged = new HashSet<>();
        AtomicBoolean running = new AtomicBoolean(true);
        List<Throwable> errors = new ArrayList<>();

        try (Client admin = new Client(cluster); Client writer = new Client(cluster)) {
            awaitLeader(admin);
            assertTrue(admin.execute(Op.CREATE, "t").ok());

            Thread load = new Thread(() -> {
                for (int i = 0; running.get(); i++) {
                    try {
                        String row = i + ",load";
                        if (writer.execute(Op.INSERT, "t", row).ok()) {
                            synchronized (acknowledged) {
                                acknowledged.add(row);
                            }
                        } else {
                            errors.add(new AssertionError("rejected " + row));
                        }
                    } catch (IOException e) {
                        errors.add(e);
                    }
                }
            });
            load.start();

            for (int round = 1; round <= 5; round++) {
                Thread.sleep(500);
                int victim = awaitLeader(admin);
                long killed = System.nanoTime();
                kill(victim);
                // Recovery time: until a fresh write succeeds.
                assertTrue(admin.execute(Op.INSERT, "t", (-round) + ",probe").ok());
                System.out.printf("round %d: killed leader %d, writes resumed after %d ms%n",
                        round, victim, (System.nanoTime() - killed) / 1_000_000);
                start(victim);
                Thread.sleep(500);
            }

            // Finally kill every node at once, in the middle of the load.
            for (int id : cluster.keySet()) {
                kill(id);
            }
            running.set(false);
            for (int id : cluster.keySet()) {
                start(id);
            }
            load.join();

            Set<String> stored = new HashSet<>(admin.execute(Op.SELECT, "t").rows());
            System.out.println("acknowledged=" + acknowledged.size()
                    + " stored=" + stored.size() + " errors=" + errors);
            assertTrue(errors.toString(), errors.isEmpty());
            assertTrue(acknowledged.size() > 20);
            assertTrue("acknowledged writes missing", stored.containsAll(acknowledged));
        }
    }
}
