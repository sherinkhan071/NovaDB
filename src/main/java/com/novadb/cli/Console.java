package com.novadb.cli;

import java.util.Scanner;

import com.novadb.node.NodeManager;

public class Console {

    private NodeManager nodeManager;

    public Console(NodeManager nodeManager) {
        this.nodeManager = nodeManager;
    }

    public void start() {

        Scanner scanner = new Scanner(System.in);

        while (true) {

            System.out.print("NovaDB > ");

            String command = scanner.nextLine().trim();

            // EXIT
            if (command.equalsIgnoreCase("exit")) {

                System.out.println("Goodbye!");
                break;
            }

            // INSERT
            if (command.toLowerCase().startsWith("insert into")) {

                handleInsert(command);
                continue;
            }

            // SELECT
            if (command.toLowerCase().startsWith("select * from")) {

                handleSelect(command);
                continue;
            }

            // DELETE
            if (command.toLowerCase().startsWith("delete from")) {

                handleDelete(command);
                continue;
            }

            System.out.println(
                    "Unknown command or unsupported distributed operation."
            );
        }

        scanner.close();
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

                System.out.println(
                        "Invalid INSERT syntax."
                );

                System.out.println(
                        "Example: INSERT INTO students 7,Grace,22,Delhi"
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

            System.out.println(
                    "INSERT error: "
                            + e.getMessage()
            );
        }
    }

    // -----------------------------------------
    // SELECT
    // -----------------------------------------

    private void handleSelect(String command) {

        try {

            String prefix =
                    "select * from";

            String tableName =
                    command.substring(
                            prefix.length()
                    ).trim();

            if (tableName.isEmpty()) {

                System.out.println(
                        "Please specify a table name."
                );

                return;
            }

            nodeManager.distributedSelect(
                    tableName
            );

        } catch (Exception e) {

            System.out.println(
                    "SELECT error: "
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

            /*
             * Expected:
             *
             * DELETE FROM students WHERE id = 7
             */

            String[] parts =
                    remaining.split(
                            "(?i)\\s+where\\s+",
                            2
                    );

            if (parts.length < 2) {

                System.out.println(
                        "Invalid DELETE syntax."
                );

                System.out.println(
                        "Example: DELETE FROM students WHERE id = 7"
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

                System.out.println(
                        "Invalid DELETE condition."
                );

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

                System.out.println(
                        "For distributed DELETE, use the ID column."
                );

                return;
            }

            nodeManager.deleteWithSharding(
                    tableName,
                    id
            );

        } catch (Exception e) {

            System.out.println(
                    "DELETE error: "
                            + e.getMessage()
            );
        }
    }
}