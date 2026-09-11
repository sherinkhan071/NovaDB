package com.novadb.engine;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

import com.novadb.model.Row;
import com.novadb.model.Table;

public class FileManager {

    public static void save(
            Map<String, Table> tables,
            String fileName) {

        try (
                ObjectOutputStream output =
                        new ObjectOutputStream(
                                new FileOutputStream(fileName)
                        )
        ) {

            output.writeObject(tables);

            System.out.println(
                    "Database saved successfully."
            );

        } catch (Exception e) {

            System.out.println(
                    "Error saving database: "
                            + e.getMessage()
            );
        }
    }

    @SuppressWarnings("unchecked")
    public static void load(
            Map<String, Table> tables,
            String fileName) {

        File file = new File(fileName);

        if (!file.exists()) {

            System.out.println(
                    "No existing database found for "
                            + fileName
            );

            return;
        }

        try (
                ObjectInputStream input =
                        new ObjectInputStream(
                                new FileInputStream(file)
                        )
        ) {

            Map<String, Table> loadedTables =
                    (Map<String, Table>) input.readObject();

            tables.putAll(loadedTables);

            System.out.println(
                    "Database loaded successfully from "
                            + fileName
            );

        } catch (Exception e) {

            System.out.println(
                    "Error loading database: "
                            + e.getMessage()
            );
        }
    }
}