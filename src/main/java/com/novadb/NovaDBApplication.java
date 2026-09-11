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

        // Give the servers time to start
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Create students table on all nodes
        node1.sendOperation(
                "localhost",
                node1.getPort(),
                "CREATE_TABLE",
                "students"
        );

        node2.sendOperation(
                "localhost",
                node2.getPort(),
                "CREATE_TABLE",
                "students"
        );

        node3.sendOperation(
                "localhost",
                node3.getPort(),
                "CREATE_TABLE",
                "students"
        );

        System.out.println();
        System.out.println("NovaDB cluster is ready.");
        System.out.println();

        // Start database console
        Console console = new Console(nodeManager);
        console.start();
    }
}