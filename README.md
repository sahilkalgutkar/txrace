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
whichever way it went. H2 wakes every waiter for a row at once. PostgreSQL
usually keeps them in line, but when the holder commits, each waiting update
re-reads the newest version of the row, and the one behind can get there
first. I only catch it when the one behind wins; when the one in front wins,
the run looks repeatable even though it might have gone the other way.

Waits can also start while the others settle: a step freed by a commit goes on
to wait for another row. Those get the same time to decide on a deadlock as a
step just released, or the deadlock would break at some later moment and what
was ready next would depend on it. And if two waits that started together
deadlock each other on PostgreSQL, their checks race to pick the victim, so I
refuse that schedule too.

A schedule that asks for a transaction that is still waiting is refused,
unless every transaction still running is waiting, because that is a deadlock
only the database can break. The watch takes one connection of its own, so a
pool needs room for one more than the number of transactions.

Any other database is never seen waiting, and a stuck step is reported once
the scheduler's timeout runs out. So is any wait on H2 when the user is not an
admin, because H2 shows other sessions only to admins.

## Exploring orders

`Explorer` runs the transactions in every order their steps can take, each
from the same starting state, and keeps what it observed after each run:

```java
Exploration exploration = new Explorer(dataSource, database -> openAccounts(database))
        .observing(database -> balance(database))
        .explore(deposit(1, 30), deposit(1, 50));
```

The orders aren't known up front. A transaction can branch on what it reads,
and a step waiting on a lock isn't ready to go. So I search them the way the
CHESS paper does, without saving any state. One run records which transactions
were ready at each step. Then, for every ready transaction that wasn't picked,
I run again with the same choices up to that point and that transaction next,
and carry on from there. Each order runs exactly once. For independent
transactions the count matches the closed form, `(a+b+...)! / (a! b! ...)`:
560 for three transactions of three, three and two steps.

The number of orders grows fast, so the search can be bounded by preemptions:
switching away from a transaction that could have taken another step. With a
bound of zero only the serial orders run. Most concurrency bugs need one or
two preemptions to show, and the deposits bear that out:

| Preemption bound | Orders run | Ends at 180 | Ends at 150 | Ends at 130 |
|---|---|---|---|---|
| 0 | 2 | 2 | 0 | 0 |
| 1 | 6 | 2 | 2 | 2 |
| 2 | 12 | 2 | 5 | 5 |
| none | 14 | 2 | 6 | 6 |

With no bound there are 14 orders rather than the 20 that two independent
three-step transactions would have, because once one deposit holds the row,
the other's update waits and isn't ready.

An order the scheduler refuses as unrepeatable is kept apart rather than
failing the search. Three updates of one row give 30 orders on PostgreSQL, all
ending at the same balance, though once in a while one is refused when an
update overtakes the one ahead of it. H2 runs 24 and refuses 6, because there a
commit frees both waiting updates at once.

The search depends on every run of a prefix offering the same choices. Each
queued order carries what its parent run was offered at every step, and a
replay that is offered anything different is recorded as refused, rather than
ending the search with everything found so far thrown away. On PostgreSQL each
lock wait also costs `deadlock_timeout`, once per order it occurs in, so a
search with many waits wants that set low.

## Checking against the serial orders

`Checker` explores the orders and flags any that ends in a way running the
transactions one after another never could:

```java
Verdict verdict = new Checker(new Explorer(dataSource, Accounts::open).observing(Accounts::balance))
        .check(deposit(30), deposit(50));
System.out.print(verdict.report());
```

```text
2 transactions, 14 orders run.
12 orders end in a way no serial order does. The one with the fewest preemptions (1) is 1 1 2 2 1 2:

     transaction 1                                 transaction 2
  1  SELECT balance FROM account WHERE id = 1  ->
     rows
  2  UPDATE account SET balance = ? WHERE id = 1
     [130]  -> updated 1
  3                                                SELECT balance FROM account WHERE id = 1  ->
                                                   rows
  4                                                UPDATE account SET balance = ? WHERE id = 1
                                                   [150]  -> updated 1 after waiting for a lock
  5  COMMIT  -> done
  6                                                COMMIT  -> done

It ended with transaction 1 returned 130, transaction 2 returned 150; observed 150.
Run one after another, they end with:
  1 then 2: transaction 1 returned 130, transaction 2 returned 180; observed 180
  2 then 1: transaction 1 returned 180, transaction 2 returned 150; observed 180
```

There is no invariant to write. The serial orders are the reference, and an
ending is what each committed transaction returned plus whatever the
observation reads. The one rule that took thought is about rollbacks, and I
got it wrong first. A transaction the database refused, one that died on an
`SQLException` such as a serialization failure or a deadlock, left nothing
behind, so I compare the run against serial runs of just the others. That
makes a refusal a correct ending rather than a finding. My first version
excused every rollback, though, and a transaction that gives up because of
what it read is exactly how a non-repeatable read can show. So a transaction
that throws anything other than an `SQLException` is part of the ending, as
having given up. The refusal rule is what lets the checker tell isolation
levels apart on PostgreSQL:

