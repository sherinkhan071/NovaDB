
package com.novadb.node;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

import com.novadb.engine.Database;
import com.novadb.engine.WriteAheadLog;
import com.novadb.model.Row;
import com.novadb.model.Table;

public class Node {

    private String nodeId;
    private int port;

    private Database database;
    private WriteAheadLog wal;
    private int requestCount = 0;

    public Node(String nodeId, int port) {

        this.nodeId = nodeId;
        this.port = port;

        this.database = new Database(
                "novadb-" + nodeId.toLowerCase() + ".data"
        );

        this.wal = new WriteAheadLog(nodeId);

        this.wal.recover(database);
    }

    public String getNodeId() {
        return nodeId;
    }

    public int getPort() {
        return port;
    }

    // -----------------------------------------
    // START NODE
    // -----------------------------------------

    public void start() {

        Thread serverThread = new Thread(() -> {

            try (
                    ServerSocket serverSocket =
                            new ServerSocket(port)
            ) {

                System.out.println(
                        nodeId
                                + " listening on port "
                                + port
                );

                while (true) {

                    Socket socket =
                            serverSocket.accept();

                    Thread clientThread =
                            new Thread(() ->
                                    handleConnection(socket)
                            );

                    clientThread.start();
                }

            } catch (Exception e) {

                System.out.println(
                        nodeId
                                + " could not start: "
                                + e.getMessage()
                );
            }

        });

        serverThread.start();
    }

    // -----------------------------------------
    // HANDLE CONNECTION
    // -----------------------------------------

    private void handleConnection(Socket socket) {

        try (
                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        socket.getInputStream()
                                )
                        );

