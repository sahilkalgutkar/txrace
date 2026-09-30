# txrace

[![CI](https://github.com/sahilkalgutkar/txrace/actions/workflows/ci.yml/badge.svg)](https://github.com/sahilkalgutkar/txrace/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/sahilkalgutkar/txrace/branch/main/graph/badge.svg)](https://codecov.io/gh/sahilkalgutkar/txrace)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

I'm building a tool that runs concurrent JDBC transactions one statement at a
time, walks through the orders they could have run in, and reports any order
whose result no serial execution could have produced.

The bugs I'm after live in service code, not in the database: read a row,
check something, write. That pattern quietly assumes an isolation level it
doesn't have, and it passes every test because tests run one request at a time.

## What works so far

The interception layer. `TracingDataSource` wraps any `DataSource` and hands
out connections whose statements, batches, commits and rollbacks each become a
step. Every step passes through a `Gate` before it reaches the driver and again
once the driver has answered, and lands in a `Trace` with its bound parameters
and what came back. The gate is where the scheduler will hold a transaction
back until its turn.

I used dynamic proxies rather than hand-written wrappers because `Connection`
alone has more than fifty methods and almost all of them should pass straight
through. The handful that matter are picked out by name.

To check the layer end to end, I wrote the order the scheduler will eventually
find by itself: two deposits into one account, interleaved by hand on H2 at its
default isolation level. Each transaction reads the balance, adds its deposit
and writes the result back. Either serial order ends at 180, and this one ends
at 150:

```text
  1  c1 t1  SELECT balance FROM account WHERE id = ?  [1]  -> rows
  2  c2 t1  SELECT balance FROM account WHERE id = ?  [1]  -> rows
  3  c1 t1  UPDATE account SET balance = ? WHERE id = ?  [130, 1]  -> updated 1
  4  c1 t1  COMMIT  -> done
  5  c2 t1  UPDATE account SET balance = ? WHERE id = ?  [150, 1]  -> updated 1
  6  c2 t1  COMMIT  -> done
```

The same order with `SET balance = balance + ?` keeps both deposits. The test is
in [`LostUpdateTest`](src/test/java/com/sahilkalgutkar/txrace/jdbc/LostUpdateTest.java).

Closing a connection with work still pending rolls it back as a step of its
own. H2, PostgreSQL and connection pools all roll back at close anyway, and
doing it explicitly puts the rollback, and the locks it releases, through the
gate.

## Running a schedule

`Scheduler` runs each transaction on its own thread and connection, and holds
every step at the gate until the schedule says it is that transaction's turn.
The schedule is a list of transaction numbers, one per step, commit included:

```java
Scheduler scheduler = new Scheduler(dataSource);
Run run = scheduler.run(Schedule.parse("1 2 1 1 2 2"), deposit(1, 30), deposit(1, 50));
```

Each `deposit` reads the balance and writes back the balance plus its amount,
and the scheduler commits it once it returns. That schedule is the lost update
from above, now happening across two real threads, and it ends at 150 every
time. `1 1 1 2 2 2` ends at 180.

A transaction needs its own thread because JDBC calls block: there is no way
to pause one halfway through a call on a shared thread, so it has to be held
back before the call. I open the connections in order on the calling thread,
so connection n in the trace is always transaction n.

The scheduler is strict. A schedule that names a transaction that has already
finished, or runs out while one still has a step to take, is an error rather
than something to guess at, because a schedule that replays exactly is the
whole point. Running every one of the 560 orders of three independent
transactions puts each step exactly where its schedule said.

One thing it cannot do yet is tell when a released step is waiting on a lock
held by a transaction that is still parked. For now that step is reported
once a timeout runs out, or H2's own lock timeout fails it first.

### Known gaps

A review of this layer turned up places where work reaches the database
without a step, or where a step ends up in the wrong transaction. I fixed the
ones the scheduler depends on. These are still open:

- Updatable result sets (`updateRow` and friends), and anything run through
  `ResultSet.getStatement()` or `DatabaseMetaData.getConnection()`, which hand
  back the driver's own objects.
- Transaction control written as SQL (`COMMIT`, `SET AUTOCOMMIT`) instead of
  through the JDBC API, DDL that commits implicitly, and H2 committing inside
  `setTransactionIsolation` when it is called part-way through a transaction.
- Errors in SQLState class 40. H2 rolls the whole transaction back and starts
  a new one, while PostgreSQL keeps it open and aborted until you roll back.
  I want real PostgreSQL in the tests before choosing how to count these.
- A failed batch keeps its per-row counts in the exception but not the trace.
- `CallableStatement` parameters: named ones are not recorded, and OUT
  parameters shift the positions of the rest.
- Savepoints, and connections unwrapped to their driver class.

## Status

- [x] JDBC proxy, gate and trace
- [x] A scheduler that replays a given order of steps exactly
- [ ] Noticing a statement that is waiting on another transaction's lock (Postgres and H2)
- [ ] Exploring orders, with a bound on preemptions
- [ ] Comparing each order against every serial order, and printing the shortest failing one
- [ ] The Hermitage anomaly cases as a test suite
- [ ] A real target
- [ ] A JUnit extension, and a benchmark against plain stress testing

## Building

Java 21 or newer. The Maven wrapper fetches Maven itself, and the tests use an
in-process H2 database, so nothing else needs to be running.

```bash
./mvnw verify
```