| Isolation level | Two read-then-write deposits | Two doctors going off call |
|---|---|---|
| READ COMMITTED | fails: a deposit is lost | |
| REPEATABLE READ | holds: the second writer gets `40001` | fails: nobody is left on call |
| SERIALIZABLE | | holds: one of them gets `40001` |

The doctors case is write skew. Each checks that two doctors are on call
before going off, each sees two in its own snapshot, and both go.

"Ends the way some serial order does" is weaker than conflict
serializability, which asks whether the steps could be reordered into a serial
order. That stronger property would flag orders whose result is still fine,
and a tool for finding bugs in application code cares about the result.

A serial order the explorer runs again has to end the way it did the first
time. If it doesn't, something in the endings changes on every run, a token or
a timestamp, and the checker stops and says so rather than reporting every
order. Arrays compare by what is in them. And the check only holds when every
order ran and was judged: an order refused as unrepeatable, or one past a run
limit, means it cannot be said.

Sequences and identity columns need care. They move on even when a
transaction rolls back, so an observation that includes a generated key sees
the gap a refused transaction left, and the comparison fails. Leave generated
keys out of what is observed and returned.

Two knobs cover cases where the comparison is too strict. `ignoringReturnValues()`
compares only which transactions committed and what was observed, for
transactions that return something new every run, like a token. And
`judgingBy(invariant)` replaces the serial orders with a rule every ending must
keep.

## Hermitage

Martin Kleppmann's [Hermitage](https://github.com/ept/hermitage) is the
standard catalogue of isolation anomalies, with hand-run tests for each and a
published table of which ones each database prevents. I wrote its tests as
txrace scenarios: the same table, the same transactions, the same
interleavings, and a check for whether the anomaly showed. Each one runs
through the scheduler at every isolation level.

PostgreSQL reproduces Hermitage's table exactly (✓ prevented, — can occur):

| Level | G0 | G1a | G1b | G1c | OTV | PMP | P4 | G-single | G2-item | G2 |
|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| read committed | ✓ | ✓ | ✓ | ✓ | ✓ | — | — | — | — | — |
| repeatable read | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | — | — |
| serializable | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

Hermitage doesn't cover H2, so this one is new:

| Level | G0 | G1a | G1b | G1c | OTV | PMP | P4 | G-single | G2-item | G2 |
|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| read uncommitted | ✓ | — | — | — | ✓ | — | — | — | — | — |
| read committed | ✓ | ✓ | ✓ | ✓ | ✓ | — | — | — | — | — |
| repeatable read | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | — | — |
| serializable | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | — | — |

It agrees with H2's own documentation, which calls its SERIALIZABLE partial
because it "doesn't ensure equivalence of concurrent and serializable
execution of transactions that perform write operations": write skew gets
through, as under snapshot isolation. The documentation also says phantoms
are possible under REPEATABLE READ, which Hermitage's PMP test doesn't show,
though one test can't rule them out.

The stronger check is the other direction. For every anomaly a level allows,
the checker has to find an order no serial order explains on its own, without
being shown Hermitage's interleaving. And at a level that prevents them all,
PostgreSQL's SERIALIZABLE, it must find nothing. Both hold on H2 and
PostgreSQL, with a bound of two preemptions.

Getting there taught me something about what a result-based check can see. I
first wrote the write skew scenario with each transaction counting the rows it
read, and the checker found nothing: a count doesn't change when the other
transaction writes, so both transactions end exactly as they would serially,
even though their reads and writes form a cycle. Hermitage's version reads the
values, and with that the checker finds it. An anomaly that changes nothing
anyone observes is invisible to a check on outcomes, which is the price of not
asking for conflict serializability.

The plan was to label each counterexample with its Adya anomaly class, worked
out from the dependency edges in the run. That needs to know which rows each
statement read and wrote, and that isn't something a JDBC proxy can see without
parsing SQL, so each scenario names its anomaly instead.

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
- PostgreSQL letting a waiting update overtake the one in front goes unnoticed
  when the one in front happens to win.
- On H2, waits for a table lock rather than a row lock, which only DDL takes,
  do not show in `INFORMATION_SCHEMA.SESSIONS`, so they run into the timeout.
- Savepoints, and connections unwrapped to their driver class.

## Status

- [x] JDBC proxy, gate and trace
- [x] A scheduler that replays a given order of steps exactly
- [x] Noticing a statement that is waiting on another transaction's lock (Postgres and H2)
- [x] Exploring orders, with a bound on preemptions
- [x] Comparing each order against every serial order, and printing the shortest failing one
- [x] The Hermitage anomaly cases as a test suite
- [ ] A real target
- [ ] A JUnit extension, and a benchmark against plain stress testing

## Building

Java 21 or newer. The Maven wrapper fetches Maven itself. The tests run against
an in-process H2 database and an embedded PostgreSQL 18, which starts from
binaries in the build, so neither Docker nor a local install is needed.

```bash
./mvnw verify
```
