
package com.novadb.cli;

import java.util.Scanner;

import com.novadb.node.NodeManager;

public class Console {

    private final NodeManager nodeManager;

    public Console(NodeManager nodeManager) {
        this.nodeManager = nodeManager;
    }

    public void start() {

        Scanner scanner = new Scanner(System.in);

        printWelcome();

        while (true) {

            System.out.print("novadb> ");

            String command = scanner.nextLine().trim();

            if (command.isEmpty()) {
                continue;
            }

            // -----------------------------------------
            // EXIT
            // -----------------------------------------

            if (command.equalsIgnoreCase("exit")) {

                System.out.println("Bye.");
                break;
            }

            // -----------------------------------------
            // HELP
            // -----------------------------------------

            if (command.equalsIgnoreCase("help")) {

                printHelp();
                continue;
            }

            // -----------------------------------------
            // CREATE TABLE
            // -----------------------------------------

            if (command.toLowerCase().startsWith("create table")) {

                String tableName =
                        command.substring(12).trim();

                if (tableName.isEmpty()) {

                    error("Table name is required.");
                    continue;
                }

                nodeManager.createTable(tableName);
                continue;
            }

            // -----------------------------------------
            // DROP TABLE
            // -----------------------------------------

            if (command.toLowerCase().startsWith("drop table")) {

                String tableName =
                        command.substring(10).trim();

                if (tableName.isEmpty()) {

                    error("Table name is required.");
                    continue;
                }

                nodeManager.dropTable(tableName);
                continue;
            }

            // -----------------------------------------
            // INSERT
            // -----------------------------------------

            if (command.toLowerCase().startsWith("insert into")) {

                handleInsert(command);
                continue;
            }

            // -----------------------------------------
            // SELECT
            // -----------------------------------------

            if (command.toLowerCase().startsWith("select * from")) {

                handleSelect(command);
                continue;
            }

            // -----------------------------------------
            // DELETE
            // -----------------------------------------

            if (command.toLowerCase().startsWith("delete from")) {

                handleDelete(command);
                continue;
            }

            // -----------------------------------------
            // UPDATE
            // -----------------------------------------

            if (command.toLowerCase().startsWith("update")) {

                handleUpdate(command);
                continue;
            }

            // -----------------------------------------
            // SHOW TABLES
            // -----------------------------------------

            if (command.equalsIgnoreCase("show tables")) {

                nodeManager.showTables();
                continue;
            }

            // -----------------------------------------
            // HEALTH
            // -----------------------------------------

            if (command.equalsIgnoreCase("health")) {

                nodeManager.checkNodeHealth();
                continue;
            }

            // -----------------------------------------
            // STATS
            // -----------------------------------------

            if (command.equalsIgnoreCase("stats")) {

                nodeManager.showStats();
                continue;
            }

            // -----------------------------------------
            // BENCHMARK
            // -----------------------------------------

            if (command.equalsIgnoreCase("benchmark")) {

                nodeManager.benchmark();
                continue;
            }

            // -----------------------------------------
            // FAILOVER
            // -----------------------------------------

            if (command.equalsIgnoreCase("failover")) {

                nodeManager.testFailover();
                continue;
            }

            // -----------------------------------------
            // UNKNOWN COMMAND
            // -----------------------------------------

            error(
                    "Unknown command. Type 'help' for available commands."
            );
        }

        scanner.close();
    }

    // -----------------------------------------
    // WELCOME
    // -----------------------------------------

    private void printWelcome() {

        System.out.println();
        System.out.println("NovaDB");
        System.out.println("Distributed Database System");
        System.out.println("Type 'help' for commands.");
        System.out.println();
    }

    // -----------------------------------------
    // HELP
    // -----------------------------------------

    private void printHelp() {

        System.out.println();
        System.out.println("Commands");
        System.out.println("----------------------------------------");

        System.out.println(
                "CREATE TABLE <name>"
        );

        System.out.println(
                "DROP TABLE <name>"
        );

        System.out.println(
                "INSERT INTO <table> <values>"
        );

        System.out.println(
                "SELECT * FROM <table>"
        );

        System.out.println(
                "UPDATE <table> SET <column> = <value> WHERE id = <id>"
        );

        System.out.println(
                "DELETE FROM <table> WHERE id = <id>"
        );

        System.out.println(
                "SHOW TABLES"
        );

        System.out.println(
                "HEALTH"
        );

        System.out.println(
                "STATS"
        );

        System.out.println(
                "BENCHMARK"
        );

        System.out.println(
                "FAILOVER"
        );

        System.out.println(
                "HELP"
        );

        System.out.println(
                "EXIT"
        );

        System.out.println("----------------------------------------");
        System.out.println();
    }

    // -----------------------------------------
    // INSERT
    // -----------------------------------------

