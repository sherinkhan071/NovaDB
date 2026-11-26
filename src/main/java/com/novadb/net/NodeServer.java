package com.novadb.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.novadb.engine.Command;
import com.novadb.engine.Result;
import com.novadb.raft.RaftNode;
import com.novadb.raft.Rpc;
import com.novadb.raft.Storage;
import com.novadb.raft.Transport;

/** Runs one node: a Raft instance plus the TCP server in front of it. */
public class NodeServer implements AutoCloseable {

    private final RaftNode raft;
    private final ServerSocket server;
    private final Map<Integer, Connection> peers = new HashMap<>();

    public NodeServer(int id, Map<Integer, InetSocketAddress> cluster, Path directory)
            throws IOException {

        InetSocketAddress own = cluster.get(id);
        if (own == null) {
            throw new IOException("Node " + id + " is not in the cluster config");
        }

        // Open the port first: two processes must never share a data directory.
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(own.getHostString(), own.getPort()));

        List<Integer> others = new ArrayList<>(cluster.keySet());
        others.remove(Integer.valueOf(id));
        for (int peer : others) {
            peers.put(peer, new Connection(cluster.get(peer)));
        }

        Transport transport = (peer, request) -> {
            int timeout = request instanceof Rpc.Snapshot ? 5000 : 500;
            DataInputStream in = peers.get(peer).call(
                    Wire.bytes(out -> Wire.write(out, request)), timeout);
            return new Rpc.Reply(in.readLong(), in.readBoolean(), in.readLong());
        };

        raft = new RaftNode(id, others, new Storage(directory), transport,
                RaftNode.Config.defaults());
    }

    public void start() {
        raft.start();
        new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    Thread worker = new Thread(() -> serve(socket));
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException e) {
                    if (!server.isClosed()) {
                        System.err.println("Accept failed: " + e.getMessage());
                    }
                }
            }
        }, "acceptor").start();
    }

    private void serve(Socket socket) {
        try (socket;
             DataInputStream in = new DataInputStream(
                     new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(
                     new BufferedOutputStream(socket.getOutputStream()))) {
            while (!server.isClosed()) {
                DataInputStream request = new DataInputStream(
                        new ByteArrayInputStream(Wire.receive(in)));
                Wire.send(out, answer(request));
            }
        } catch (IOException | RuntimeException e) {
            // The connection ends: closed by the other side, or a bad request.
        }
    }

    private byte[] answer(DataInputStream request) throws IOException {
        byte type = request.readByte();

        if (type == Wire.STATUS) {
            RaftNode.Status s = raft.status();
            return Wire.bytes(out -> out.writeUTF("node=" + s.id() + " role=" + s.role()
                    + " term=" + s.term() + " leader=" + s.leader()
                    + " commit=" + s.commitIndex() + " last=" + s.lastIndex()
                    + " snapshot=" + s.snapshotIndex()));
        }

        if (type != Wire.COMMAND) {
            Rpc.Reply reply = raft.handle(Wire.readRequest(type, request));
            return Wire.bytes(out -> {
                out.writeLong(reply.term());
                out.writeBoolean(reply.success());
                out.writeLong(reply.index());
            });
        }

        try {
            Result result = raft.propose(Command.read(request)).get(2, TimeUnit.SECONDS);
            return Wire.bytes(out -> {
                out.writeByte(Wire.DONE);
                result.write(out);
            });
        } catch (ExecutionException e) {
            int hint = e.getCause() instanceof RaftNode.NotLeaderException n ? n.leader : -1;
            return notLeader(hint);
        } catch (TimeoutException e) {
            return notLeader(-1); // outcome unknown: the client retries safely
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return notLeader(-1);
        }
    }

    private static byte[] notLeader(int hint) throws IOException {
        return Wire.bytes(out -> {
            out.writeByte(Wire.NOT_LEADER);
            out.writeInt(hint);
        });
    }

    @Override
    public void close() throws IOException {
        server.close();
        raft.close();
        peers.values().forEach(Connection::close);
    }
}
