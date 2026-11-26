package com.novadb.node;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import com.novadb.engine.Database;
import com.novadb.engine.DatabaseException;

/** One database server. Requests are single lines: OPERATION|argument|... */
public class Node {

    private static final int TIMEOUT_MS = 5000;

    private final String nodeId;
    private final int requestedPort;
    private final Database database;
    private final AtomicInteger requestCount = new AtomicInteger();

    private volatile ServerSocket serverSocket;
    private Thread acceptor;

    /** @param port TCP port, or 0 to let the system choose one */
    public Node(String nodeId, int port, Path dataDirectory) {

        this.nodeId = nodeId;
        this.requestedPort = port;
        this.database = new Database(
                dataDirectory.resolve("novadb-" + nodeId.toLowerCase() + ".wal"));
    }

    public String getNodeId() {
        return nodeId;
    }

    public int getPort() {
        return serverSocket == null ? requestedPort : serverSocket.getLocalPort();
    }

    /** The port is open when this returns; it throws if the port is taken. */
    public void start() throws IOException {

        ServerSocket server = new ServerSocket(
                requestedPort, 50, InetAddress.getLoopbackAddress());
        serverSocket = server;

        acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    Thread worker = new Thread(() -> handle(socket));
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException e) {
                    if (!server.isClosed()) {
                        System.out.println(nodeId + " accept error: " + e.getMessage());
                    }
                }
            }
        }, nodeId + "-acceptor");

        acceptor.start();
    }

    /** Stops accepting connections. The data stays open until close(). */
    public void stop() {

        try {
            if (serverSocket != null) {
                serverSocket.close();
            }

            // The port is only released once the accept thread has returned.
            if (acceptor != null) {
                acceptor.join();
            }
        } catch (IOException e) {
            System.out.println(nodeId + " stop error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void close() {

        stop();

        try {
            database.close();
        } catch (IOException e) {
            System.out.println(nodeId + " close error: " + e.getMessage());
        }
    }

    private void handle(Socket socket) {

        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(
                     socket.getOutputStream(), true, StandardCharsets.UTF_8)) {

            socket.setSoTimeout(TIMEOUT_MS);

            String request = reader.readLine();

            if (request != null) {
                writer.println(process(request));
            }

        } catch (IOException e) {
            System.out.println(nodeId + " connection error: " + e.getMessage());
        }
    }

    private String process(String request) {

        requestCount.incrementAndGet();

        String[] f = request.split("\\|", -1);

        try {
            switch (f[0]) {
                case "CREATE_TABLE" -> {
                    require(f, 2);
                    database.createTable(f[1]);
                }
                case "DROP_TABLE" -> {
                    require(f, 2);
                    database.dropTable(f[1]);
                }
                case "INSERT" -> {
                    require(f, 3);
                    database.insert(f[1], f[2]);
                }
                case "UPDATE" -> {
                    require(f, 5);
                    database.update(f[1], f[2], Integer.parseInt(f[3]), f[4]);
                }
                case "DELETE" -> {
                    require(f, 3);
                    database.delete(f[1], f[2]);
                }
                case "QUERY" -> {
                    require(f, 2);
                    return "SUCCESS|" + String.join(";", database.select(f[1]));
                }
                case "SHOW_TABLES" -> {
                    return "SUCCESS|" + String.join(",", database.tableNames());
                }
                case "HEALTH" -> {
                    return "SUCCESS|HEALTH";
                }
                case "STATS" -> {
                    return "SUCCESS|" + database.rowCount() + "|" + requestCount.get();
                }
                default -> {
                    return "ERROR|Unknown operation";
                }
            }

            return "SUCCESS|" + f[0];

        } catch (DatabaseException e) {
            return "ERROR|" + e.getMessage();
        } catch (NumberFormatException e) {
            return "ERROR|Invalid number";
        }
    }

    private static void require(String[] fields, int count) {

        if (fields.length != count) {
            throw new DatabaseException("Malformed request");
        }
    }
}
