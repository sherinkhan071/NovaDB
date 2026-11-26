package com.novadb.engine;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * One database request. clientId and sequence identify a write, so that a retry
 * of the same write is applied only once.
 */
public record Command(Op op, List<String> args, String clientId, long sequence) {

    public enum Op {
        NOOP, CREATE, DROP, INSERT, UPDATE, DELETE, SELECT, TABLES;

        public boolean isWrite() {
            return this == CREATE || this == DROP || this == INSERT
                    || this == UPDATE || this == DELETE;
        }
    }

    public static Command noop() {
        return new Command(Op.NOOP, List.of(), "", 0);
    }

    public void write(DataOutput out) throws IOException {
        out.writeByte(op.ordinal());
        out.writeInt(args.size());
        for (String arg : args) {
            out.writeUTF(arg);
        }
        out.writeUTF(clientId);
        out.writeLong(sequence);
    }

    public static Command read(DataInput in) throws IOException {
        Op op = Op.values()[in.readByte()];
        int count = in.readInt();
        List<String> args = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            args.add(in.readUTF());
        }
        return new Command(op, List.copyOf(args), in.readUTF(), in.readLong());
    }
}
