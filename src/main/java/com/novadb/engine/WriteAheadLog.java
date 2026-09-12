package com.novadb.engine;

import java.io.*;

import com.novadb.model.Table;
import com.novadb.model.Row;

public class WriteAheadLog {

    private String fileName;

    public WriteAheadLog(String nodeId) {

        fileName =
                "novadb-"
                        + nodeId.toLowerCase()
                        + ".wal";
    }

    // -----------------------------------------
    // WRITE TO WAL
    // -----------------------------------------

    public synchronized void log(
            String operation,
            String data) {

        try (
                FileWriter fileWriter =
                        new FileWriter(
                                fileName,
                                true
                        );

                PrintWriter writer =
                        new PrintWriter(fileWriter)
        ) {

            writer.println(
                    operation + "|" + data
            );

            writer.flush();

        } catch (IOException e) {

            System.out.println(
                    "WAL error: "
                            + e.getMessage()
            );
        }
    }

    // -----------------------------------------
    // WAL RECOVERY
    // -----------------------------------------

    public void recover(Database database) {

        File file =
                new File(fileName);

        if (!file.exists()) {
            return;
        }

        boolean recovered = false;

        try (
                BufferedReader reader =
                        new BufferedReader(
                                new FileReader(file)
                        )
        ) {

            String line;

            while ((line = reader.readLine()) != null) {

                if (line.trim().isEmpty()) {
                    continue;
                }

                String[] parts =
                        line.split("\\|", 2);

                if (parts.length < 2) {
                    continue;
                }

                String operation =
                        parts[0];

                String data =
                        parts[1];

                // ---------------------------------
                // INSERT RECOVERY
                // ---------------------------------

                if (operation.equalsIgnoreCase(
                        "INSERT")) {

                    String[] insertParts =
                            data.split("\\|", 2);

                    if (insertParts.length != 2) {
                        continue;
                    }

                    String tableName =
                            insertParts[0];

                    String rowData =
                            insertParts[1];

                    String[] values =
                            rowData.split(",");

                    if (values.length == 0) {
                        continue;
                    }

                    String id =
                            values[0].trim();

                    Table table =
                            database.getTable(
                                    tableName
                            );

                    // Recreate table if necessary
                    if (table == null) {

                        database.execute(
                                "create table "
                                        + tableName,
                                true
                        );

                        table =
                                database.getTable(
                                        tableName
                                );
                    }

                    // Check for duplicate row
                    boolean alreadyExists =
                            false;

                    if (table != null) {

                        for (Row row :
                                table.getRows()) {

                            if (!row.getValues().isEmpty()
                                    && row.getValues()
                                    .get(0)
                                    .trim()
                                    .equals(id)) {

                                alreadyExists = true;
                                break;
                            }
                        }
                    }

                    // Insert only if missing
                    if (!alreadyExists) {

                        database.execute(
                                "insert into "
                                        + tableName
                                        + " "
                                        + rowData,
                                true
                        );
                    }

                    recovered = true;
                }

                // ---------------------------------
                // DELETE RECOVERY
                // ---------------------------------

                else if (
                        operation.equalsIgnoreCase(
                                "DELETE"
                        )
                ) {

                    String[] deleteParts =
                            data.split("\\|", 2);

                    if (deleteParts.length != 2) {
                        continue;
                    }

                    String tableName =
                            deleteParts[0];

                    String id =
                            deleteParts[1].trim();

                    Table table =
                            database.getTable(
                                    tableName
                            );

                    if (table == null) {
                        continue;
                    }

                    database.execute(
                            "delete from "
                                    + tableName
                                    + " "
                                    + id,
                            true
                    );

                    recovered = true;
                }

                // ---------------------------------
                // UPDATE RECOVERY
                // ---------------------------------

                else if (
                        operation.equalsIgnoreCase(
                                "UPDATE"
                        )
                ) {

                    String[] updateParts =
                            data.split("\\|", 4);

                    if (updateParts.length != 4) {
                        continue;
                    }

                    String tableName =
                            updateParts[0];

                    String id =
                            updateParts[1].trim();

                    int columnIndex;

                    try {

                        columnIndex =
                                Integer.parseInt(
                                        updateParts[2].trim()
                                );

                    } catch (NumberFormatException e) {

                        continue;
                    }

                    String newValue =
                            updateParts[3];

                    Table table =
                            database.getTable(
                                    tableName
                            );

                    if (table == null) {
                        continue;
                    }

                    for (Row row :
                            table.getRows()) {

                        if (row.getValues().isEmpty()) {
                            continue;
                        }

                        if (row.getValues()
                                .get(0)
                                .trim()
                                .equals(id)) {

                            if (columnIndex >= 0
                                    && columnIndex
                                    < row.getValues().size()) {

                                row.getValues()
                                        .set(
                                                columnIndex,
                                                newValue
                                        );
                            }
                        }
                    }

                    recovered = true;
                }
            }

            // ---------------------------------
            // SAVE RECOVERED DATABASE
            // ---------------------------------

            if (recovered) {

                database.saveDatabase(true);
            }

            System.out.println(
                    "WAL recovery: "
                            + fileName
                            + " ✓"
            );

        } catch (Exception e) {

            System.out.println(
                    "WAL recovery error: "
                            + e.getMessage()
            );
        }
    }
}