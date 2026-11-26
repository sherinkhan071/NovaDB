package com.novadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.novadb.engine.Command;
import com.novadb.engine.Command.Op;
import com.novadb.engine.Database;
import com.novadb.raft.Entry;
import com.novadb.raft.Storage;

public class StorageTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static Entry entry(long index, long term) {
        return new Entry(index, term, Command.noop());
    }

    private Path directory() {
        return folder.getRoot().toPath();
    }

    private void write(Entry... entries) throws IOException {
        try (Storage storage = new Storage(directory())) {
            storage.append(List.of(entries));
        }
    }

    private List<Entry> reload() throws IOException {
        try (Storage storage = new Storage(directory())) {
            return storage.entries();
        }
    }

    @Test
    public void voteEntriesAndTruncationSurviveRestart() throws IOException {
        try (Storage storage = new Storage(directory())) {
            storage.saveVote(4, 2);
            storage.append(List.of(entry(1, 1), entry(2, 1), entry(3, 2)));
            storage.truncateFrom(2);
            storage.append(List.of(entry(2, 4)));
        }
        try (Storage storage = new Storage(directory())) {
            assertEquals(4, storage.term());
            assertEquals(2, storage.votedFor());
            assertEquals(List.of(entry(1, 1), entry(2, 4)), storage.entries());
        }
    }

    @Test
    public void cutOffTailIsDiscarded() throws IOException {
        write(entry(1, 1), entry(2, 1));
        Path log = directory().resolve("log");
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "rw")) {
            file.setLength(file.length() - 3);
        }
        assertEquals(List.of(entry(1, 1)), reload());
        write(entry(2, 2));
        assertEquals(List.of(entry(1, 1), entry(2, 2)), reload());
    }

    @Test
    public void damageBeforeTheEndStopsStartup() throws IOException {
        write(entry(1, 1), entry(2, 1));
        Path log = directory().resolve("log");
        byte[] bytes = Files.readAllBytes(log);
        bytes[15] ^= 1; // inside the first entry
        Files.write(log, bytes, StandardOpenOption.TRUNCATE_EXISTING);
        assertThrows(IOException.class, this::reload);
    }

    @Test
    public void entriesCoveredBySnapshotAreDroppedAfterCrash() throws IOException {
        write(entry(1, 1), entry(2, 1), entry(3, 1));
        byte[] oldLog = Files.readAllBytes(directory().resolve("log"));
        try (Storage storage = new Storage(directory())) {
            storage.saveSnapshot(new Storage.Saved(2, 1, new Database().snapshot()),
                    List.of(entry(3, 1)));
        }
        // As if the crash came after the snapshot but before the log rewrite.
        Files.write(directory().resolve("log"), oldLog);
        assertEquals(List.of(entry(3, 1)), reload());
    }

    @Test
    public void valuesWithSeparatorCharactersRoundTrip() {
        Database database = new Database();
        database.apply(new Command(Op.CREATE, List.of("t"), "c", 1));
        database.apply(new Command(Op.INSERT, List.of("t", "1,Zed;Q|R"), "c", 2));
        Database copy = new Database();
        copy.restore(database.snapshot());
        assertEquals(List.of("1,Zed;Q|R"),
                copy.apply(new Command(Op.SELECT, List.of("t"), "c", 3)).rows());
    }
}
