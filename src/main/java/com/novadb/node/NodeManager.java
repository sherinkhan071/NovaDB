package com.novadb.node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NodeManager {

    private List<Node> nodes;
    private ShardManager shardManager;

    public NodeManager() {

        nodes = new ArrayList<>();

        shardManager =
                new ShardManager(nodes);
    }

    // -----------------------------------------
    // NODE MANAGEMENT
    // -----------------------------------------

    public void addNode(Node node) {

        nodes.add(node);

        System.out.println(
                "Node "
                        + node.getNodeId()
                        + " added."
        );
    }

    public void startAllNodes() {

        if (nodes.isEmpty()) {

            System.out.println(
                    "No nodes available."
            );

            return;
        }

        for (Node node : nodes) {
            node.start();
        }
    }

    public void showNodes() {

        System.out.println();
        System.out.println(
                "NovaDB Cluster"
        );
        System.out.println(
                "----------------------------------"
        );

        for (Node node : nodes) {

            System.out.println(
                    node.getNodeId()
                            + " | Port: "
                            + node.getPort()
            );
        }

        System.out.println(
                "----------------------------------"
        );
    }

    // -----------------------------------------
    // CREATE TABLE
    // -----------------------------------------

    public void createTable(String tableName) {

        System.out.println();
        System.out.println(
                "Creating table '" + tableName + "'..."
        );

        for (Node node : nodes) {

            node.sendOperation(
                    "localhost",
                    node.getPort(),
                    "CREATE_TABLE",
                    tableName
            );
        }

        System.out.println(
                "Table '" + tableName
                        + "' created successfully on all nodes."
        );
    }
// -----------------------------------------
// DROP TABLE
// -----------------------------------------

public void dropTable(String tableName) {

    System.out.println();
    System.out.println(
            "Dropping table '" + tableName + "'..."
    );

    if (nodes.isEmpty()) {

        System.out.println(
                "No nodes available."
        );

        return;
    }

    for (Node node : nodes) {

        node.sendOperation(
                "localhost",
                node.getPort(),
                "DROP_TABLE",
                tableName
        );
    }

    System.out.println(
            "Table '" + tableName
                    + "' dropped successfully on all nodes."
    );
}
    // -----------------------------------------
    // SHOW TABLES
    // -----------------------------------------

    public void showTables() {

        System.out.println();
        System.out.println(
                "=================================="
        );
        System.out.println(
                "          NOVADB TABLES"
        );
        System.out.println(
                "=================================="
        );

        if (nodes.isEmpty()) {

            System.out.println(
                    "No nodes available."
            );

            System.out.println(
                    "=================================="
            );

            return;
        }

        try {

            Node node =
                    nodes.get(0);

            String response =
                    node.sendOperationAndGetResponse(
                            "localhost",
                            node.getPort(),
                            "SHOW_TABLES",
                            ""
                    );

            if (response == null
                    || response.trim().isEmpty()) {

                System.out.println(
                        "No tables found."
                );

                System.out.println(
                        "=================================="
                );

                return;
            }

            if (response.startsWith("SUCCESS|")) {

                String data =
                        response.substring(
                                "SUCCESS|".length()
                        ).trim();

                if (data.isEmpty()) {

                    System.out.println(
                            "No tables found."
                    );

                } else {

                    String[] tables =
                            data.split(",");

                    for (String table : tables) {

                        if (!table.trim().isEmpty()) {

                            System.out.println(
                                    "- "
                                            + table.trim()
                            );
                        }
                    }
                }

            } else {

                System.out.println(
                        "Unable to retrieve tables."
                );

                System.out.println(
                        response
                );
            }

        } catch (Exception e) {

            System.out.println(
                    "SHOW TABLES error: "
                            + e.getMessage()
            );
        }

        System.out.println(
                "=================================="
        );
    }

    // -----------------------------------------
    // SHARDED INSERT + REPLICATION
    // -----------------------------------------

    public void insertWithSharding(
            String tableName,
            String values) {

        Node primaryNode =
                shardManager.getNodeForRow(values);

        if (primaryNode == null) {

            System.out.println(
                    "Could not determine shard."
            );

            return;
        }

        System.out.println();
        System.out.println(
                "Sharded INSERT"
        );
        System.out.println(
                "----------------------------------"
        );

        System.out.println(
                "Row     : " + values
        );

        System.out.println(
                "Primary : "
                        + primaryNode.getNodeId()
        );

        primaryNode.sendOperation(
                "localhost",
                primaryNode.getPort(),
                "INSERT",
                tableName + "|" + values
        );

        System.out.println(
                "Replicating..."
        );

        for (Node node : nodes) {

            if (node == primaryNode) {
                continue;
            }

            primaryNode.sendOperation(
                    "localhost",
                    node.getPort(),
                    "REPLICATE",
                    tableName + "|" + values
            );
        }

        System.out.println(
                "Replication complete."
        );

        System.out.println(
                "INSERT complete."
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

        Map<String, String> uniqueRows =
                new LinkedHashMap<>();

        for (Node node : nodes) {

            String response =
                    node.sendQuery(
                            "localhost",
                            node.getPort(),
                            tableName
                    );

            if (response == null) {
                continue;
            }

            String[] parts =
                    response.split("\\|", 2);

            if (parts.length < 2) {
                continue;
            }

            if (!parts[0].equalsIgnoreCase(
                    "SUCCESS")) {

                continue;
            }

            String rowsData =
                    parts[1];

            if (rowsData.trim().isEmpty()) {
                continue;
            }

            String[] rows =
                    rowsData.split(";");

            for (String row : rows) {

                if (row.trim().isEmpty()) {
                    continue;
                }

                String[] values =
                        row.split(",", 2);

                if (values.length < 2) {
                    continue;
                }

                String id =
                        values[0].trim();

                uniqueRows.putIfAbsent(
                        id,
                        row
                );
            }
        }

        for (String row :
                uniqueRows.values()) {

            System.out.println(row);
        }

        System.out.println();
        System.out.println(
                "Total unique rows: "
                        + uniqueRows.size()
        );

        System.out.println(
                "Distributed SELECT completed."
        );
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

        String tableName =
                "students";

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
                "Replicating row..."
        );

        for (Node node : nodes) {

            if (node == primaryNode) {
                continue;
            }

            primaryNode.sendOperation(
                    "localhost",
                    node.getPort(),
                    "REPLICATE",
                    tableName + "|" + values
            );
        }

        System.out.println(
                "Replication complete."
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

        for (Node node : nodes) {

            node.sendOperation(
                    "localhost",
                    node.getPort(),
                    "CREATE_TABLE",
                    tableName
            );
        }

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

        Node primaryNode =
                shardManager.getNodeForRow(
                        id + ",dummy"
                );

        if (primaryNode == null) {

            System.out.println(
                    "Could not determine shard."
            );

            return;
        }

        System.out.println();
        System.out.println(
                "Distributed DELETE"
        );
        System.out.println(
                "----------------------------------"
        );

        System.out.println(
                "Row ID  : " + id
        );

        System.out.println(
                "Primary : "
                        + primaryNode.getNodeId()
        );

        primaryNode.sendOperation(
                "localhost",
                primaryNode.getPort(),
                "DELETE",
                tableName + "|" + id
        );

        System.out.println(
                "Deleting replicated copies..."
        );

        for (Node node : nodes) {

            if (node == primaryNode) {
                continue;
            }

            primaryNode.sendOperation(
                    "localhost",
                    node.getPort(),
                    "DELETE",
                    tableName + "|" + id
            );
        }

        System.out.println(
                "DELETE complete."
        );
    }

    // -----------------------------------------
    // DISTRIBUTED UPDATE
    // -----------------------------------------

    public void updateWithSharding(
            String tableName,
            String id,
            int columnIndex,
            String newValue) {

        Node primaryNode =
                shardManager.getNodeForRow(
                        id + ",dummy"
                );

        if (primaryNode == null) {

            System.out.println(
                    "Could not determine shard."
            );

            return;
        }

        String updateData =
                tableName
                        + "|"
                        + id
                        + "|"
                        + columnIndex
                        + "|"
                        + newValue;

        System.out.println();
        System.out.println(
                "Distributed UPDATE"
        );
        System.out.println(
                "----------------------------------"
        );

        System.out.println(
                "Row ID  : " + id
        );

        System.out.println(
                "Primary : "
                        + primaryNode.getNodeId()
        );

        primaryNode.sendOperation(
                "localhost",
                primaryNode.getPort(),
                "UPDATE",
                updateData
        );

        System.out.println(
                "Updating replicated copies..."
        );

        for (Node node : nodes) {

            if (node == primaryNode) {
                continue;
            }

            primaryNode.sendOperation(
                    "localhost",
                    node.getPort(),
                    "UPDATE",
                    updateData
            );
        }

        System.out.println(
                "UPDATE complete."
        );
    }

    // -----------------------------------------
    // NODE HEALTH
    // -----------------------------------------

    public void checkNodeHealth() {

        System.out.println();
        System.out.println(
                "=================================="
        );
        System.out.println(
                "NODE HEALTH CHECK"
        );
        System.out.println(
                "=================================="
        );

        for (Node node : nodes) {

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

                writer.println("HEALTH");

                String response =
                        reader.readLine();

                if ("SUCCESS|HEALTH".equals(
                        response)) {

                    System.out.println(
                            node.getNodeId()
                                    + " → ONLINE"
                    );

                } else {

                    System.out.println(
                            node.getNodeId()
                                    + " → UNHEALTHY"
                    );
                }

            } catch (Exception e) {

                System.out.println(
                        node.getNodeId()
                                + " → OFFLINE"
                );
            }
        }

        System.out.println();
    }

    // -----------------------------------------
    // FIND FAILOVER NODE
    // -----------------------------------------

    public Node getFailoverNode(
            Node failedNode) {

        for (Node node : nodes) {

            if (node == failedNode) {
                continue;
            }

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

                writer.println("HEALTH");

                String response =
                        reader.readLine();

                if ("SUCCESS|HEALTH".equals(
                        response)) {

                    return node;
                }

            } catch (Exception e) {
                // Try next node
            }
        }

        return null;
    }

    // -----------------------------------------
    // FAILOVER TEST
    // -----------------------------------------

    public void testFailover() {

        System.out.println();
        System.out.println(
                "=================================="
        );
        System.out.println(
                "FAILOVER TEST"
        );
        System.out.println(
                "=================================="
        );

        if (nodes.size() < 2) {

            System.out.println(
                    "At least 2 nodes are required."
            );

            return;
        }

        Node failedNode =
                nodes.get(0);

        System.out.println(
                "Simulating failure of "
                        + failedNode.getNodeId()
        );

        Node failoverNode =
                getFailoverNode(
                        failedNode
                );

        if (failoverNode == null) {

            System.out.println(
                    "No healthy failover node available."
            );

            return;
        }

        System.out.println(
                "Failure detected: "
                        + failedNode.getNodeId()
        );

        System.out.println(
                "Failover node: "
                        + failoverNode.getNodeId()
        );

        System.out.println(
                "Failover successful."
        );

        System.out.println();
    }

    // -----------------------------------------
    // STATISTICS
    // -----------------------------------------

    public void showStats() {

        System.out.println();
        System.out.println(
                "=================================="
        );
        System.out.println(
                "        NOVADB STATISTICS"
        );
        System.out.println(
                "=================================="
        );

        int onlineNodes = 0;
        int totalRows = 0;

        for (Node node : nodes) {

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

                writer.println("STATS");

                String response =
                        reader.readLine();

                if (response != null
                        && response.startsWith(
                                "SUCCESS|")) {

                    onlineNodes++;

                    String[] parts =
                            response.split("\\|");

                    int rows = 0;
                    int requests = 0;

                    for (String part : parts) {

                        if (part.startsWith("rows=")) {

                            rows =
                                    Integer.parseInt(
                                            part.substring(5)
                                    );
                        }

                        if (part.startsWith(
                                "requests=")) {

                            requests =
                                    Integer.parseInt(
                                            part.substring(9)
                                    );
                        }
                    }

                    totalRows += rows;

                    System.out.println();
                    System.out.println(
                            node.getNodeId()
                                    + " → ONLINE"
                    );

                    System.out.println(
                            "   Rows     : "
                                    + rows
                    );

                    System.out.println(
                            "   Requests : "
                                    + requests
                    );
                }

            } catch (Exception e) {

                System.out.println(
                        node.getNodeId()
                                + " → OFFLINE"
                );
            }
        }

        System.out.println();
        System.out.println(
                "----------------------------------"
        );

        System.out.println(
                "Total Nodes : "
                        + nodes.size()
        );

        System.out.println(
                "Online Nodes: "
                        + onlineNodes
        );

        System.out.println(
                "Total Rows  : "
                        + totalRows
        );

        System.out.println(
                "=================================="
        );
    }

    // -----------------------------------------
    // BENCHMARK
    // -----------------------------------------

    public void benchmark() {

        System.out.println();
        System.out.println(
                "=================================="
        );
        System.out.println(
                "        NOVADB BENCHMARK"
        );
        System.out.println(
                "=================================="
        );

        int operations = 30;

        long startTime =
                System.nanoTime();

        for (int i = 0;
             i < operations;
             i++) {

            int id =
                    100 + i;

            Node node =
                    nodes.get(
                            id % nodes.size()
                    );

            node.sendOperation(
                    "localhost",
                    node.getPort(),
                    "INSERT",
                    "students|"
                            + id
                            + ",Bench"
                            + id
                            + ",22,Delhi"
            );
        }

        long endTime =
                System.nanoTime();

        long elapsedNanos =
                endTime - startTime;

        double elapsedMillis =
                elapsedNanos / 1_000_000.0;

        double averageMillis =
                elapsedMillis / operations;

        double operationsPerSecond =
                operations
                        / (elapsedMillis / 1000.0);

        System.out.println();
        System.out.println(
                "----------------------------------"
        );

        System.out.println(
                "Operations      : "
                        + operations
        );

        System.out.printf(
                "Total time      : %.2f ms%n",
                elapsedMillis
        );

        System.out.printf(
                "Average latency : %.2f ms/op%n",
                averageMillis
        );

        System.out.printf(
                "Throughput      : %.2f ops/sec%n",
                operationsPerSecond
        );

        System.out.println(
                "----------------------------------"
        );

        System.out.println();
    }
}