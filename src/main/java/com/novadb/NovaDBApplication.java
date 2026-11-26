package com.novadb;

import java.io.IOException;
import java.nio.file.Path;

import com.novadb.cli.Console;
import com.novadb.node.Node;
import com.novadb.node.NodeManager;

public class NovaDBApplication {

    public static void main(String[] args) {

        NodeManager cluster = new NodeManager();

        try {
            for (int i = 1; i <= 3; i++) {
                Node node = new Node("Node-" + i, 5000 + i, Path.of("."));
                cluster.addNode(node);
                node.start();
                System.out.println(node.getNodeId() + " listening on port " + node.getPort());
            }

            if (cluster.showTables().body().isEmpty()) {
                cluster.createTable("students");
            }

            new Console(cluster).start();

        } catch (IOException e) {
            System.out.println("Could not start the cluster: " + e.getMessage());
        } finally {
            cluster.closeAll();
        }
    }
}
