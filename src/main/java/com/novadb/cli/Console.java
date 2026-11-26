package com.novadb.cli;

import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.novadb.node.Node;
import com.novadb.node.NodeManager;
import com.novadb.node.Reply;

public class Console {

    private static final List<String> COLUMNS = List.of("id", "name", "age", "city");

    private static final Pattern CREATE = command("create\\s+table\\s+(\\S+)");
    private static final Pattern DROP = command("drop\\s+table\\s+(\\S+)");
    private static final Pattern INSERT = command("insert\\s+into\\s+(\\S+)\\s+(.+)");
    private static final Pattern SELECT = command("select\\s+\\*\\s+from\\s+(\\S+)");
    private static final Pattern DELETE = command(
            "delete\\s+from\\s+(\\S+)\\s+where\\s+id\\s*=\\s*(\\S+)");
    private static final Pattern UPDATE = command(
            "update\\s+(\\S+)\\s+set\\s+(\\w+)\\s*=\\s*(.+?)\\s+where\\s+id\\s*=\\s*(\\S+)");

    private final NodeManager cluster;

    public Console(NodeManager cluster) {
        this.cluster = cluster;
    }

    public void start() {

        Scanner scanner = new Scanner(System.in);
        System.out.println("Type 'help' for commands.");

        while (true) {
            System.out.print("novadb> ");

            if (!scanner.hasNextLine()) {
                break;
            }

            String line = scanner.nextLine().trim();

            if (line.equalsIgnoreCase("exit")) {
                System.out.println("Bye.");
                break;
            }

            if (!line.isEmpty()) {
                execute(line);
            }
        }
    }

    public void execute(String line) {

        Matcher m;

        if ((m = CREATE.matcher(line)).matches()) {
            print(cluster.createTable(m.group(1)));
        } else if ((m = DROP.matcher(line)).matches()) {
            print(cluster.dropTable(m.group(1)));
        } else if ((m = INSERT.matcher(line)).matches()) {
            print(cluster.insert(m.group(1), m.group(2)));
        } else if ((m = SELECT.matcher(line)).matches()) {
            select(m.group(1));
        } else if ((m = DELETE.matcher(line)).matches()) {
            print(cluster.delete(m.group(1), m.group(2)));
        } else if ((m = UPDATE.matcher(line)).matches()) {
            int column = COLUMNS.indexOf(m.group(2).toLowerCase());

            if (column < 0) {
                System.out.println("Error: Unknown column: " + m.group(2));
            } else {
                print(cluster.update(m.group(1), m.group(4), column, m.group(3)));
            }
        } else {
            switch (line.toLowerCase()) {
                case "show tables" -> showTables();
                case "health" -> health();
                case "stats" -> stats();
                case "benchmark" -> benchmark();
                case "help" -> help();
                default -> System.out.println(
                        "Error: Unknown or incomplete command. Type 'help'.");
            }
        }
    }

    private void select(String table) {

        Reply reply = cluster.select(table);

        if (!reply.ok()) {
            print(reply);
            return;
        }

        String[] rows = reply.body().isEmpty()
                ? new String[0] : reply.body().split(";");

        for (String row : rows) {
            System.out.println(row);
        }

        System.out.println("Total rows: " + rows.length);
    }

    private void showTables() {

        Reply reply = cluster.showTables();

        if (!reply.ok()) {
            print(reply);
        } else if (reply.body().isEmpty()) {
            System.out.println("No tables found.");
        } else {
            for (String table : reply.body().split(",")) {
                System.out.println("- " + table);
            }
        }
    }

    private void health() {

        for (Node node : cluster.getNodes()) {
            System.out.println(node.getNodeId() + " : "
                    + (cluster.health(node).ok() ? "ONLINE" : "OFFLINE"));
        }
    }

    private void stats() {

        for (Node node : cluster.getNodes()) {
            Reply reply = cluster.stats(node);

            if (reply.ok()) {
                String[] values = reply.body().split("\\|");
                System.out.println(node.getNodeId() + " : rows=" + values[0]
                        + " requests=" + values[1]);
            } else {
                System.out.println(node.getNodeId() + " : OFFLINE");
            }
        }
    }

    /** Sequential replicated inserts: one client, no concurrency. */
    private void benchmark() {

        String table = "bench_" + System.currentTimeMillis();

        if (!cluster.createTable(table).ok()) {
            System.out.println("Error: Could not create the benchmark table.");
            return;
        }

        int operations = 500;
        long[] latencies = new long[operations];
        long start = System.nanoTime();

        for (int i = 0; i < operations; i++) {
            long before = System.nanoTime();
            Reply reply = cluster.insert(table, i + ",Bench,22,Delhi");
            latencies[i] = System.nanoTime() - before;

            if (!reply.ok()) {
                print(reply);
                return;
            }
        }

        double seconds = (System.nanoTime() - start) / 1e9;
        Arrays.sort(latencies);

        System.out.printf("Replicated inserts : %d (1 client)%n", operations);
        System.out.printf("Throughput         : %.0f writes/sec%n", operations / seconds);
        System.out.printf("Latency p50        : %.2f ms%n",
                latencies[operations / 2] / 1e6);
        System.out.printf("Latency p99        : %.2f ms%n",
                latencies[operations * 99 / 100 - 1] / 1e6);

        cluster.dropTable(table);
    }

    private void help() {

        System.out.println("""
                CREATE TABLE <name>
                DROP TABLE <name>
                INSERT INTO <table> <id>,<name>,<age>,<city>
                SELECT * FROM <table>
                UPDATE <table> SET <column> = <value> WHERE id = <id>
                DELETE FROM <table> WHERE id = <id>
                SHOW TABLES
                HEALTH
                STATS
                BENCHMARK
                HELP
                EXIT""");
    }

    private static void print(Reply reply) {
        System.out.println((reply.ok() ? "OK: " : "Error: ") + reply.body());
    }

    private static Pattern command(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }
}