                PrintWriter writer =
                        new PrintWriter(
                                socket.getOutputStream(),
                                true
                        )
        ) {

            String message =
                    reader.readLine();

            if (message == null || message.trim().isEmpty()) {

                writer.println(
                        "ERROR|Empty message"
                );

                return;
            }

            NodeMessage nodeMessage =
                    parseMessage(message);

            String result =
                    processOperation(nodeMessage);

            writer.println(result);

        } catch (Exception e) {

            System.out.println(
                    nodeId
                            + " connection error: "
                            + e.getMessage()
            );
        }
    }

    // -----------------------------------------
    // PARSE MESSAGE
    // -----------------------------------------

    private NodeMessage parseMessage(
            String message) {

        String[] parts =
                message.split("\\|", 2);

        String operation =
                parts[0];

        String data = "";

        if (parts.length > 1) {
            data = parts[1];
        }

        return new NodeMessage(
                operation,
                data
        );
    }

    // -----------------------------------------
    // PROCESS OPERATION
    // -----------------------------------------

    private synchronized String processOperation(
            NodeMessage message) {

        requestCount++;

        String operation =
                message.getOperation();

        String data =
                message.getData();

        if (operation.equalsIgnoreCase(
                "CREATE_TABLE")) {

            processCreateTable(data);

            return "SUCCESS|CREATE_TABLE";
        }

        else if (operation.equalsIgnoreCase(
                "INSERT")) {

            processInsert(data);

            return "SUCCESS|INSERT";
        }

        else if (operation.equalsIgnoreCase(
                "HEALTH")) {

            return "SUCCESS|HEALTH";
        }

        else if (operation.equalsIgnoreCase(
                "STATS")) {

            return processStats();
        }

        else if (operation.equalsIgnoreCase(
                "QUERY")) {

            return processQuery(data);
        }

        else if (operation.equalsIgnoreCase(
                "DELETE")) {

            return processDelete(data);
        }

        else if (operation.equalsIgnoreCase(
                "UPDATE")) {

            return processUpdate(data);
        }

        else if (operation.equalsIgnoreCase(
                "SHOW_TABLES")) {

            return processShowTables();
        }

        else if (operation.equalsIgnoreCase(
                "REPLICATE")) {

            processInsert(data);

            return "SUCCESS|REPLICATE";
        }

        else {

            return "ERROR|Unknown operation";
        }
    }

    // -----------------------------------------
    // CREATE TABLE
    // -----------------------------------------

    private void processCreateTable(
            String tableName) {

        if (database.getTable(tableName) != null) {
            return;
        }

        String command =
                "create table "
                        + tableName;

        database.execute(command);
    }

    // -----------------------------------------
    // INSERT
    // -----------------------------------------

    private void processInsert(
            String data) {

        String[] parts =
                data.split("\\|", 2);

        if (parts.length < 2) {
            return;
        }

        String tableName =
                parts[0];

        String values =
                parts[1];

        // WAL before database operation
        wal.log(
                "INSERT",
                tableName
                        + "|"
                        + values
        );

        String command =
                "insert into "
                        + tableName
                        + " "
                        + values;

        database.execute(command);
    }

    // -----------------------------------------
    // QUERY
    // -----------------------------------------

    private String processQuery(
            String tableName) {

        Table table =
                database.getTable(tableName);

        if (table == null) {

            return "ERROR|Table not found";
        }

        StringBuilder result =
                new StringBuilder();

        for (Row row :
                table.getRows()) {

            for (int i = 0;
                 i < row.getValues().size();
                 i++) {

                if (i > 0) {
                    result.append(",");
                }

                result.append(
                        row.getValues().get(i)
                );
            }

            result.append(";");
        }

        if (result.length() == 0) {
            return "SUCCESS|";
        }

        return "SUCCESS|"
                + result;
    }

    // -----------------------------------------
    // SHOW TABLES
    // -----------------------------------------

    private String processShowTables() {

        StringBuilder result =
                new StringBuilder();

        /*
         * Database exposes its tables through
         * getTables().
         */
        for (String tableName :
                database.getTables().keySet()) {

            if (result.length() > 0) {
                result.append(",");
            }

            result.append(tableName);
        }

        return "SUCCESS|"
                + result;
    }

    // -----------------------------------------
    // DELETE
    // -----------------------------------------

    private String processDelete(
            String data) {

        String[] parts =
                data.split("\\|", 2);

        if (parts.length < 2) {

            return "ERROR|Invalid DELETE data";
        }

        String tableName =
                parts[0];

        String id =
                parts[1].trim();

        Table table =
                database.getTable(tableName);

        if (table == null) {

            return "ERROR|Table not found";
        }

        int beforeSize =
                table.getRows().size();

        table.getRows().removeIf(
                row ->
                        !row.getValues().isEmpty()
                                && row.getValues()
                                .get(0)
                                .trim()
                                .equals(id)
        );

        int afterSize =
                table.getRows().size();

        boolean deleted =
                beforeSize != afterSize;

        if (deleted) {

            wal.log(
                    "DELETE",
                    tableName
                            + "|"
                            + id
            );

            database.saveDatabase();

            return "SUCCESS|DELETE";
        }

        return "ERROR|Row not found";
    }

    // -----------------------------------------
    // UPDATE
    // -----------------------------------------

    private String processUpdate(
            String data) {

        String[] parts =
                data.split("\\|", 4);

        if (parts.length < 4) {

            return "ERROR|Invalid UPDATE data";
        }

        String tableName =
                parts[0];

        String id =
                parts[1].trim();

        int columnIndex;

        try {

            columnIndex =
                    Integer.parseInt(
                            parts[2].trim()
                    );

        } catch (NumberFormatException e) {

            return "ERROR|Invalid column index";
        }

        String newValue =
                parts[3].trim();

        Table table =
                database.getTable(tableName);

        if (table == null) {

            return "ERROR|Table not found";
        }

        for (Row row :
                table.getRows()) {

            if (row.getValues().isEmpty()) {
                continue;
            }

            if (row.getValues()
                    .get(0)
                    .trim()
                    .equals(id)) {

                if (columnIndex < 0
                        || columnIndex
                        >= row.getValues().size()) {

                    return "ERROR|Column index out of range";
                }

                row.getValues()
                        .set(
                                columnIndex,
                                newValue
                        );

                wal.log(
                        "UPDATE",
                        tableName
                                + "|"
                                + id
                                + "|"
                                + columnIndex
                                + "|"
                                + newValue
                );

                database.saveDatabase();

                return "SUCCESS|UPDATE";
            }
        }

        return "ERROR|Row not found";
    }

    // -----------------------------------------
    // SEND MESSAGE
    // -----------------------------------------

    public void sendMessage(
            String host,
            int targetPort,
            String message) {

        try (
                Socket socket =
                        new Socket(
                                host,
                                targetPort
                        );

                PrintWriter writer =
                        new PrintWriter(
                                socket.getOutputStream(),
                                true
                        );

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        socket.getInputStream()
                                )
                        )
        ) {

            writer.println(message);

            reader.readLine();

        } catch (Exception e) {

            System.out.println(
                    nodeId
                            + " could not send message: "
                            + e.getMessage()
            );
        }
    }

    // -----------------------------------------
    // SEND QUERY
    // -----------------------------------------

    public String sendQuery(
            String host,
            int targetPort,
            String tableName) {

        try (
                Socket socket =
                        new Socket(
                                host,
                                targetPort
                        );

                PrintWriter writer =
                        new PrintWriter(
                                socket.getOutputStream(),
                                true
                        );

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        socket.getInputStream()
                                )
                        )
        ) {

            NodeMessage message =
                    new NodeMessage(
                            "QUERY",
                            tableName
                    );

            writer.println(
                    message.toString()
            );

            return reader.readLine();

        } catch (Exception e) {

            return "ERROR|"
                    + e.getMessage();
        }
    }

    // -----------------------------------------
    // SEND OPERATION
    // -----------------------------------------

    public void sendOperation(
            String host,
            int targetPort,
            String operation,
            String data) {

        NodeMessage message =
                new NodeMessage(
                        operation,
                        data
                );

        sendMessage(
                host,
                targetPort,
                message.toString()
        );
    }

    // -----------------------------------------
    // STATISTICS
    // -----------------------------------------

    private String processStats() {

        int rowCount = 0;

        Table table =
                database.getTable("students");

        if (table != null) {

            rowCount =
                    table.getRows().size();
        }

        return "SUCCESS|"
                + nodeId
                + "|rows="
                + rowCount
                + "|requests="
                + requestCount;
    }
    public String sendOperationAndGetResponse(
        String host,
        int targetPort,
        String operation,
        String data) {

    try (
        java.net.Socket socket =
                new java.net.Socket(host, targetPort);

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

        NodeMessage message =
                new NodeMessage(operation, data);

        writer.println(message.toString());

        return reader.readLine();

    } catch (Exception e) {

        return "ERROR|" + e.getMessage();
    }
}
}
