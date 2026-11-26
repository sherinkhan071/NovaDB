package com.novadb.engine;

/** A request that could not be carried out. The message is shown to the user. */
public class DatabaseException extends RuntimeException {

    public DatabaseException(String message) {
        super(message);
    }
}
