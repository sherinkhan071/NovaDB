
package com.novadb.engine;

import java.util.HashMap;
import java.util.Map;

import com.novadb.model.Row;
import com.novadb.model.Table;

public class Database {

    private Map<String, Table> tables;
    private String fileName;

    public Database() {

        tables = new HashMap<>();

        fileName = "novadb.data";

        FileManager.load(tables, fileName);
    }

    public Database(String fileName) {

        tables = new HashMap<>();

        this.fileName = fileName;

        FileManager.load(tables, fileName);
    }

    // -----------------------------------------
    // NORMAL EXECUTE
    // -----------------------------------------

    public synchronized void execute(String command) {

        execute(command, false);
    }

    // -----------------------------------------
    // SILENT EXECUTE
    // Used by WAL recovery
    // -----------------------------------------

    public synchronized void execute(
            String command,
            boolean silent) {

        command = command.trim();

        // CREATE TABLE
        if (command.toLowerCase()
                .startsWith("create table")) {

            String tableName =
                    command.substring(13).trim();

            createTable(
                    tableName,
                    silent
            );
        }

        // INSERT INTO
        else if (command.toLowerCase()
                .startsWith("insert into")) {

            String[] parts =
                    command.split("\\s+", 4);

            if (parts.length < 4) {

                if (!silent) {
                    System.out.println(
                            "Invalid INSERT command."
                    );
                }

                return;
            }

            String tableName = parts[2];
            String value = parts[3];

            insertInto(
                    tableName,
                    value,
                    silent
            );
        }

        // SELECT * FROM
        else if (command.toLowerCase()
                .startsWith("select * from")) {

            String remaining =
                    command.substring(13).trim();

            // SELECT WHERE
            if (remaining.toLowerCase()
                    .contains(" where ")) {

                String[] parts =
                        remaining.split(
                                "(?i) where ",
                                2
                        );

                String tableName =
                        parts[0].trim();

                String searchValue =
                        parts[1].trim();

                selectWhere(
                        tableName,
                        searchValue
                );

            } else {

                selectAll(remaining);
            }
        }

        // DELETE FROM
        else if (command.toLowerCase()
                .startsWith("delete from")) {

            String[] parts =
                    command.split("\\s+", 4);

            if (parts.length < 4) {

                if (!silent) {
                    System.out.println(
                            "Invalid DELETE command."
                    );
                }

                return;
            }

            String tableName = parts[2];
            String value = parts[3];

            deleteRow(
                    tableName,
                    value,
                    silent
            );
        }

        // DROP TABLE
        else if (command.toLowerCase()
                .startsWith("drop table")) {

            String tableName =
                    command.substring(10).trim();

            dropTable(
                    tableName,
                    silent
            );
        }

        // UPDATE
        else if (command.toLowerCase()
                .startsWith("update")) {

            String[] parts =
                    command.split("\\s+", 4);

            if (parts.length < 4) {

                if (!silent) {
                    System.out.println(
                            "Invalid UPDATE command."
                    );
                }

                return;
            }

            String tableName = parts[1];
            String oldValue = parts[2];
            String newValue = parts[3];

            updateRow(
                    tableName,
                    oldValue,
                    newValue,
                    silent
            );
        }

        // DESCRIBE
        else if (command.toLowerCase()
                .startsWith("describe")) {

            String tableName =
                    command.substring(8).trim();

            describeTable(tableName);
        }

        // SAVE
        else if (command.equalsIgnoreCase("save")) {

            FileManager.save(
                    tables,
                    fileName
            );
        }

        // SHOW TABLES
        else if (command.equalsIgnoreCase(
                "show tables")) {

            showTables();
        }

        else {

            if (!silent) {
                System.out.println(
                        "Unknown command."
                );
            }
        }
    }

    // -----------------------------------------
    // CREATE TABLE
    // -----------------------------------------

    private void createTable(
            String tableName,
            boolean silent) {

        if (tables.containsKey(tableName)) {

            if (!silent) {
                System.out.println(
                        "Table already exists."
                );
            }

            return;
        }

        tables.put(
                tableName,
                new Table()
        );

        if (!silent) {

            System.out.println(
                    "Table '" + tableName +
                    "' created successfully."
            );
        }

        FileManager.save(
                tables,
                fileName,
                silent
        );
    }

    // -----------------------------------------
    // INSERT
    // -----------------------------------------

    private void insertInto(
            String tableName,
            String value,
            boolean silent) {

        Table table =
                tables.get(tableName);

        if (table == null) {

            if (!silent) {
                System.out.println(
                        "Table does not exist."
                );
            }

            return;
        }

        Row row = new Row();

        String[] values =
                value.split(",");

        for (String v : values) {

            row.addValue(
                    v.trim()
            );
        }

        table.addRow(row);

        if (!silent) {

            System.out.println(
                    "Row inserted successfully."
            );
        }

        FileManager.save(
                tables,
                fileName,
                silent
        );
    }

    // -----------------------------------------
    // SELECT ALL
    // -----------------------------------------

