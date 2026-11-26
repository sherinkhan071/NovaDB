package com.novadb.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The state machine: tables held in memory. It must be deterministic, so that
 * every node applying the same commands in the same order ends up identical.
 * It is not thread-safe; the Raft node calls it while holding its lock.
 */
public class Database {

    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final int MAX_ROW_LENGTH = 4000;

    /** The last write applied for one client, and what it returned. */
    private record Session(long sequence, Result result) {
    }

    // table name -> row id -> values
    private final Map<String, Map<Long, String[]>> tables = new TreeMap<>();
    private final Map<String, Session> sessions = new TreeMap<>();

    public Result apply(Command command) {

        if (!command.op().isWrite()) {
            return run(command);
        }

        Session last = sessions.get(command.clientId());

        if (last != null && command.sequence() <= last.sequence()) {
            return command.sequence() == last.sequence()
                    ? last.result() : Result.failure("Outdated request");
        }

        Result result = run(command);
        sessions.put(command.clientId(), new Session(command.sequence(), result));
        return result;
    }

    private Result run(Command command) {

        List<String> a = command.args();

        try {
            return switch (command.op()) {
                case NOOP -> Result.success();
                case CREATE -> create(a.get(0));
                case DROP -> {
                    table(a.get(0));
                    tables.remove(a.get(0));
                    yield Result.success();
                }
                case INSERT -> insert(a.get(0), a.get(1));
                case UPDATE -> update(a.get(0), a.get(1),
                        Integer.parseInt(a.get(2)), a.get(3));
                case DELETE -> {
                    Map<Long, String[]> table = table(a.get(0));
                    long id = id(a.get(1));
                    row(table, id);
                    table.remove(id);
                    yield Result.success();
                }
                case SELECT -> Result.of(table(a.get(0)).values().stream()
                        .map(values -> String.join(",", values)).toList());
                case TABLES -> Result.of(new ArrayList<>(tables.keySet()));
            };
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (IndexOutOfBoundsException e) {
            return Result.failure("Malformed command");
        }
    }

    private Result create(String name) {

        if (!TABLE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid table name: " + name);
        }
        if (tables.putIfAbsent(name, new LinkedHashMap<>()) != null) {
            throw new IllegalArgumentException("Table already exists: " + name);
        }
        return Result.success();
    }

    private Result insert(String name, String row) {

        checkLength(row);
        String[] values = row.split(",", -1);

        for (int i = 0; i < values.length; i++) {
            values[i] = values[i].trim();
        }

        long id = id(values[0]);
        values[0] = Long.toString(id);

        if (table(name).putIfAbsent(id, values) != null) {
            throw new IllegalArgumentException("Duplicate id: " + id);
        }
        return Result.success();
    }

    private Result update(String name, String rowId, int column, String value) {

        String[] values = row(table(name), id(rowId));

        if (column == 0) {
            throw new IllegalArgumentException("The id column cannot be changed");
        }
        if (column < 0 || column >= values.length) {
            throw new IllegalArgumentException("Column index out of range");
        }
        if (value.contains(",")) {
            throw new IllegalArgumentException("A value cannot contain ','");
        }

        String[] changed = values.clone();
        changed[column] = value.trim();
        checkLength(String.join(",", changed));
        table(name).put(id(rowId), changed);
        return Result.success();
    }

    public byte[] snapshot() {

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(tables.size());

            for (var table : tables.entrySet()) {
                out.writeUTF(table.getKey());
                out.writeInt(table.getValue().size());

                for (String[] values : table.getValue().values()) {
                    out.writeUTF(String.join(",", values));
                }
            }

            out.writeInt(sessions.size());

            for (var session : sessions.entrySet()) {
                out.writeUTF(session.getKey());
                out.writeLong(session.getValue().sequence());
                session.getValue().result().write(out);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return bytes.toByteArray();
    }

    public void restore(byte[] snapshot) {

        tables.clear();
        sessions.clear();

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(snapshot))) {
            for (int t = in.readInt(); t > 0; t--) {
                String name = in.readUTF();
                Map<Long, String[]> table = new LinkedHashMap<>();
                tables.put(name, table);

                for (int r = in.readInt(); r > 0; r--) {
                    String[] values = in.readUTF().split(",", -1);
                    table.put(Long.parseLong(values[0]), values);
                }
            }

            for (int s = in.readInt(); s > 0; s--) {
                sessions.put(in.readUTF(), new Session(in.readLong(), Result.read(in)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<Long, String[]> table(String name) {
        Map<Long, String[]> table = tables.get(name);
        if (table == null) {
            throw new IllegalArgumentException("Table not found: " + name);
        }
        return table;
    }

    private static String[] row(Map<Long, String[]> table, long id) {
        String[] values = table.get(id);
        if (values == null) {
            throw new IllegalArgumentException("Row not found: " + id);
        }
        return values;
    }

    private static long id(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The id must be a whole number: " + text);
        }
    }

    private static void checkLength(String row) {
        if (row.length() > MAX_ROW_LENGTH) {
            throw new IllegalArgumentException("Row is too long");
        }
    }
}
