package com.novadb.node;

import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates the cluster. Every node holds a full copy of the data. A write
 * goes to the row's primary first and then to the other nodes.
 *
 * Known limit: if a replica fails after the primary has applied a write, the
 * copies differ. The failure is reported but not repaired; that needs consensus.
 */
public class NodeManager {

    private final List<Node> nodes = new ArrayList<>();

    public void addNode(Node node) {
        nodes.add(node);
    }

    public List<Node> getNodes() {
        return List.copyOf(nodes);
    }

    public void closeAll() {
        nodes.forEach(Node::close);
    }

    public Reply createTable(String table) {
        return write(0, "CREATE_TABLE|" + table);
    }

    public Reply dropTable(String table) {
        return write(0, "DROP_TABLE|" + table);
    }

    public Reply insert(String table, String row) {

        if (hasSeparator(table) || hasSeparator(row)) {
            return invalidCharacter();
        }

        String id = row.split(",", 2)[0];

        return writeRow(id, "INSERT|" + table + "|" + row);
    }

    public Reply update(String table, String id, int column, String value) {

        if (hasSeparator(table) || hasSeparator(id) || hasSeparator(value)) {
            return invalidCharacter();
        }

        return writeRow(id,
                "UPDATE|" + table + "|" + id + "|" + column + "|" + value);
    }

    public Reply delete(String table, String id) {

        if (hasSeparator(table) || hasSeparator(id)) {
            return invalidCharacter();
        }

        return writeRow(id, "DELETE|" + table + "|" + id);
    }

    /** Rows separated by ';', read from the first node that answers. */
    public Reply select(String table) {
        return read("QUERY|" + table);
    }

    /** Table names separated by ','. */
    public Reply showTables() {
        return read("SHOW_TABLES");
    }

    public Reply health(Node node) {
        return NodeClient.send(node.getPort(), "HEALTH");
    }

    /** Body is "rows|requests". */
    public Reply stats(Node node) {
        return NodeClient.send(node.getPort(), "STATS");
    }

    private Reply writeRow(String id, String request) {

        try {
            long key = Long.parseLong(id.trim());
            return write((int) Math.floorMod(key, (long) nodes.size()), request);
        } catch (NumberFormatException e) {
            return new Reply(false, "The id must be a whole number: " + id);
        }
    }

    private Reply write(int primaryIndex, String request) {

        if (nodes.isEmpty()) {
            return new Reply(false, "No nodes available");
        }

        Node primary = nodes.get(primaryIndex);
        Reply first = NodeClient.send(primary.getPort(), request);

        if (!first.ok()) {
            return new Reply(false, "Primary " + primary.getNodeId() + ": "
                    + first.body() + ". Replicas were not contacted.");
        }

        List<String> failures = new ArrayList<>();

        for (Node node : nodes) {
            if (node != primary) {
                Reply reply = NodeClient.send(node.getPort(), request);

                if (!reply.ok()) {
                    failures.add(node.getNodeId() + " (" + reply.body() + ")");
                }
            }
        }

        if (!failures.isEmpty()) {
            return new Reply(false, "Partial write: applied on "
                    + primary.getNodeId() + " but failed on "
                    + String.join(", ", failures));
        }

        return new Reply(true, "Written to all " + nodes.size()
                + " nodes (primary " + primary.getNodeId() + ")");
    }

    private Reply read(String request) {

        if (request.indexOf('\n') >= 0) {
            return invalidCharacter();
        }

        Reply last = new Reply(false, "No nodes available");

        for (Node node : nodes) {
            last = NodeClient.send(node.getPort(), request);

            if (last.ok()) {
                return last;
            }
        }

        return last;
    }

    private static boolean hasSeparator(String text) {
        return text.indexOf('|') >= 0 || text.indexOf(';') >= 0;
    }

    private static Reply invalidCharacter() {
        return new Reply(false, "Values cannot contain '|', ';' or line breaks");
    }
}
