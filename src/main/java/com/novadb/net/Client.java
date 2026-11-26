package com.novadb.net;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.novadb.engine.Command;
import com.novadb.engine.Result;

/** Finds the leader and retries. Use one client per thread. */
public class Client implements AutoCloseable {

    private static final int TIMEOUT_MS = 3000;
    private static final long DEADLINE_MS = 10_000;

    private final Map<Integer, Connection> nodes = new TreeMap<>();
    private final List<Integer> ids;
    private final String clientId = UUID.randomUUID().toString();
    private long sequence;
    private int target;

    public Client(Map<Integer, InetSocketAddress> cluster) {
        cluster.forEach((id, address) -> nodes.put(id, new Connection(address)));
        ids = new ArrayList<>(nodes.keySet());
        target = ids.get(0);
    }

    /** Throws if no answer arrives in time; a write may or may not have happened. */
    public Result execute(Command.Op op, String... args) throws IOException {

        // Retries reuse this sequence number, so the write is applied once.
        Command command = new Command(op, List.of(args), clientId, ++sequence);
        byte[] request = Wire.bytes(out -> {
            out.writeByte(Wire.COMMAND);
            command.write(out);
        });
        long deadline = System.currentTimeMillis() + DEADLINE_MS;

        while (System.currentTimeMillis() < deadline) {
            int hint = -1;
            try {
                DataInputStream in = nodes.get(target).call(request, TIMEOUT_MS);
                if (in.readByte() == Wire.DONE) {
                    return Result.read(in);
                }
                hint = in.readInt();
            } catch (IOException e) {
                // try another node
            }
            if (nodes.containsKey(hint) && hint != target) {
                target = hint;
            } else {
                target = ids.get((ids.indexOf(target) + 1) % ids.size());
                sleep(50);
            }
        }
        throw new IOException("No answer from the cluster; the outcome is unknown. "
                + "Check that a majority of nodes is running.");
    }

    /** One line describing a node, or null if it cannot be reached. */
    public String status(int id) {
        try {
            return nodes.get(id).call(new byte[] {Wire.STATUS}, 1000).readUTF();
        } catch (IOException e) {
            return null;
        }
    }

    public List<Integer> nodeIds() {
        return List.copyOf(ids);
    }

    @Override
    public void close() {
        nodes.values().forEach(Connection::close);
    }

    private static void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
    }
}
