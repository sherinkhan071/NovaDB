package com.novadb.node;

import java.util.ArrayList;
import java.util.List;

public class NodeManager {

    private List<Node> nodes;

    private ShardManager shardManager;

    public NodeManager() {

        nodes = new ArrayList<>();

        shardManager = new ShardManager(nodes);
    }

    public void addNode(Node node) {

        nodes.add(node);

        System.out.println(
                "Node " + node.getNodeId()
                        + " added successfully."
        );
    }

    public void startAllNodes() {

        if (nodes.isEmpty()) {

            System.out.println(
                    "No nodes available."
            );

            return;
        }

        System.out.println(
                "Starting NovaDB cluster..."
        );

        for (Node node : nodes) {

            node.start();
        }
    }

    public void showNodes() {

        if (nodes.isEmpty()) {

            System.out.println(
                    "No nodes available."
            );

            return;
        }

        System.out.println(
                "NovaDB Nodes:"
        );

        for (Node node : nodes) {

            System.out.println(
                    "- " + node.getNodeId()
                            + " | Port: "
                            + node.getPort()
            );
        }
    }

    // -----------------------------------------
    // SHARDING
    // -----------------------------------------

    public void insertWithSharding(
            String tableName,
            String values) {

        System.out.println();

        System.out.println(
                "Starting sharded insert..."
        );

        Node targetNode =
                shardManager.getNodeForRow(values);

        if (targetNode == null) {

            System.out.println(
                    "Could not determine shard."
            );

            return;
        }

        System.out.println(
                "Row: " + values
        );

        System.out.println(
                "Assigned shard: "
                        + targetNode.getNodeId()
        );

        System.out.println(
                "Sending row to "
                        + targetNode.getNodeId()
        );

        targetNode.sendOperation(
                "localhost",
                targetNode.getPort(),
                "INSERT",
                tableName + "|" + values
        );

        System.out.println(
                "Sharded insert completed."
        );
    }

    // -----------------------------------------
    // DISTRIBUTED SELECT
    // -----------------------------------------

    public void distributedSelect(
            String tableName) {

        System.out.println();

        System.out.println(
                "=================================="
        );

        System.out.println(
                "DISTRIBUTED SELECT"
        );

        System.out.println(
                "=================================="
        );

        System.out.println();

        boolean foundData = false;

        for (Node node : nodes) {

            System.out.println(
                    "Querying "
                            + node.getNodeId()
                            + "..."
            );

            String response =
                    node.sendQuery(
                            "localhost",
                            node.getPort(),
                            tableName
                    );

            if (response == null) {

                System.out.println(
                        "No response from "
                                + node.getNodeId()
                );

                continue;
            }

            if (response.startsWith(
                    "SUCCESS|")) {

                String data =
                        response.substring(8);

                if (!data.isEmpty()) {

                    foundData = true;

                    String[] rows =
                            data.split(";");

                    for (String row : rows) {

                        if (!row.isEmpty()) {

                            System.out.println(
                                    node.getNodeId()
                                            + " → "
                                            + row
                            );
                        }
                    }

                } else {

                    System.out.println(
                            node.getNodeId()
                                    + " → No rows"
                    );
                }

            } else {

                System.out.println(
                        node.getNodeId()
                                + " → "
                                + response
                );
            }
        }

        System.out.println();

        if (foundData) {

            System.out.println(
                    "Distributed SELECT completed."
            );

        } else {

            System.out.println(
                    "No data found."
            );
        }
    }

    // -----------------------------------------
    // SHARDING TEST
    // -----------------------------------------

    public void testSharding() {

        if (nodes.size() < 3) {

            System.out.println(
                    "At least 3 nodes are required."
            );

            return;
        }

        String tableName = "students";

        String[] rows = {
                "1,Alice,22,Delhi",
                "2,Bob,23,Gurugram",
                "3,Charlie,24,Noida",
                "4,David,21,Delhi",
                "5,Ethan,23,Gurugram",
                "6,Frank,22,Noida"
        };

        System.out.println();

        System.out.println(
                "=================================="
        );

        System.out.println(
                "SHARDING TEST"
        );

        System.out.println(
                "=================================="
        );

        System.out.println();

        for (String row : rows) {

            shardManager.showShardLocation(row);

            insertWithSharding(
                    tableName,
                    row
            );
        }
    }

    // -----------------------------------------
    // REPLICATION
    // -----------------------------------------

    public void replicateInsert(
            Node primaryNode,
            String tableName,
            String values) {

        System.out.println();

        System.out.println(
                "Starting automatic replication..."
        );

        for (Node node : nodes) {

            if (node == primaryNode) {

                continue;
            }

            System.out.println(
                    primaryNode.getNodeId()
                            + " -> "
                            + node.getNodeId()
                            + " : REPLICATE"
            );

            primaryNode.sendOperation(
                    "localhost",
                    node.getPort(),
                    "REPLICATE",
                    tableName + "|" + values
            );
        }

        System.out.println(
                "Replication completed."
        );
    }

    // -----------------------------------------
    // OLD REPLICATION TEST
    // -----------------------------------------

    public void testAutomaticReplication() {

        if (nodes.size() < 3) {

            System.out.println(
                    "At least 3 nodes are required."
            );

            return;
        }

        Node primaryNode =
                nodes.get(0);

        String tableName =
                "students";

        String values =
                "5,Ethan,23,Gurugram";

        System.out.println();

        System.out.println(
                "=================================="
        );

        System.out.println(
                "AUTOMATIC REPLICATION TEST"
        );

        System.out.println(
                "=================================="
        );

        System.out.println();

        System.out.println(
                "Creating students table on all nodes..."
        );

        for (Node node : nodes) {

            node.sendOperation(
                    "localhost",
                    node.getPort(),
                    "CREATE_TABLE",
                    tableName
            );
        }

        System.out.println();

        System.out.println(
                "Primary node: "
                        + primaryNode.getNodeId()
        );

        System.out.println(
                "Inserting: "
                        + values
        );

        primaryNode.sendOperation(
                "localhost",
                primaryNode.getPort(),
                "INSERT",
                tableName + "|" + values
        );

        replicateInsert(
                primaryNode,
                tableName,
                values
        );
    }
    // -----------------------------------------
// DISTRIBUTED DELETE
// -----------------------------------------

public void deleteWithSharding(
        String tableName,
        String id) {

    System.out.println();

    System.out.println(
            "Starting distributed delete..."
    );

    // Create a row-like value so ShardManager
    // can determine the correct node.
    String rowData =
            id + ",dummy";

    Node targetNode =
            shardManager.getNodeForRow(rowData);

    if (targetNode == null) {

        System.out.println(
                "Could not determine shard."
        );

        return;
    }

    System.out.println(
            "Row ID: " + id
    );

    System.out.println(
            "Target shard: "
                    + targetNode.getNodeId()
    );

    System.out.println(
            "Sending DELETE to "
                    + targetNode.getNodeId()
    );

    targetNode.sendOperation(
            "localhost",
            targetNode.getPort(),
            "DELETE",
            tableName + "|" + id
    );

    System.out.println(
            "Distributed delete completed."
    );
}
}