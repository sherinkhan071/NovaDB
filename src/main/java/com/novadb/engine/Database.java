package com.novadb.engine;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.novadb.model.Row;
import com.novadb.model.Table;

/**
 * Every change follows the same three steps: validate, append to the log,
 * apply to memory. Recovery runs the "apply" step on each logged record.
 */
public class Database implements Closeable {

    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Map<String, Table> tables = new TreeMap<>();
    private final WriteAheadLog log;

    public Database(Path logFile) {

        try {
            log = new WriteAheadLog(logFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open " + logFile, e);
        }

        int line = 0;

        for (String record : log.recoveredRecords()) {
            line++;

            try {
                apply(record.split("\\|", -1));
            } catch (RuntimeException e) {
                // Refuse to start rather than run with silently missing data.
                throw new DatabaseException(
                        "Corrupt log " + logFile + " at line " + line);
            }
        }
    }

    public synchronized void createTable(String name) {

        if (!TABLE_NAME.matcher(name).matches()) {
            throw new DatabaseException("Invalid table name: " + name);
        }

        if (tables.containsKey(name)) {
            throw new DatabaseException("Table already exists: " + name);
        }

        commit("CREATE", name);
    }

    public synchronized void dropTable(String name) {

        table(name);
        commit("DROP", name);
    }

    /** @param row comma-separated values; the first is the numeric id */
    public synchronized void insert(String tableName, String row) {

        checkCharacters(row);

        String[] values = row.split(",", -1);

        for (int i = 0; i < values.length; i++) {
            values[i] = values[i].trim();
        }

        values[0] = parseId(values[0]);

        if (table(tableName).find(values[0]) != null) {
            throw new DatabaseException("Duplicate id: " + values[0]);
        }

        commit("INSERT", tableName, String.join(",", values));
    }

    public synchronized void update(
            String tableName, String id, int column, String value) {

        checkCharacters(value);

        if (value.contains(",")) {
            throw new DatabaseException("A value cannot contain ','");
        }

        Row row = existingRow(tableName, parseId(id));

        if (column == 0) {
            throw new DatabaseException("The id column cannot be changed");
        }

        if (column < 0 || column >= row.getValues().size()) {
            throw new DatabaseException("Column index out of range");
        }

        commit("UPDATE", tableName, parseId(id),
                Integer.toString(column), value.trim());
    }

    public synchronized void delete(String tableName, String id) {

        existingRow(tableName, parseId(id));
        commit("DELETE", tableName, parseId(id));
    }

    /** Rows as comma-separated strings, copied so callers cannot change them. */
    public synchronized List<String> select(String tableName) {

        List<String> result = new ArrayList<>();

        for (Row row : table(tableName).getRows()) {
            result.add(String.join(",", row.getValues()));
        }

        return result;
    }

    public synchronized List<String> tableNames() {
        return new ArrayList<>(tables.keySet());
    }

    public synchronized int rowCount() {
        return tables.values().stream().mapToInt(t -> t.getRows().size()).sum();
    }

    @Override
    public synchronized void close() throws IOException {
        log.close();
    }

    private void commit(String... fields) {

        try {
            log.append(String.join("|", fields));
        } catch (IOException e) {
            throw new DatabaseException("Could not write log: " + e.getMessage());
        }

        apply(fields);
    }

    private void apply(String[] f) {

        switch (f[0]) {
            case "CREATE" -> tables.put(f[1], new Table());
            case "DROP" -> tables.remove(f[1]);
            case "INSERT" -> {
                Row row = new Row();

                for (String value : f[2].split(",", -1)) {
                    row.addValue(value);
                }

                table(f[1]).addRow(row);
            }
            case "UPDATE" -> existingRow(f[1], f[2])
                    .getValues().set(Integer.parseInt(f[3]), f[4]);
            case "DELETE" -> table(f[1]).getRows().remove(existingRow(f[1], f[2]));
            default -> throw new DatabaseException("Unknown record: " + f[0]);
        }
    }

    private Table table(String name) {

        Table table = tables.get(name);

        if (table == null) {
            throw new DatabaseException("Table not found: " + name);
        }

        return table;
    }

    private Row existingRow(String tableName, String id) {

        Row row = table(tableName).find(id);

        if (row == null) {
            throw new DatabaseException("Row not found: " + id);
        }

        return row;
    }

    private static String parseId(String id) {

        try {
            return Long.toString(Long.parseLong(id.trim()));
        } catch (NumberFormatException e) {
            throw new DatabaseException("The id must be a whole number: " + id);
        }
    }

    // These characters separate fields and rows in the log and wire formats.
    private static void checkCharacters(String text) {

        if (text.matches("(?s).*[|;\\r\\n].*")) {
            throw new DatabaseException(
                    "Values cannot contain '|', ';' or line breaks");
        }
    }
}
