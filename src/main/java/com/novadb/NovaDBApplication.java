package com.novadb;

import java.io.IOException;
import java.nio.file.Path;

import com.novadb.cli.Benchmark;
import com.novadb.cli.Console;
import com.novadb.net.Client;
import com.novadb.net.ClusterConfig;
import com.novadb.net.NodeServer;

public class NovaDBApplication {

    private static final String USAGE = """
            Usage:
              node  <config> <id> <data directory>
              cli   <config>
              bench <config> <clients> <writes>""";

    public static void main(String[] args) throws Exception {

        if (args.length < 2) {
            System.out.println(USAGE);
            System.exit(1);
        }

        var cluster = ClusterConfig.load(Path.of(args[1]));

        if (args[0].equals("node") && args.length == 4) {
            NodeServer node = new NodeServer(
                    Integer.parseInt(args[2]), cluster, Path.of(args[3]));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    node.close();
                } catch (IOException e) {
                    System.err.println("Shutdown error: " + e.getMessage());
                }
            }));
            node.start();
            System.out.println("Node " + args[2] + " running. Press Ctrl+C to stop.");
        } else if (args[0].equals("cli")) {
            try (Client client = new Client(cluster)) {
                new Console(client).start();
            }
        } else if (args[0].equals("bench") && args.length == 4) {
            Benchmark.run(cluster, Integer.parseInt(args[2]), Integer.parseInt(args[3]));
        } else {
            System.out.println(USAGE);
            System.exit(1);
        }
    }
}
