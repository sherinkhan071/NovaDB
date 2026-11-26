package com.novadb.cli;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.novadb.engine.Command.Op;
import com.novadb.engine.Result;
import com.novadb.net.Client;

/** Concurrent inserts against a running cluster. */
public final class Benchmark {

    private Benchmark() {
    }

    public static void run(Map<Integer, InetSocketAddress> cluster, int clients,
                           int operations) throws IOException, InterruptedException {

        if (clients < 1 || operations < 1) {
            throw new IOException("Clients and operations must be positive");
        }

        String table = "bench_" + System.currentTimeMillis();
        try (Client client = new Client(cluster)) {
            Result created = client.execute(Op.CREATE, table);
            if (!created.ok()) {
                throw new IOException(created.error());
            }
        }

        long[] latencies = new long[operations];
        AtomicInteger next = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        long start = System.nanoTime();

        for (int i = 0; i < clients; i++) {
            Thread thread = new Thread(() -> {
                try (Client client = new Client(cluster)) {
                    int n;
                    while ((n = next.getAndIncrement()) < operations) {
                        long before = System.nanoTime();
                        try {
                            if (!client.execute(Op.INSERT, table,
                                    n + ",Bench,22,Delhi").ok()) {
                                failed.incrementAndGet();
                            }
                        } catch (IOException e) {
                            failed.incrementAndGet();
                        }
                        latencies[n] = System.nanoTime() - before;
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        double seconds = (System.nanoTime() - start) / 1e9;
        Arrays.sort(latencies);
        System.out.printf("clients=%d writes=%d failed=%d%n", clients, operations, failed.get());
        System.out.printf("throughput=%.0f writes/sec  p50=%.1f ms  p99=%.1f ms%n",
                (operations - failed.get()) / seconds,
                latencies[(operations - 1) / 2] / 1e6,
                latencies[(int) Math.ceil(operations * 0.99) - 1] / 1e6);

        try (Client client = new Client(cluster)) {
            client.execute(Op.DROP, table);
        }
    }
}
