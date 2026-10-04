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

### Steps that wait on a lock

Release transaction 2's update while transaction 1 still holds the row, and the
update cannot finish until transaction 1 commits, which it never will, because
it is parked waiting for its turn. A scheduler that simply waited would hang.

So when a released step does not finish promptly, I ask the database whether it
is waiting on a lock, and whose: `pg_blocking_pids` on PostgreSQL,
`INFORMATION_SCHEMA.SESSIONS` on H2, over a connection of its own. If it is,
the step is marked and the schedule moves on. The step finishes later, during
the holder's commit:

```text
  3  c1 t1  UPDATE account SET balance = ? WHERE id = ?  [130, 1]  -> updated 1
  4  c2 t1  UPDATE account SET balance = ? WHERE id = ?  [150, 1]  -> updated 1 after waiting for a lock
  5  c1 t1  COMMIT  -> done
```

The part that took care was keeping that deterministic. A lock released by one
step lets a waiting step run on another thread, so after every step I wait for
each waiting step to settle, finishing or being seen waiting again, before the
next one goes. H2 can still name a holder for a moment after it has committed,
so a wait only counts if the holder still has a transaction open.

Each connection's session id is read before autocommit goes off. On
PostgreSQL, asking with autocommit off would start the transaction, and under
REPEATABLE READ that fixes its snapshot before the schedule says it should.

The same schedule then behaves the way each database documents. Under
PostgreSQL's READ COMMITTED, transaction 2's update overwrites transaction 1's
once the lock is free, and the deposit is lost. Under REPEATABLE READ, the same
update fails with `40001` instead.

Deadlocks needed one more thing to stay repeatable. PostgreSQL checks a wait
for a deadlock once, `deadlock_timeout` after it starts, and fails whichever
wait finds the cycle. My first version moved on as soon as it saw a step
waiting, so which transaction failed depended on how long the steps in
between took. Now I watch every newly waiting step for `deadlock_timeout`
before leaving it, so each earlier wait has had its check, and the wait that
closes a cycle is always the one PostgreSQL fails. H2 decides when the wait
starts and fails the youngest transaction in the cycle. When the youngest
closes it, both fail the same one; when the oldest closes it, they disagree,
and the tests pin down both. The price is that every lock wait on PostgreSQL
costs `deadlock_timeout`, so a test database wants it set low; mine use 100ms.

One step can free two waiting steps at once: a rollback that lets two inserts
of the same key go, say. They race, and the database picks the order, so the
same schedule could end two ways. The loser ends up waiting behind the
winner, which is how I spot it, and I refuse the schedule rather than report
whichever way it went. PostgreSQL queues a second waiter for a row behind the
first, not behind the holder, so updates of one row still go in queue order
there; H2 lets them race.

A schedule that asks for a transaction that is still waiting is refused,
unless every transaction still running is waiting, because that is a deadlock
only the database can break. The watch takes one connection of its own, so a
pool needs room for one more than the number of transactions.

Any other database is never seen waiting, and a stuck step is reported once
the scheduler's timeout runs out. So is any wait on H2 when the user is not an
admin, because H2 shows other sessions only to admins.

### Known gaps

A review of this layer turned up places where work reaches the database
without a step, or where a step ends up in the wrong transaction. I fixed the
ones the scheduler depends on, then the ones where result sets led back out of
the trace. Row changes made through an updatable result set are steps now, and
a failed batch keeps its per-row counts. These are still open:

- Transaction control written as SQL (`COMMIT`, `SET AUTOCOMMIT`) instead of
  through the JDBC API, DDL that commits implicitly, and H2 committing inside
  `setTransactionIsolation` when it is called part-way through a transaction.
- Errors in SQLState class 40, outside the scheduler. H2 rolls the whole
  transaction back and starts a new one, while PostgreSQL keeps it open and
  aborted until you roll back, so code that carries on after one gets its
  later steps counted in the wrong transaction. Under the scheduler a
  transaction that throws is rolled back as a step, which counts it right on
  both.
- With pgjdbc and a fetch size set, `ResultSet.next()` fetches more rows from
  the server outside any step, and `SELECT ... FOR UPDATE` takes its row locks
  as those rows arrive.
- `CallableStatement` parameters: named ones are not recorded, and OUT
  parameters shift the positions of the rest.
- On H2, waits for a table lock rather than a row lock, which only DDL takes,
  do not show in `INFORMATION_SCHEMA.SESSIONS`, so they run into the timeout.
- Savepoints, and connections unwrapped to their driver class.

## Status

- [x] JDBC proxy, gate and trace
- [x] A scheduler that replays a given order of steps exactly
- [x] Noticing a statement that is waiting on another transaction's lock (Postgres and H2)
- [ ] Exploring orders, with a bound on preemptions
- [ ] Comparing each order against every serial order, and printing the shortest failing one
- [ ] The Hermitage anomaly cases as a test suite
- [ ] A real target
- [ ] A JUnit extension, and a benchmark against plain stress testing

## Building

Java 21 or newer. The Maven wrapper fetches Maven itself. The tests run against
an in-process H2 database and an embedded PostgreSQL 18, which starts from
binaries in the build, so neither Docker nor a local install is needed.

```bash
./mvnw verify
```
