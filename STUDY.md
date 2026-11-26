# Learn this before putting it on your resume

Read in this order, and answer each question aloud.

1. `engine/Database.java`: Why must every node apply commands deterministically?
2. `raft/Storage.java`: What survives a crash? How is a half-written entry detected?
3. `raft/RaftNode.java`, elections: Why random timeouts? Why save the vote before replying?
4. `RaftNode`, `advanceCommit`: Why only commit entries of the current term this way?
5. `RaftNode`, `onAppend`: How does a follower with a different log get repaired?
6. `RaftNode`, snapshots: When is a snapshot sent instead of entries?
7. `net/Client.java`: A write times out. Did it happen? Why is retrying safe?
8. `RaftTest`: Pick one test. What would break it?

Exercise: in `RaftNode.onVote`, remove `&& upToDate`, run the tests, and explain the failure.
Then restore it.
