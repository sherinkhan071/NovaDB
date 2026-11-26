package com.novadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.novadb.cli.Console;
import com.novadb.engine.Database;
import com.novadb.engine.DatabaseException;
import com.novadb.node.Node;
import com.novadb.node.NodeManager;
import com.novadb.node.Reply;

public class PhaseOneTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final NodeManager cluster = new NodeManager();

    @Before
    public void startCluster() throws IOException {
        for (int i = 1; i <= 3; i++) {
            Node node = new Node("Node-" + i, 0, folder.getRoot().toPath());
            node.start();
            cluster.addNode(node);
        }
        assertTrue(cluster.createTable("students").ok());
    }

    @After
    public void stopCluster() {
        cluster.closeAll();
    }

    private Path log() {
        return folder.getRoot().toPath().resolve("single.wal");
    }

    @Test
    public void deleteThenRestartKeepsOtherRows() throws IOException {
        try (Database db = new Database(log())) {
            db.createTable("students");
            db.insert("students", "1,Alice,2,Delhi");
            db.insert("students", "2,Bob,23,Noida");
            db.delete("students", "2");
        }
        try (Database db = new Database(log())) {
            assertEquals(List.of("1,Alice,2,Delhi"), db.select("students"));
        }
    }

    @Test
    public void updateAndDropSurviveRestart() throws IOException {
        try (Database db = new Database(log())) {
            db.createTable("a");
            db.createTable("b");
            db.insert("a", "1,Alice,20,Delhi");
            db.update("a", "1", 3, "Noida");
            db.dropTable("b");
        }
        try (Database db = new Database(log())) {
            assertEquals(List.of("a"), db.tableNames());
            assertEquals(List.of("1,Alice,20,Noida"), db.select("a"));
        }
    }

    @Test
    public void cutOffLastRecordIsDiscardedAndLogStaysUsable() throws IOException {
        try (Database db = new Database(log())) {
            db.createTable("a");
            db.insert("a", "1,Alice");
        }
        Files.writeString(log(), "INSERT|a|2,Bo", StandardOpenOption.APPEND);

        try (Database db = new Database(log())) {
            assertEquals(List.of("1,Alice"), db.select("a"));
            db.insert("a", "3,Cara");
        }
        try (Database db = new Database(log())) {
            assertEquals(List.of("1,Alice", "3,Cara"), db.select("a"));
        }
    }

    @Test
    public void corruptRecordStopsStartup() throws IOException {
        Files.writeString(log(), "GARBAGE\n");
        assertThrows(DatabaseException.class, () -> new Database(log()));
    }

    @Test
    public void dropTableRemovesItFromEveryNode() {
        assertTrue(cluster.dropTable("students").ok());
        assertEquals("", cluster.showTables().body());
        assertFalse(cluster.select("students").ok());
    }

    @Test
    public void writeFailsWhenPrimaryIsDown() {
        cluster.getNodes().get(1).stop();               // primary for id 1
        Reply reply = cluster.insert("students", "1,Alice,20,Delhi");
        assertFalse(reply.ok());
        assertEquals("", cluster.select("students").body());
    }

    @Test
    public void writeReportsFailedReplica() {
        cluster.getNodes().get(2).stop();
        Reply reply = cluster.insert("students", "1,Alice,20,Delhi");
        assertFalse(reply.ok());
        assertTrue(reply.body().contains("Node-3"));
    }

    @Test
    public void duplicateIdIsRejected() {
        assertTrue(cluster.insert("students", "5,A,20,X").ok());
        assertFalse(cluster.insert("students", "05,B,30,Y").ok());
        assertEquals("5,A,20,X", cluster.select("students").body());
    }

    @Test
    public void missingTableAndMissingRowAreErrors() {
        assertFalse(cluster.insert("ghost", "1,A").ok());
        assertFalse(cluster.delete("students", "99").ok());
    }

    @Test
    public void tableNameContainingSetCanBeUpdated() {
        cluster.createTable("assets");
        cluster.insert("assets", "1,Old,2,Delhi");
        new Console(cluster).execute("UPDATE assets SET name = New WHERE id = 1");
        assertEquals("1,New,2,Delhi", cluster.select("assets").body());
    }

    @Test
    public void separatorCharactersAreRejected() {
        assertFalse(cluster.insert("students", "9,Zed;Q,20,X").ok());
        assertFalse(cluster.insert("students", "9,Zed|Q,20,X").ok());
        assertEquals("", cluster.select("students").body());
    }
}
