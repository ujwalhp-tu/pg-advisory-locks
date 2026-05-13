# PostgreSQL Advisory Locks: Comprehensive Architecture & Operations Guide

This document provides an in-depth architectural overview, operational guidelines, and theoretical foundations for the distributed locking service implemented in this project using PostgreSQL Advisory Locks.

---

## Table of Contents
1. [Core Concepts & Mechanics](#1-core-concepts--mechanics)
2. [Concurrency Models & MVCC](#2-concurrency-models--mvcc)
3. [Naming Strategies & Hashing Mathematics](#3-naming-strategies--hashing-mathematics)
4. [PostgreSQL Infrastructure & Performance](#4-postgresql-infrastructure--performance)
5. [Reliability, Failures, & Correctness](#5-reliability-failures--correctness)
6. [Observability & Monitoring](#6-observability--monitoring)

---

## 1. Core Concepts & Mechanics

### What exactly does a lock mean here?
In traditional relational databases, locks are intrinsically tied to physical or logical database objects (e.g., a row, a page, a table). 

A **PostgreSQL Advisory Lock** is entirely different. It is a **logical, application-defined mutex** that resides purely in PostgreSQL's shared memory. It has no relationship to any table or row. 
When the application requests a lock on the 64-bit integer `123456789`, PostgreSQL simply records in its internal lock manager: *"Transaction X holds exclusive rights to the number 123456789."* If Transaction Y requests the same number, PostgreSQL forces Transaction Y to wait. The application assigns business meaning (e.g., "Processing Order #55") to that number.

### Session-Level Locks vs. Transactional Locks
PostgreSQL offers two variants of advisory locks:
*   **Session-Level (`pg_advisory_lock`)**: The lock is held by the database connection (session) until explicitly released via `pg_advisory_unlock`, or until the TCP connection drops. 
    *   *Danger*: In modern Java applications using connection pools (like HikariCP), connections are never truly closed; they are returned to the pool. If an application throws an exception and misses the `unlock` call, the lock is leaked. The next thread to borrow that connection inherits the lock, leading to catastrophic system-wide deadlocks.
*   **Transaction-Level (`pg_advisory_xact_lock`)**: The lock is bound to the current database transaction. It is **automatically released** the exact millisecond the transaction commits or rolls back.
    *   *Advantage*: 100% safe from application-level memory leaks. Frameworks like Spring manage the transaction lifecycle, ensuring guaranteed release regardless of application errors. **Our service exclusively uses transaction-level locks.**

### How does lock serialization happen?
When multiple nodes request the same lock concurrently, serialization is handled by the PostgreSQL Lock Manager:
1.  **Fast-Path**: If the lock is uncontended, it is granted immediately using lightweight atomic operations in shared memory.
2.  **Wait Queue**: If the lock is held, the requesting backend process is placed into a FIFO (First-In-First-Out) wait queue.
3.  **OS Semaphores**: The waiting PostgreSQL process goes to sleep using OS-level semaphores. It consumes **zero CPU** while waiting.
4.  **Wakeup**: When the lock is released, the OS instantly wakes up the next process in the queue.

---

## 2. Concurrency Models & MVCC

### Pessimistic vs. Optimistic Concurrency
*   **Optimistic Concurrency (e.g., JPA `@Version`)**: Assumes conflicts are rare. Multiple threads read the data, modify it, and attempt to write. The database detects the conflict at commit time, allowing the first to succeed and forcing the others to roll back and retry. Best for high-throughput, read-heavy workloads.
*   **Pessimistic Concurrency (Advisory Locks)**: Assumes conflicts will happen and are expensive. The first thread locks the resource; all others wait. Best for write-heavy workloads or when operations have external side effects.

### What are odd fits for an MVCC pattern, and how do advisory locks mitigate them?
PostgreSQL uses **MVCC (Multi-Version Concurrency Control)** to handle concurrent transactions without locking rows for reads. However, MVCC falls apart when dealing with **external side effects**.

*   **The MVCC Failure Scenario**: Imagine two threads processing the same order concurrently. Both read the order, both call the Stripe API to charge $50, and both attempt to update the database. MVCC will catch the conflict at the database level and roll one back. However, **Stripe has already been charged twice**. You cannot "rollback" a REST API call.
*   **The Advisory Lock Mitigation**: By acquiring an advisory lock *before* reading the database or calling Stripe, the second thread is forced to wait. Once the first thread finishes (commits the DB and releases the lock), the second thread wakes up, sees the order is already marked as "PAID", and skips the Stripe call entirely.

---

## 3. Naming Strategies & Hashing Mathematics

### Strategy to name advisory locks
Tying lock names to code structure (e.g., `com.service.OrderService.process`) is an anti-pattern. If you refactor the class name, or run a Blue-Green deployment where V1 and V2 have different method names, they will fail to mutually exclude each other.
**Solution**: We use a centralized `LockResource` Enum registry combined with a business identifier (e.g., `ORDER_PROCESSING:12345`). This decouples the lock from the codebase and ties it to the business domain.

### Collision possibilities in hashing into 64-bit space
PostgreSQL's `pg_advisory_xact_lock` requires a 64-bit integer. Because our lock names are strings, we must hash them. We use Guava's `Murmur3_128` to generate a highly dispersed 64-bit hash.

**The Mathematics of Collisions (Birthday Paradox)**:
The 64-bit integer space contains $1.84 \times 10^{19}$ possible values. 
The probability of a collision $P$ for $n$ concurrent locks is approximately:
$P \approx 1 - e^{-n^2 / (2 \times 2^{64})}$

*   At **10,000** concurrent locks, the collision probability is $2.7 \times 10^{-12}$.
*   At **1,000,000** concurrent locks, the collision probability is $2.7 \times 10^{-8}$.
*   To reach a 1% chance of collision, you would need to hold **600 million** locks *at the exact same millisecond*.

**Conclusion**: For practical enterprise applications, the risk of a 64-bit hash collision is statistically zero.

---

## 4. PostgreSQL Infrastructure & Performance

### PostgreSQL configuration limitations
Advisory locks are stored in a fixed-size shared memory hash table allocated when PostgreSQL starts. 
The maximum number of locks the entire database can hold simultaneously is defined by the formula:
`max_locks_per_transaction` × `max_connections`

*   By default, `max_locks_per_transaction` is 64, and `max_connections` is 100.
*   This means a default Postgres installation can hold **6,400** concurrent locks.
*   If you exceed this, Postgres throws an `out of shared memory` error. If your application requires massive parallel locking, you must increase `max_locks_per_transaction` in `postgresql.conf` (requires a database restart).

### Any effects on DB performance?
Because advisory locks bypass disk I/O and WAL (Write-Ahead Logging), they are incredibly fast. Acquiring an uncontended lock takes microseconds.
However, extreme contention on the *exact same lock key* can cause CPU spikes due to spinlock contention within the Postgres Lock Manager. Normal distributed usage across different keys has negligible impact.

### How does a read replica affect the whole lock scenario?
Advisory locks modify shared memory state. Because Read Replicas are strictly read-only, **you cannot acquire an advisory lock on a replica**. Executing the lock function on a replica will result in a SQL exception. All lock acquisition queries must be routed to the Primary (Writer) node.

---

## 5. Reliability, Failures, & Correctness

### Possible failure scenarios, deadlocks, and stale locks
*   **Deadlocks**: If Thread A locks Resource 1 and waits for Resource 2, while Thread B locks Resource 2 and waits for Resource 1, a deadlock occurs. PostgreSQL has a built-in deadlock detector that runs after `deadlock_timeout` (default 1 second). It will automatically abort one of the transactions, throwing a `40P01` error, resolving the deadlock.
*   **Stale Locks**: As established, transactional locks make stale locks impossible. If a node loses power, the TCP connection severs, Postgres rolls back the orphaned transaction, and the lock is instantly freed.
*   **Connection Pool Exhaustion**: If threads wait indefinitely for locks, they hold active database connections. A sudden spike in contention can exhaust the HikariCP pool, bringing down the entire microservice. This is mitigated by enforcing strict **timeouts** on lock acquisition.

### Lock behavior in long-standing transactions
Because we use `@Transactional(propagation = Propagation.REQUIRED)`, the lock is held until the *entire* transaction completes.
**The Danger**: Long-standing transactions are toxic to PostgreSQL. Postgres relies on an `autovacuum` process to clean up dead rows (tuples). `autovacuum` cannot clean any row that died *after* the oldest active transaction started. 
If you hold an advisory lock (and thus a transaction) open for 10 minutes while waiting for a slow external API, you prevent Postgres from cleaning up dead rows across the *entire database* for 10 minutes, leading to table bloat and degraded query performance. **Keep locked transactions as short as possible.**

### Correctness guarantees for a distributed locking pattern
PostgreSQL provides **strict mutual exclusion (Safety)** and **liveness** on a single node. 
However, it is **not a consensus-based distributed system** (like ZooKeeper, etcd, or Redis Redlock). 
*   **The Split-Brain Risk**: PostgreSQL replication is asynchronous. If the Primary node crashes while Node A holds a lock, the Replica is promoted to Primary. Because the lock existed only in the RAM of the dead Primary, the new Primary has no knowledge of it. Node B can now acquire the lock on the new Primary, resulting in both Node A and Node B believing they hold exclusive access. 
*   **Conclusion**: Postgres advisory locks are excellent for operational efficiency and preventing race conditions, but should not be used if a split-brain during a rare database failover would cause catastrophic financial or data loss.

---

## 6. Observability & Monitoring

### Visibility and monitoring of locks
Advisory locks are fully visible via PostgreSQL system catalogs. You can monitor active and waiting locks by joining `pg_locks` with `pg_stat_activity`.

**Diagnostic Query:**
```sql
SELECT 
    l.pid, 
    l.objid AS lock_hash, 
    l.granted,
    a.application_name,
    a.query AS current_query, 
    a.state, 
    age(now(), a.query_start) AS duration
FROM pg_locks l 
JOIN pg_stat_activity a ON l.pid = a.pid 
WHERE l.locktype = 'advisory';
```
*   `granted = true`: The process holding the lock.
*   `granted = false`: Processes waiting in the queue.
*   `duration`: How long the transaction has been running (useful for catching long-standing transactions).

### Use cases for these locks and characteristic priority
1.  **External API Idempotency**: Preventing double-charging a credit card or sending duplicate emails.
2.  **Leader Election / Cron Jobs**: Ensuring a scheduled task (e.g., "Generate Daily Report") runs on exactly one node in a 50-node Kubernetes cluster.
3.  **Legacy System Rate Limiting**: Serializing access to a fragile downstream mainframe that crashes if hit with concurrent requests.
4.  **Complex Tree Updates**: Locking a parent entity when updating deeply nested children to prevent concurrent structural modifications.