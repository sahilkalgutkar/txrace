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

### Known gaps

A review of this layer turned up places where work reaches the database
without a step, or where a step ends up in the wrong transaction. I fixed the
ones the scheduler depends on, then the ones where result sets led back out of
the trace. Row changes made through an updatable result set are steps now, and
a failed batch keeps its per-row counts. These are still open:

- Transaction control written as SQL (`COMMIT`, `SET AUTOCOMMIT`) instead of
  through the JDBC API, DDL that commits implicitly, and H2 committing inside
  `setTransactionIsolation` when it is called part-way through a transaction.
- Errors in SQLState class 40. H2 rolls the whole transaction back and starts
  a new one, while PostgreSQL keeps it open and aborted until you roll back.
  I want real PostgreSQL in the tests before choosing how to count these.
- `CallableStatement` parameters: named ones are not recorded, and OUT
  parameters shift the positions of the rest.
- Savepoints, and connections unwrapped to their driver class.

## Status

- [x] JDBC proxy, gate and trace
- [ ] A scheduler that replays a given order of steps exactly
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
