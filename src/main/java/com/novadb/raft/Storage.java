package com.novadb.raft;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Everything a node must remember across a crash:
 *   meta      current term and vote
 *   log       entries, each as [length][length crc][payload crc][payload]
 *   snapshot  database state up to some index, replacing older entries
 * Every method returns only after the data has been forced to disk.
 */
public class Storage implements Closeable {

    private static final int HEADER = 12;

    public record Saved(long index, long term, byte[] data) {
    }

    private final Path directory;
    private final List<Long> offsets = new ArrayList<>(); // file position of each entry
    private final List<Entry> entries = new ArrayList<>();
    private FileChannel log;

    private long term;
    private int votedFor = -1;
    private Saved snapshot = new Saved(0, 0, null);

    public Storage(Path directory) throws IOException {

        this.directory = directory;
        Files.createDirectories(directory);

        byte[] meta = readChecked(directory.resolve("meta"));
        if (meta != null) {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(meta));
            term = in.readLong();
            votedFor = in.readInt();
        }

        byte[] saved = readChecked(directory.resolve("snapshot"));
        if (saved != null) {
            ByteBuffer buffer = ByteBuffer.wrap(saved);
            long index = buffer.getLong();
            long snapshotTerm = buffer.getLong();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            snapshot = new Saved(index, snapshotTerm, data);
        }

        openLog();
    }

    public long term() {
        return term;
    }

    public int votedFor() {
        return votedFor;
    }

    public Saved snapshot() {
        return snapshot;
    }

    /** Entries after the snapshot, in order. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public void saveVote(long newTerm, int candidate) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeLong(newTerm);
        out.writeInt(candidate);
        writeChecked("meta", bytes.toByteArray());
        term = newTerm;
        votedFor = candidate;
    }

    public void append(List<Entry> added) throws IOException {
        for (Entry entry : added) {
            offsets.add(log.position());
            writeFully(log, encode(entry));
            entries.add(entry);
        }
        log.force(false);
    }

    /** Removes the entry at this index and everything after it. */
    public void truncateFrom(long index) throws IOException {
        int position = (int) (index - snapshot.index() - 1);
        if (position < entries.size()) {
            log.truncate(offsets.get(position));
            log.position(offsets.get(position));
            log.force(false);
            offsets.subList(position, offsets.size()).clear();
            entries.subList(position, entries.size()).clear();
        }
    }

    /** Saves a snapshot, then rewrites the log with only the entries in keep. */
    public void saveSnapshot(Saved saved, List<Entry> keep) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(16 + saved.data().length);
        buffer.putLong(saved.index()).putLong(saved.term()).put(saved.data());
        writeChecked("snapshot", buffer.array());
        snapshot = saved;
        // A crash here is safe: openLog drops entries the snapshot covers.
        rewriteLog(keep);
    }

    @Override
    public void close() throws IOException {
        log.close();
    }

    private void openLog() throws IOException {

        Path file = directory.resolve("log");
        log = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(file));
        List<Entry> found = new ArrayList<>();
        List<Long> positions = new ArrayList<>();
        int end = 0;

        while (buffer.remaining() >= HEADER) {
            int length = buffer.getInt();
            int lengthCrc = buffer.getInt();
            int payloadCrc = buffer.getInt();

            if (crc(ByteBuffer.allocate(4).putInt(length).array()) != lengthCrc) {
                throw new IOException("Corrupt log header at byte " + end + " in " + file);
            }
            if (length > buffer.remaining()) {
                break; // the last write was cut off
            }

            byte[] payload = new byte[length];
            buffer.get(payload);

            if (crc(payload) != payloadCrc) {
                if (!buffer.hasRemaining()) {
                    break; // damaged final record: never acknowledged
                }
                throw new IOException("Corrupt log entry at byte " + end + " in " + file);
            }

            positions.add((long) end);
            found.add(Entry.read(new DataInputStream(new ByteArrayInputStream(payload))));
            end = buffer.position();
        }

        log.truncate(end);
        log.position(end);

        List<Entry> keep = new ArrayList<>();
        boolean conflict = false;

        for (Entry entry : found) {
            if (entry.index() == snapshot.index() && entry.term() != snapshot.term()) {
                conflict = true; // later entries belong to a replaced history
            }
            if (entry.index() > snapshot.index() && !conflict) {
                if (entry.index() != snapshot.index() + keep.size() + 1) {
                    throw new IOException("Gap in log before index " + entry.index());
                }
                keep.add(entry);
            }
        }

        if (keep.size() != found.size()) {
            rewriteLog(keep);
        } else {
            entries.addAll(found);
            offsets.addAll(positions);
        }
    }

    private void rewriteLog(List<Entry> keep) throws IOException {

        List<Entry> copy = List.copyOf(keep);
        Path temporary = directory.resolve("log.tmp");

        try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            for (Entry entry : copy) {
                writeFully(out, encode(entry));
            }
            out.force(true);
        }

        log.close();
        replace(temporary, directory.resolve("log"));
        log = FileChannel.open(directory.resolve("log"),
                StandardOpenOption.READ, StandardOpenOption.WRITE);

        entries.clear();
        offsets.clear();
        long position = 0;

        for (Entry entry : copy) {
            offsets.add(position);
            entries.add(entry);
            position += encode(entry).remaining();
        }
        log.position(position);
    }

    private static ByteBuffer encode(Entry entry) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        entry.write(new DataOutputStream(bytes));
        byte[] payload = bytes.toByteArray();
        byte[] length = ByteBuffer.allocate(4).putInt(payload.length).array();
        return ByteBuffer.allocate(HEADER + payload.length)
                .put(length).putInt(crc(length)).putInt(crc(payload)).put(payload).flip();
    }

    /** Write to a temporary file, then rename, so readers never see half a file. */
    private void writeChecked(String name, byte[] data) throws IOException {
        Path temporary = directory.resolve(name + ".tmp");

        try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            writeFully(out, ByteBuffer.allocate(4 + data.length)
                    .putInt(crc(data)).put(data).flip());
            out.force(true);
        }
        replace(temporary, directory.resolve(name));
    }

    private static byte[] readChecked(Path file) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(file));
        if (buffer.remaining() < 4) {
            throw new IOException("Corrupt file " + file);
        }
        int expected = buffer.getInt();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        if (crc(data) != expected) {
            throw new IOException("Corrupt file " + file);
        }
        return data;
    }

    private void replace(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        // Makes the rename durable. Windows cannot open a directory this way.
        try (FileChannel folder = FileChannel.open(directory, StandardOpenOption.READ)) {
            folder.force(true);
        } catch (IOException e) {
            if (!System.getProperty("os.name").startsWith("Windows")) {
                throw e;
            }
        }
    }

    private static void writeFully(FileChannel out, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            out.write(buffer);
        }
    }

    private static int crc(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return (int) crc.getValue();
    }
}