    private void selectAll(
            String tableName) {

        Table table =
                tables.get(tableName);

        if (table == null) {

            System.out.println(
                    "Table not found."
            );

            return;
        }

        if (table.getRows().isEmpty()) {

            System.out.println(
                    "Table is empty."
            );

            return;
        }

        System.out.println("Rows:");

        for (Row row :
                table.getRows()) {

            for (String value :
                    row.getValues()) {

                System.out.print(
                        value + " "
                );
            }

            System.out.println();
        }
    }

    // -----------------------------------------
    // SELECT WHERE
    // -----------------------------------------

    private void selectWhere(
            String tableName,
            String searchValue) {

        Table table =
                tables.get(tableName);

        if (table == null) {

            System.out.println(
                    "Table not found."
            );

            return;
        }

        if (table.getRows().isEmpty()) {

            System.out.println(
                    "Table is empty."
            );

            return;
        }

        boolean found = false;

        System.out.println(
                "Matching Rows:"
        );

        for (Row row :
                table.getRows()) {

            if (row.getValues()
                    .contains(searchValue)) {

                found = true;

                for (String value :
                        row.getValues()) {

                    System.out.print(
                            value + " "
                    );
                }

                System.out.println();
            }
        }

        if (!found) {

            System.out.println(
                    "No matching rows found."
            );
        }
    }

    // -----------------------------------------
    // DELETE
    // -----------------------------------------

    private void deleteRow(
            String tableName,
            String value,
            boolean silent) {

        Table table =
                tables.get(tableName);

        if (table == null) {

            if (!silent) {
                System.out.println(
                        "Table not found."
                );
            }

            return;
        }

        boolean removed =
                table.getRows()
                        .removeIf(
                                row ->
                                        row.getValues()
                                                .contains(value)
                        );

        if (removed) {

            if (!silent) {

                System.out.println(
                        "Row deleted successfully."
                );
            }

            FileManager.save(
                    tables,
                    fileName,
                    silent
            );

        } else {

            if (!silent) {

                System.out.println(
                        "Row not found."
                );
            }
        }
    }

    // -----------------------------------------
    // UPDATE
    // -----------------------------------------

    private void updateRow(
            String tableName,
            String oldValue,
            String newValue,
            boolean silent) {

        Table table =
                tables.get(tableName);

        if (table == null) {

            if (!silent) {

                System.out.println(
                        "Table not found."
                );
            }

            return;
        }

        for (Row row :
                table.getRows()) {

            for (int i = 0;
                 i < row.getValues().size();
                 i++) {

                if (row.getValues()
                        .get(i)
                        .equals(oldValue)) {

                    row.getValues()
                            .set(
                                    i,
                                    newValue
                            );

                    if (!silent) {

                        System.out.println(
                                "Row updated successfully."
                        );
                    }

                    FileManager.save(
                            tables,
                            fileName,
                            silent
                    );

                    return;
                }
            }
        }

        if (!silent) {

            System.out.println(
                    "Value not found."
            );
        }
    }

    // -----------------------------------------
    // DROP TABLE
    // -----------------------------------------

    private void dropTable(
            String tableName,
            boolean silent) {

        if (!tables.containsKey(
                tableName)) {

            if (!silent) {

                System.out.println(
                        "Table not found."
                );
            }

            return;
        }

        tables.remove(tableName);

        if (!silent) {

            System.out.println(
                    "Table '" + tableName +
                    "' dropped successfully."
            );
        }

        FileManager.save(
                tables,
                fileName,
                silent
        );
    }

    // -----------------------------------------
    // SHOW TABLES
    // -----------------------------------------

    private void showTables() {

        if (tables.isEmpty()) {

            System.out.println(
                    "No tables found."
            );

            return;
        }

        System.out.println("Tables:");

        for (String tableName :
                tables.keySet()) {

            System.out.println(
                    "- " + tableName
            );
        }
    }

    // -----------------------------------------
    // DESCRIBE
    // -----------------------------------------

    private void describeTable(
            String tableName) {

        Table table =
                tables.get(tableName);

        if (table == null) {

            System.out.println(
                    "Table not found."
            );

            return;
        }

        System.out.println(
                "Table Name : "
                        + tableName
        );

        System.out.println(
                "Total Rows : "
                        + table.getRows().size()
        );

        if (!table.getRows().isEmpty()) {

            System.out.println(
                    "Columns    : "
                            + table.getRows()
                            .get(0)
                            .getValues()
                            .size()
            );

        } else {

            System.out.println(
                    "Columns    : 0"
            );
        }
    }

    // -----------------------------------------
    // GET TABLE
    // -----------------------------------------

    public synchronized Table getTable(
            String tableName) {

        return tables.get(tableName);
    }

    // -----------------------------------------
    // GET ALL TABLES
    // Used by Node / distributed SHOW TABLES
    // -----------------------------------------

    public synchronized Map<String, Table> getTables() {

        return tables;
    }

    // -----------------------------------------
    // SAVE DATABASE
    // -----------------------------------------

    public synchronized void saveDatabase() {

        FileManager.save(
                tables,
                fileName
        );
    }

    // -----------------------------------------
    // SILENT SAVE DATABASE
    // -----------------------------------------

    public synchronized void saveDatabase(
            boolean silent) {

        FileManager.save(
                tables,
                fileName,
                silent
        );
    }
}
