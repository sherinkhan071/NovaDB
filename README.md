# NovaDB

### Lightweight Distributed Database System Built from Scratch in Java

NovaDB is a lightweight distributed database system implemented from scratch in Java to explore the core concepts behind distributed data storage.

The project runs a **3-node database cluster**, communicates between nodes using **TCP sockets**, distributes records using **ID-based sharding**, replicates data across nodes, persists data to disk, and uses a **Write-Ahead Log (WAL)** for recovery.

NovaDB also provides an interactive command-line interface for performing database operations and inspecting cluster health, statistics, benchmarking, and failover behavior.

---

## ✨ Features

* 🗄️ **Distributed 3-node database cluster**
* 🔀 **ID-based sharding** across database nodes
* 🔁 **Data replication** across nodes
* 🌐 **TCP socket-based node communication**
* 💾 **Persistent file-based storage**
* 📝 **Write-Ahead Logging (WAL)**
* ♻️ **WAL-based recovery**
* 🔍 **Distributed SELECT queries**
* ➕ **INSERT operations**
* ✏️ **UPDATE operations**
* 🗑️ **DELETE operations**
* 📋 **CREATE / DROP / SHOW TABLES**
* ❤️ **Node health monitoring**
* 📊 **Cluster statistics**
* ⚡ **Basic benchmarking**
* 🔄 **Failover detection/testing**
* 🖥️ **Interactive database CLI**
* 🧵 **Multi-threaded node servers**

---

# 🏗️ Architecture

NovaDB consists of a client-facing command-line interface, a database engine, and multiple database nodes.

```text
                         ┌─────────────────────┐
                         │      User / CLI      │
                         │      novadb>         │
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │    NodeManager      │
                         │                     │
                         │ Routing / Sharding  │
                         │ Replication         │
                         │ Distributed Queries │
                         │ Health Checks       │
                         └──────────┬──────────┘
                                    │
                    ┌───────────────┼───────────────┐
                    │               │               │
                    ▼               ▼               ▼
             ┌────────────┐  ┌────────────┐  ┌────────────┐
             │   Node-1   │  │   Node-2   │  │   Node-3   │
             │   :5001    │  │   :5002    │  │   :5003    │
             ├────────────┤  ├────────────┤  ├────────────┤
             │ Database   │  │ Database   │  │ Database   │
             │ WAL        │  │ WAL        │  │ WAL        │
             │ File Store │  │ File Store │  │ File Store │
             └────────────┘  └────────────┘  └────────────┘
```

Each node runs its own TCP server and maintains its own database state.

---

# 🔀 Sharding

NovaDB uses a simple **ID-based sharding strategy**.

For an inserted row, the first value is treated as the row ID.

The target node is calculated using:

```text
nodeIndex = id % numberOfNodes
```

For a 3-node cluster:

```text
ID       Node

1   →    Node-2
2   →    Node-3
3   →    Node-1
4   →    Node-2
5   →    Node-3
6   →    Node-1
```

This allows rows to be distributed across the available nodes.

### Example

```text
INSERT INTO students 7,Grace,22,Delhi
```

NovaDB determines the shard using the row ID:

```text
7 % 3 = 1
```

The row is therefore routed to the corresponding node.

---

# 🔁 Replication

After determining the primary node for a row, NovaDB sends the same record to the other nodes using the `REPLICATE` operation.

```text
                    INSERT
                       │
                       ▼
                  Primary Node
                       │
              ┌────────┴────────┐
              ▼                 ▼
           Replica           Replica
           Node-2            Node-3
```

This means a row can exist on multiple nodes, allowing the system to retrieve data even when the original shard is unavailable.

The same replication approach is used when updating or deleting replicated records.

> **Note:** NovaDB currently implements application-level replication. It does not implement a consensus protocol such as Raft or Paxos.

---

# 💾 Persistence

Each node maintains its own persistent database file.

The database uses Java file I/O to save and load table data.

Example node storage:

```text
novadb-node-1.data
novadb-node-2.data
novadb-node-3.data
```

When a node starts, it loads its persisted state before accepting requests.

---

# 📝 Write-Ahead Logging

NovaDB implements a **Write-Ahead Log (WAL)** to record important database operations before applying them to the database.

Operations such as:

```text
INSERT
UPDATE
DELETE
```

are recorded in the node's WAL.

The basic flow is:

```text
Client Request
      │
      ▼
   WAL Entry
      │
      ▼
Database Operation
      │
      ▼
Persistent Storage
```

When a node starts, the WAL is checked and recovery operations can be replayed to restore database state.

This demonstrates the basic principle of **write-ahead logging and crash recovery** used in database systems.

---

# 🌐 Node Communication

NovaDB nodes communicate using Java's built-in networking APIs:

```text
ServerSocket
Socket
BufferedReader
PrintWriter
```

Each node listens on its own port:

