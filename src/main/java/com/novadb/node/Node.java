package com.novadb.node;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

import com.novadb.engine.Database;
import com.novadb.model.Row;
import com.novadb.model.Table;

public class Node {

    private String nodeId;
    private int port;

    private Database database;

    public Node(String nodeId, int port) {

        this.nodeId = nodeId;
        this.port = port;

        // Each node has its own database file
        this.database = new Database(
                "novadb-" + nodeId.toLowerCase() + ".data"
        );
    }

    public String getNodeId() {
        return nodeId;
    }

    public int getPort() {
        return port;
    }

    public void start() {

        Thread serverThread = new Thread(() -> {

            try (ServerSocket serverSocket =
                         new ServerSocket(port)) {

                System.out.println(
                        nodeId
                                + " listening on port "
                                + port
                );

                while (true) {

                    Socket socket =
                            serverSocket.accept();

                    handleConnection(socket);
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

            String message = reader.readLine();

            System.out.println();
            System.out.println(
                    nodeId
                            + " received: "
                            + message
            );

            NodeMessage nodeMessage =
                    parseMessage(message);

            String result =
                    processOperation(nodeMessage);

            writer.println(result);

            socket.close();

        } catch (Exception e) {

            System.out.println(
                    nodeId
                            + " connection error: "
                            + e.getMessage()
            );
        }
    }

    private NodeMessage parseMessage(String message) {

        String[] parts =
                message.split("\\|", 2);

        String operation = parts[0];

        String data = "";

        if (parts.length > 1) {
            data = parts[1];
        }

        return new NodeMessage(
                operation,
                data
        );
    }

    private String processOperation(
            NodeMessage message) {

        String operation =
                message.getOperation();

        String data =
                message.getData();

        System.out.println(
                nodeId
                        + " processing: "
                        + operation
        );

        // CREATE TABLE
        if (operation.equalsIgnoreCase(
                "CREATE_TABLE")) {

            processCreateTable(data);

            return "SUCCESS|CREATE_TABLE";
        }

        // INSERT
        else if (operation.equalsIgnoreCase(
                "INSERT")) {

            processInsert(data);

            return "SUCCESS|INSERT";
        }

        // QUERY
        else if (operation.equalsIgnoreCase(
                "QUERY")) {

            return processQuery(data);
        }

        // DELETE
        else if (operation.equalsIgnoreCase(
                "DELETE")) {

            return processDelete(data);
        }

        // REPLICATE
        else if (operation.equalsIgnoreCase(
                "REPLICATE")) {

            processInsert(data);

            return "SUCCESS|REPLICATE";
        }

        else {

            System.out.println(
                    "Unknown operation: "
                            + operation
            );

            return "ERROR|Unknown operation";
        }
    }

    private void processCreateTable(
            String tableName) {

        String command =
                "create table " + tableName;

        System.out.println(
                nodeId
                        + " executing: "
                        + command
        );

        database.execute(command);
    }

    private void processInsert(
            String data) {

        /*
         * Expected:
         *
         * students|5,Ethan,23,Gurugram
         */

        String[] parts =
                data.split("\\|", 2);

        if (parts.length < 2) {

            System.out.println(
                    "Invalid INSERT data."
            );

            return;
        }

        String tableName =
                parts[0];

        String values =
                parts[1];

        String command =
                "insert into "
                        + tableName
                        + " "
                        + values;

        System.out.println(
                nodeId
                        + " executing: "
                        + command
        );

        database.execute(command);
    }

    private String processQuery(
            String tableName) {

        Table table =
                database.getTable(tableName);

        if (table == null) {

            return "ERROR|Table not found";
        }

        StringBuilder result =
                new StringBuilder();

        for (Row row : table.getRows()) {

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

        return "SUCCESS|"
                + result;
    }

    // -----------------------------------------
    // DELETE
    // -----------------------------------------

    private String processDelete(
            String data) {

        /*
         * Expected:
         *
         * students|7
         *
         * The second value is the row ID.
         */

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

        boolean deleted = false;

        for (int i = 0;
             i < table.getRows().size();
             i++) {

            Row row =
                    table.getRows().get(i);

            if (!row.getValues().isEmpty()
                    && row.getValues()
                          .get(0)
                          .trim()
                          .equals(id)) {

                table.getRows().remove(i);

                deleted = true;

                break;
            }
        }

        if (deleted) {

            database.saveDatabase();

            System.out.println(
                    "Row with ID "
                            + id
                            + " deleted from "
                            + nodeId
            );

            return "SUCCESS|DELETE";
        }

        return "ERROR|Row not found";
    }

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

            String response =
                    reader.readLine();

            System.out.println(
                    nodeId
                            + " received response: "
                            + response
            );

        } catch (Exception e) {

            System.out.println(
                    nodeId
                            + " could not send message: "
                            + e.getMessage()
            );
        }
    }

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
}