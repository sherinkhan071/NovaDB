package com.novadb.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** A reusable connection carrying one request at a time. */
final class Connection implements AutoCloseable {

    private final InetSocketAddress address;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;

    Connection(InetSocketAddress address) {
        this.address = address;
    }

    synchronized DataInputStream call(byte[] request, int timeoutMs) throws IOException {
        try {
            if (socket == null) {
                socket = new Socket();
                // Resolve each time, so a restarted container is found again.
                socket.connect(new InetSocketAddress(
                        address.getHostString(), address.getPort()), timeoutMs);
                socket.setTcpNoDelay(true);
                in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            }
            socket.setSoTimeout(timeoutMs);
            Wire.send(out, request);
            return new DataInputStream(new java.io.ByteArrayInputStream(Wire.receive(in)));
        } catch (IOException e) {
            close(); // a late reply must not be mistaken for the next one
            throw e;
        }
    }

    @Override
    public synchronized void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing useful to do when closing fails
            }
            socket = null;
        }
    }
}
