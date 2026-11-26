package com.novadb.cli;

import java.io.IOException;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.novadb.engine.Command.Op;
import com.novadb.engine.Result;
import com.novadb.net.Client;

public class Console {

    private static final List<String> COLUMNS = List.of("id", "name", "age", "city");

    private static final Pattern CREATE = command("create\\s+table\\s+(\\S+)");
    private static final Pattern DROP = command("drop\\s+table\\s+(\\S+)");
    private static final Pattern INSERT = command("insert\\s+into\\s+(\\S+)\\s+(.+)");
    private static final Pattern SELECT = command("select\\s+\\*\\s+from\\s+(\\S+)");
    private static final Pattern DELETE = command(
            "delete\\s+from\\s+(\\S+)\\s+where\\s+id\\s*=\\s*(\\S+)");
    private static final Pattern UPDATE = command(
            "update\\s+(\\S+)\\s+set\\s+(\\w+)\\s*=\\s*(.+?)\\s+where\\s+id\\s*=\\s*(\\S+)");

    private final Client client;

    public Console(Client client) {
        this.client = client;
    }

    public void start() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("NovaDB. Type 'help' for commands.");

        while (true) {
            System.out.print("novadb> ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String line = scanner.nextLine().trim();
            if (line.equalsIgnoreCase("exit")) {
                System.out.println("Bye.");
                break;
            }
            if (!line.isEmpty()) {
                try {
                    execute(line);
                } catch (IOException e) {
                    System.out.println("Error: " + e.getMessage());
                }
            }
        }
    }

    private void execute(String line) throws IOException {
        Matcher m;
        if ((m = CREATE.matcher(line)).matches()) {
            print(client.execute(Op.CREATE, m.group(1)));
        } else if ((m = DROP.matcher(line)).matches()) {
            print(client.execute(Op.DROP, m.group(1)));
        } else if ((m = INSERT.matcher(line)).matches()) {
            print(client.execute(Op.INSERT, m.group(1), m.group(2)));
        } else if ((m = SELECT.matcher(line)).matches()) {
            list(client.execute(Op.SELECT, m.group(1)), "rows");
        } else if ((m = DELETE.matcher(line)).matches()) {
            print(client.execute(Op.DELETE, m.group(1), m.group(2)));
        } else if ((m = UPDATE.matcher(line)).matches()) {
            int column = COLUMNS.indexOf(m.group(2).toLowerCase());
            if (column < 0) {
                System.out.println("Error: Unknown column: " + m.group(2));
            } else {
                print(client.execute(Op.UPDATE, m.group(1), m.group(4),
                        Integer.toString(column), m.group(3)));
            }
        } else if (line.equalsIgnoreCase("show tables")) {
            list(client.execute(Op.TABLES), "tables");
        } else if (line.equalsIgnoreCase("status")) {
            for (int id : client.nodeIds()) {
                String status = client.status(id);
                System.out.println(status == null ? "node=" + id + " OFFLINE" : status);
            }
        } else if (line.equalsIgnoreCase("help")) {
            System.out.println("""
                    CREATE TABLE <name>
                    DROP TABLE <name>
                    INSERT INTO <table> <id>,<name>,<age>,<city>
                    SELECT * FROM <table>
                    UPDATE <table> SET <column> = <value> WHERE id = <id>
                    DELETE FROM <table> WHERE id = <id>
                    SHOW TABLES
                    STATUS
                    HELP
                    EXIT""");
        } else {
            System.out.println("Error: Unknown or incomplete command. Type 'help'.");
        }
    }

    private static void print(Result result) {
        System.out.println(result.ok() ? "OK" : "Error: " + result.error());
    }

    private static void list(Result result, String noun) {
        if (!result.ok()) {
            print(result);
            return;
        }
        result.rows().forEach(System.out::println);
        System.out.println("(" + result.rows().size() + " " + noun + ")");
    }

    private static Pattern command(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }
}
