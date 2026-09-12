package com.novadb;

import com.novadb.cli.Console;
import com.novadb.node.Node;
import com.novadb.node.NodeManager;

public class NovaDBApplication {

    public static void main(String[] args) {

        System.out.println("==================================");
        System.out.println("      Welcome to NovaDB v1.0");
        System.out.println("==================================");

        NodeManager nodeManager = new NodeManager();

        Node node1 = new Node("Node-1", 5001);
        Node node2 = new Node("Node-2", 5002);
        Node node3 = new Node("Node-3", 5003);

        nodeManager.addNode(node1);
        nodeManager.addNode(node2);
        nodeManager.addNode(node3);

        nodeManager.showNodes();

        // Start all database nodes
        nodeManager.startAllNodes();

        // Give servers time to start
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Create students table on all nodes
        createTableIfNeeded(node1);
        createTableIfNeeded(node2);
        createTableIfNeeded(node3);

        System.out.println();
        System.out.println("NovaDB cluster is ready.");
        System.out.println();

        // Start database console
        Console console = new Console(nodeManager);
        console.start();
    }

    private static void createTableIfNeeded(Node node) {

        try {
            String response = sendCreateTable(node);

            if (response != null &&
                    response.startsWith("SUCCESS")) {
                return;
            }

        } catch (Exception e) {
            System.out.println(
                    "Could not initialize "
                            + node.getNodeId()
                            + ": "
                            + e.getMessage()
            );
        }
    }

    private static String sendCreateTable(Node node) {

        try (
                java.net.Socket socket =
                        new java.net.Socket(
                                "localhost",
                                node.getPort()
                        );

                java.io.PrintWriter writer =
                        new java.io.PrintWriter(
                                socket.getOutputStream(),
                                true
                        );

                java.io.BufferedReader reader =
                        new java.io.BufferedReader(
                                new java.io.InputStreamReader(
                                        socket.getInputStream()
                                )
                        )
        ) {

            writer.println(
                    "CREATE_TABLE|students"
            );

            return reader.readLine();

        } catch (Exception e) {

            throw new RuntimeException(e);
        }
    }
}