```text
Node-1 → 5001
Node-2 → 5002
Node-3 → 5003
```

Messages between nodes follow a lightweight operation-based protocol.

Example:

```text
INSERT|students|7,Grace,22,Delhi
```

Other operations include:

```text
CREATE_TABLE
INSERT
QUERY
UPDATE
DELETE
REPLICATE
HEALTH
STATS
SHOW_TABLES
```

---

# 🔍 Distributed SELECT

A `SELECT` operation queries all available nodes.

```text
                  SELECT
                     │
       ┌─────────────┼─────────────┐
       ▼             ▼             ▼
    Node-1         Node-2        Node-3
       │             │             │
       └─────────────┼─────────────┘
                     ▼
              Result Aggregation
                     │
                     ▼
               Unique Rows
```

NovaDB collects the results from the nodes and removes duplicate rows using their IDs before displaying the final result.

Example:

```text
novadb> SELECT * FROM students
```

Output:

```text
1,Alice,22,Delhi
2,Bob,23,Gurugram
3,Charlie,24,Noida

Total unique rows: 3
```

---

# ❤️ Node Health Monitoring

NovaDB provides a health-check mechanism that sends a `HEALTH` request to each node.

Example:

```text
novadb> health
```

Possible output:

```text
==================================
NODE HEALTH CHECK
==================================

Node-1 → ONLINE
Node-2 → ONLINE
Node-3 → ONLINE
```

If a node cannot be reached, it is reported as:

```text
Node-2 → OFFLINE
```

---

# 🔄 Failover Testing

NovaDB includes a failover testing mechanism.

The system can simulate the failure of one node and search the remaining nodes for a healthy node that can act as a failover candidate.

Example:

```text
novadb> failover
```

Output:

```text
==================================
FAILOVER TEST
==================================

Simulating failure of Node-1

Failure detected: Node-1
Failover node: Node-2

Failover successful.
```

> **Important:** This is currently a **failover detection/test mechanism**, not a full automatic leader-election or consensus implementation.

---

# 📊 Cluster Statistics

The `STATS` command collects information from the database nodes.

Example:

```text
novadb> stats
```

The system reports:

```text
Node-1 → ONLINE
   Rows     : 10
   Requests : 14

Node-2 → ONLINE
   Rows     : 8
   Requests : 11

Node-3 → ONLINE
   Rows     : 9
   Requests : 12

----------------------------------
Total Nodes : 3
Online Nodes: 3
Total Rows  : 27
```

---

# ⚡ Benchmarking

NovaDB includes a basic benchmark that performs multiple INSERT operations and measures:

* Total execution time
* Average latency
* Operations per second

Example:

```text
novadb> benchmark
```

Output format:

```text
==================================
        NOVADB BENCHMARK
==================================

Operations      : 30
Total time      : XX.XX ms
Average latency : X.XX ms/op
Throughput      : XX.XX ops/sec
```

The benchmark is intended as a simple performance measurement rather than a production-grade database benchmark suite.

---

# 🖥️ Command Line Interface

NovaDB provides an interactive CLI.

Start the application and use:

```text
novadb>
```

Available commands:

| Command                                                 | Description                                 |
| ------------------------------------------------------- | ------------------------------------------- |
| `CREATE TABLE <name>`                                   | Create a table on all nodes                 |
| `DROP TABLE <name>`                                     | Drop a table from all nodes                 |
| `INSERT INTO <table> <values>`                          | Insert a row using sharding and replication |
| `SELECT * FROM <table>`                                 | Perform a distributed query                 |
| `UPDATE <table> SET <column> = <value> WHERE id = <id>` | Update a row across replicas                |
| `DELETE FROM <table> WHERE id = <id>`                   | Delete a row across replicas                |
| `SHOW TABLES`                                           | Display available tables                    |
| `HEALTH`                                                | Check node health                           |
| `STATS`                                                 | Display cluster statistics                  |
| `BENCHMARK`                                             | Run the basic benchmark                     |
| `FAILOVER`                                              | Run the failover test                       |
| `HELP`                                                  | Display available commands                  |
| `EXIT`                                                  | Exit NovaDB                                 |

---

# 🧪 Example Usage

### 1. Start NovaDB

