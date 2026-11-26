package com.novadb.raft;

import java.io.IOException;

/** Delivers a message to another node. Tests replace it to cut the network. */
public interface Transport {

    Rpc.Reply send(int peer, Rpc.Request request) throws IOException;
}
