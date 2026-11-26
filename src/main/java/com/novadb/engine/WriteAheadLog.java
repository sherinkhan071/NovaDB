package com.novadb.engine;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only log with one record per line. It is the only copy of the data on
 * disk: the tables in memory are rebuilt by replaying it in order.
 */
public class WriteAheadLog implements Closeable {

    private final FileChannel channel;
    private final List<String> recovered = new ArrayList<>();

    public WriteAheadLog(Path file) throws IOException {

        channel = FileChannel.open(file,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);

        byte[] bytes = Files.readAllBytes(file);

        // A record counts only once its newline is on disk. Anything after the
        // last newline is a write that was cut off, so it is discarded.
        int end = 0;

        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                recovered.add(new String(bytes, end, i - end, StandardCharsets.UTF_8));
                end = i + 1;
            }
        }

        channel.truncate(end);
        channel.position(end);
    }

    /** Records that were on disk when the log was opened. */
    public List<String> recoveredRecords() {
        return recovered;
    }

    /** Returns only after the record has been forced to disk. */
    public synchronized void append(String record) throws IOException {

        long start = channel.position();

        try {
            ByteBuffer buffer = ByteBuffer.wrap(
                    (record + "\n").getBytes(StandardCharsets.UTF_8));

            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }

            channel.force(false);

        } catch (IOException e) {
            channel.truncate(start);
            throw e;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        channel.close();
    }
}
