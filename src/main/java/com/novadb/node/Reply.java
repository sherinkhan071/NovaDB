package com.novadb.node;

/** Result of one request: the data on success, or the reason for failure. */
public record Reply(boolean ok, String body) {
}
