package com.novadb.raft;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import com.novadb.engine.Command;

/** One position in the replicated log. */
public record Entry(long index, long term, Command command) {

    public void write(DataOutput out) throws IOException {
        out.writeLong(index);
        out.writeLong(term);
        command.write(out);
    }

    public static Entry read(DataInput in) throws IOException {
        return new Entry(in.readLong(), in.readLong(), Command.read(in));
    }
}
