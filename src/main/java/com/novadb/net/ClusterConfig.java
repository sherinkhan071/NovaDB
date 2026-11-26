package com.novadb.net;

import java.io.IOException;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** Node addresses, read from lines such as: node.1=localhost:5001 */
public final class ClusterConfig {

    private ClusterConfig() {
    }

    public static Map<Integer, InetSocketAddress> load(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        Map<Integer, InetSocketAddress> nodes = new TreeMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("node.")) {
                String[] address = properties.getProperty(key).trim().split(":");
                nodes.put(Integer.parseInt(key.substring(5)),
                        InetSocketAddress.createUnresolved(
                                address[0], Integer.parseInt(address[1])));
            }
        }
        if (nodes.isEmpty()) {
            throw new IOException("No nodes listed in " + file);
        }
        return nodes;
    }
}