mvn clean compile
```

Then run:

```bash
java -cp target/classes com.novadb.NovaDBApplication
```

The application starts three nodes:

```text
Node-1 listening on port 5001
Node-2 listening on port 5002
Node-3 listening on port 5003
```

---

### 2. Create a table

```text
novadb> CREATE TABLE students
```

---

### 3. Insert data

```text
novadb> INSERT INTO students 1,Alice,22,Delhi
novadb> INSERT INTO students 2,Bob,23,Gurugram
novadb> INSERT INTO students 3,Charlie,24,Noida
```

NovaDB determines the shard using the ID and replicates the row to the other nodes.

---

### 4. Query the distributed database

```text
novadb> SELECT * FROM students
```

---

### 5. Update a row

```text
novadb> UPDATE students SET name = Daniel WHERE id = 2
```

---

### 6. Delete a row

```text
novadb> DELETE FROM students WHERE id = 3
```

---

### 7. Check cluster health

```text
novadb> HEALTH
```

---

### 8. View statistics

```text
novadb> STATS
```

---

### 9. Run benchmark

```text
novadb> BENCHMARK
```

---

### 10. Test failover

```text
novadb> FAILOVER
```

---

# 📁 Project Structure

```text
NovaDB/
│
├── pom.xml
│
├── src/
│   └── main/
│       └── java/
│           └── com/
│               └── novadb/
│
│                   ├── NovaDBApplication.java
│                   │
│                   ├── cli/
│                   │   └── Console.java
│                   │
│                   ├── engine/
│                   │   ├── Database.java
│                   │   ├── FileManager.java
│                   │   └── WriteAheadLog.java
│                   │
│                   ├── model/
│                   │   ├── Row.java
│                   │   └── Table.java
│                   │
│                   ├── node/
│                   │   ├── Node.java
│                   │   ├── NodeManager.java
│                   │   ├── NodeMessage.java
│                   │   └── ShardManager.java
│                   │
│                   └── parser/
│                       └── CommandParser.java
│
└── README.md
```

### Main components

**`NovaDBApplication`**

Initializes the database cluster and starts the CLI.

**`Console`**

Provides the interactive command-line interface and parses user commands.

**`Node`**

Represents an individual database node and handles TCP connections and database operations.

**`NodeManager`**

Coordinates the cluster, routing, replication, distributed queries, health checks, statistics, benchmarking, and failover testing.

**`ShardManager`**

Determines which node should store a row using the row ID.

**`Database`**

Implements table and row operations and manages database state.

**`FileManager`**

Handles persistence of database data to disk.

**`WriteAheadLog`**

Records database operations and supports recovery when nodes restart.

**`NodeMessage`**

Represents messages exchanged between database nodes.

**`Row` / `Table`**

Represent the basic database data model.

---

# 🛠️ Tech Stack

| Technology                | Usage                           |
| ------------------------- | ------------------------------- |
| **Java**                  | Core implementation             |
| **Java Sockets**          | Node-to-node communication      |
| **Maven**                 | Build and dependency management |
| **File I/O**              | Persistent storage              |
| **Threads**               | Concurrent client connections   |
| **Write-Ahead Logging**   | Operation logging and recovery  |
| **Collections Framework** | In-memory database structures   |

The project is configured to compile against **Java 17**.

---

# 🚀 Getting Started

## Prerequisites

Make sure the following are installed:

* Java 17+
* Maven 3+

Verify:

```bash
java -version
mvn -version
```

---

## Clone the repository

```bash
git clone https://github.com/sherinkhan071/NovaDB.git
```

Move into the project:

```bash
cd NovaDB
```

---

## Build

```bash
mvn clean compile
```

---

## Run

```bash
java -cp target/classes com.novadb.NovaDBApplication
```

Once the nodes start, the CLI will appear:

```text
novadb>
```

Type:

```text
help
```

to see the available commands.

---

# 🧠 Concepts Demonstrated

NovaDB was built as a hands-on exploration of several systems and database concepts:

### Distributed Systems

* Multiple cooperating nodes
* Network communication
* Request routing
* Replication
* Health monitoring
* Failure detection

### Database Systems

* Tables
* Rows
* CRUD operations
* Persistent storage
* Query execution
* Write-Ahead Logging

### Systems Programming

* TCP sockets
* Server/client communication
* Threads
* File I/O
* Recovery mechanisms

### Data Distribution

* Deterministic sharding
* Distributed reads
* Replicated writes
* Duplicate result handling

---

# 🔮 Future Improvements

NovaDB is intentionally a lightweight educational implementation. Possible future improvements include:

* [ ] Raft-based consensus
* [ ] Automatic leader election
* [ ] Automatic replica recovery
* [ ] Dynamic node discovery
* [ ] Configurable replication factor
* [ ] Better query parsing
* [ ] Indexing
* [ ] B-tree based storage
* [ ] Transactions
* [ ] Stronger consistency guarantees
* [ ] Configurable cluster size
* [ ] Persistent request/latency metrics
* [ ] Automated integration tests
* [ ] Containerized deployment with Docker
* [ ] More advanced benchmarking

---

# 🎯 Project Goal

NovaDB was created to understand what happens underneath a database system rather than relying entirely on an existing database engine.

The project focuses on implementing core ideas such as:

```text
Data
 ↓
Storage
 ↓
Persistence
 ↓
WAL
 ↓
Node
 ↓
Network Communication
 ↓
Sharding
 ↓
Replication
 ↓
Distributed Queries
