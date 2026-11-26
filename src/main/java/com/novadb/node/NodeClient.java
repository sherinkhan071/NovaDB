package com.novadb.node;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Sends one request to a node. A timeout means the outcome is unknown. */
final class NodeClient {

    private static final int TIMEOUT_MS = 2000;

    private NodeClient() {
    }

    static Reply send(int port, String request) {

        if (request.indexOf('\n') >= 0 || request.indexOf('\r') >= 0) {
            return new Reply(false, "Line breaks are not allowed");
        }

        try (Socket socket = new Socket()) {

            socket.connect(new InetSocketAddress(
                    InetAddress.getLoopbackAddress(), port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);

            new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)
                    .println(request);

            String response = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8)).readLine();

            if (response == null) {
                return new Reply(false, "No response");
            }

            String[] parts = response.split("\\|", 2);

            return new Reply(parts[0].equals("SUCCESS"),
                    parts.length > 1 ? parts[1] : "");

        } catch (IOException e) {
            return new Reply(false, "Unreachable: " + e.getMessage());
        }
    }
}