    private void handleInsert(String command) {

        try {

            String lowerCommand =
                    command.toLowerCase();

            int intoIndex =
                    lowerCommand.indexOf("into");

            String remaining =
                    command.substring(
                            intoIndex + 4
                    ).trim();

            String[] parts =
                    remaining.split("\\s+", 2);

            if (parts.length < 2) {

                error("Invalid INSERT syntax.");

                System.out.println(
                        "Usage: INSERT INTO students 7,Grace,22,Delhi"
                );

                return;
            }

            String tableName =
                    parts[0];

            String values =
                    parts[1];

            nodeManager.insertWithSharding(
                    tableName,
                    values
            );

        } catch (Exception e) {

            error(
                    "INSERT failed: "
                            + e.getMessage()
            );
        }
    }

    // -----------------------------------------
    // SELECT
    // -----------------------------------------

    private void handleSelect(String command) {

        try {

            String lowerCommand =
                    command.toLowerCase();

            String prefix =
                    "select * from";

            int prefixIndex =
                    lowerCommand.indexOf(prefix);

            String tableName =
                    command.substring(
                            prefixIndex + prefix.length()
                    ).trim();

            if (tableName.isEmpty()) {

                error("Table name is required.");
                return;
            }

            nodeManager.distributedSelect(
                    tableName
            );

        } catch (Exception e) {

            error(
                    "SELECT failed: "
                            + e.getMessage()
            );
        }
    }

    // -----------------------------------------
    // DELETE
    // -----------------------------------------

    private void handleDelete(String command) {

        try {

            String lowerCommand =
                    command.toLowerCase();

            int fromIndex =
                    lowerCommand.indexOf("from");

            String remaining =
                    command.substring(
                            fromIndex + 4
                    ).trim();

            String[] parts =
                    remaining.split(
                            "(?i)\\s+where\\s+",
                            2
                    );

            if (parts.length < 2) {

                error("Invalid DELETE syntax.");

                System.out.println(
                        "Usage: DELETE FROM students WHERE id = 7"
                );

                return;
            }

            String tableName =
                    parts[0].trim();

            String condition =
                    parts[1].trim();

            String[] conditionParts =
                    condition.split(
                            "\\s*=\\s*",
                            2
                    );

            if (conditionParts.length < 2) {

                error("Invalid DELETE condition.");

                System.out.println(
                        "Use: WHERE id = 7"
                );

                return;
            }

            String column =
                    conditionParts[0].trim();

            String id =
                    conditionParts[1].trim();

            if (!column.equalsIgnoreCase("id")) {

                error(
                        "Distributed DELETE requires the ID column."
                );

                return;
            }

            nodeManager.deleteWithSharding(
                    tableName,
                    id
            );

        } catch (Exception e) {

            error(
                    "DELETE failed: "
                            + e.getMessage()
            );
        }
    }

    // -----------------------------------------
    // UPDATE
    // -----------------------------------------

    private void handleUpdate(String command) {

        try {

            String lowerCommand =
                    command.toLowerCase();

            int updateIndex =
                    lowerCommand.indexOf("update");

            int setIndex =
                    lowerCommand.indexOf("set");

            int whereIndex =
                    lowerCommand.indexOf("where");

            if (setIndex == -1
                    || whereIndex == -1) {

                error("Invalid UPDATE syntax.");

                System.out.println(
                        "Usage: UPDATE students SET name = Daniel WHERE id = 4"
                );

                return;
            }

            String tableName =
                    command.substring(
                            updateIndex + 6,
                            setIndex
                    ).trim();

            String setPart =
                    command.substring(
                            setIndex + 3,
                            whereIndex
                    ).trim();

            String wherePart =
                    command.substring(
                            whereIndex + 5
                    ).trim();

            String[] setParts =
                    setPart.split(
                            "\\s*=\\s*",
                            2
                    );

            String[] whereParts =
                    wherePart.split(
                            "\\s*=\\s*",
                            2
                    );

            if (setParts.length < 2
                    || whereParts.length < 2) {

                error("Invalid UPDATE syntax.");

                System.out.println(
                        "Usage: UPDATE students SET name = Daniel WHERE id = 4"
                );

                return;
            }

            String column =
                    setParts[0].trim();

            String newValue =
                    setParts[1].trim();

            String whereColumn =
                    whereParts[0].trim();

            String id =
                    whereParts[1].trim();

            if (!whereColumn.equalsIgnoreCase("id")) {

                error(
                        "Distributed UPDATE requires the ID column."
                );

                return;
            }

            int columnIndex;

            if (column.equalsIgnoreCase("id")) {

                columnIndex = 0;

            } else if (column.equalsIgnoreCase("name")) {

                columnIndex = 1;

            } else if (column.equalsIgnoreCase("age")) {

                columnIndex = 2;

            } else if (column.equalsIgnoreCase("city")) {

                columnIndex = 3;

            } else {

                error(
                        "Unknown column: " + column
                );

                return;
            }

            nodeManager.updateWithSharding(
                    tableName,
                    id,
                    columnIndex,
                    newValue
            );

        } catch (Exception e) {

            error(
                    "UPDATE failed: "
                            + e.getMessage()
            );
        }
    }

    // -----------------------------------------
    // ERROR
    // -----------------------------------------

    private void error(String message) {

        System.out.println(
                "Error: " + message
        );
    }
}
