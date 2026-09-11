package com.novadb.node;

public class NodeMessage {

    private String operation;
    private String data;

    public NodeMessage(String operation, String data) {
        this.operation = operation;
        this.data = data;
    }

    public String getOperation() {
        return operation;
    }

    public String getData() {
        return data;
    }

    @Override
    public String toString() {
        return operation + "|" + data;
    }
}