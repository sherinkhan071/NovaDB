package com.novadb.engine;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Outcome of a command: rows (or names) on success, otherwise an error. */
public record Result(boolean ok, String error, List<String> rows) {

    public static Result success() {
        return new Result(true, "", List.of());
    }

    public static Result of(List<String> rows) {
        return new Result(true, "", List.copyOf(rows));
    }

    public static Result failure(String error) {
        // Errors can echo user input, so keep them short.
        return new Result(false,
                error.length() > 200 ? error.substring(0, 200) : error, List.of());
    }

    public void write(DataOutput out) throws IOException {
        out.writeBoolean(ok);
        out.writeUTF(error);
        out.writeInt(rows.size());
        for (String row : rows) {
            out.writeUTF(row);
        }
    }

    public static Result read(DataInput in) throws IOException {
        boolean ok = in.readBoolean();
        String error = in.readUTF();
        int count = in.readInt();
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(in.readUTF());
        }
        return new Result(ok, error, rows);
    }
}
