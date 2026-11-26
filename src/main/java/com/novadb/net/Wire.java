package com.novadb.net;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.novadb.raft.Entry;
import com.novadb.raft.Rpc;

/** Binary protocol. Each message is a 4-byte length followed by that many bytes. */
final class Wire {

    static final byte VOTE = 1;
    static final byte APPEND = 2;
    static final byte SNAPSHOT = 3;
    static final byte COMMAND = 4;
    static final byte STATUS = 5;

    static final byte DONE = 0;
    static final byte NOT_LEADER = 1;

    private static final int MAX_FRAME = 64 * 1024 * 1024;

    private Wire() {
    }

    interface Body {
        void write(DataOutputStream out) throws IOException;
    }

    static byte[] bytes(Body body) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        body.write(new DataOutputStream(buffer));
        return buffer.toByteArray();
    }

    static void send(DataOutputStream out, byte[] frame) throws IOException {
        if (frame.length > MAX_FRAME) {
            throw new IOException("Message too large");
        }
        out.writeInt(frame.length);
        out.write(frame);
        out.flush();
    }

    static byte[] receive(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_FRAME) {
            throw new IOException("Invalid message length");
        }
        byte[] frame = new byte[length];
        in.readFully(frame);
        return frame;
    }

    static void write(DataOutputStream out, Rpc.Request request) throws IOException {
        if (request instanceof Rpc.Vote v) {
            out.writeByte(VOTE);
            out.writeLong(v.term());
            out.writeInt(v.candidate());
            out.writeLong(v.lastIndex());
            out.writeLong(v.lastTerm());
        } else if (request instanceof Rpc.Append a) {
            out.writeByte(APPEND);
            out.writeLong(a.term());
            out.writeInt(a.leader());
            out.writeLong(a.prevIndex());
            out.writeLong(a.prevTerm());
            out.writeInt(a.entries().size());
            for (Entry entry : a.entries()) {
                entry.write(out);
            }
            out.writeLong(a.leaderCommit());
        } else {
            Rpc.Snapshot s = (Rpc.Snapshot) request;
            out.writeByte(SNAPSHOT);
            out.writeLong(s.term());
            out.writeInt(s.leader());
            out.writeLong(s.lastIndex());
            out.writeLong(s.lastTerm());
            out.writeInt(s.data().length);
            out.write(s.data());
        }
    }

    static Rpc.Request readRequest(byte type, DataInputStream in) throws IOException {
        if (type == VOTE) {
            return new Rpc.Vote(in.readLong(), in.readInt(), in.readLong(), in.readLong());
        }
        if (type == APPEND) {
            long term = in.readLong();
            int leader = in.readInt();
            long prevIndex = in.readLong();
            long prevTerm = in.readLong();
            List<Entry> entries = new ArrayList<>();
            for (int i = in.readInt(); i > 0; i--) {
                entries.add(Entry.read(in));
            }
            return new Rpc.Append(term, leader, prevIndex, prevTerm, entries, in.readLong());
        }
        if (type == SNAPSHOT) {
            long term = in.readLong();
            int leader = in.readInt();
            long lastIndex = in.readLong();
            long lastTerm = in.readLong();
            int length = in.readInt();
            if (length < 0 || length > MAX_FRAME) {
                throw new IOException("Invalid snapshot length");
            }
            byte[] data = new byte[length];
            in.readFully(data);
            return new Rpc.Snapshot(term, leader, lastIndex, lastTerm, data);
        }
        throw new IOException("Unknown message type " + type);
    }
}
