package com.novadb.node;

import java.util.List;

public class ShardManager {

    private List<Node> nodes;

    public ShardManager(List<Node> nodes) {
        this.nodes = nodes;
    }

    public Node getNodeForRow(String rowData) {

        // Get the first value as the ID
        String[] values = rowData.split(",");

        if (values.length == 0) {
            return null;
        }

        try {

            int id = Integer.parseInt(values[0].trim());

            // Decide which node should store the row
            int nodeIndex = id % nodes.size();

            return nodes.get(nodeIndex);

        } catch (NumberFormatException e) {

            System.out.println(
                    "Invalid row ID: " + values[0]
            );

            return null;
        }
    }

    public void showShardLocation(String rowData) {

        Node node = getNodeForRow(rowData);

        if (node == null) {
            System.out.println(
                    "Could not determine shard."
            );
            return;
        }

        System.out.println(
                "Row [" + rowData + "] belongs to "
                        + node.getNodeId()
        );
    }
}