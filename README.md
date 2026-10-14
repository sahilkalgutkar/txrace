# txrace

[![CI](https://github.com/sahilkalgutkar/txrace/actions/workflows/ci.yml/badge.svg)](https://github.com/sahilkalgutkar/txrace/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/sahilkalgutkar/txrace/branch/main/graph/badge.svg)](https://codecov.io/gh/sahilkalgutkar/txrace)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

I built txrace to run concurrent JDBC transactions one statement at a time,
walk through the orders they could have run in, and report any order whose
result no serial execution could have produced, as a trace you can read and
replay.

The bugs I'm after live in service code, not in the database: read a row,
check something, write. That pattern quietly assumes an isolation level it
doesn't have, and it passes every test because tests run one request at a time.

What it found:

- **Two races in my own OAuth provider.** Two concurrent refreshes of one token
  both succeeded and left two live tokens in the family, and the revocation
  meant to catch that could miss a token being issued at the same moment. Both
  are fixed in [trust-platform](https://github.com/sahilkalgutkar/trust-platform)
  with integration tests that race real requests. [The story is below](#the-bug-i-went-looking-for).
- **A fix that looked right and wasn't.** A single bulk revocation still missed
  the new token. A stress test that released both requests together caught it
  in 2 of 4000 trials; txrace found it on the second order it ran. [Numbers below](#stress-testing-against-exploration).
- **Hermitage's PostgreSQL table, all 30 cells**, reproduced from scheduled
  runs, and a first table for H2. [See Hermitage](#hermitage).

There is a JUnit 5 test type for using it, `@RaceTest`. [See below](#using-it-from-junit).

## Tracing every statement

The interception layer. `TracingDataSource` wraps any `DataSource` and hands
out connections whose statements, batches, commits and rollbacks each become a
step. Every step passes through a `Gate` before it reaches the driver and again
once the driver has answered, and lands in a `Trace` with its bound parameters
and what came back. The gate is where the scheduler holds a transaction back
until its turn.

I used dynamic proxies rather than hand-written wrappers because `Connection`
alone has more than fifty methods and almost all of them should pass straight
through. The handful that matter are picked out by name.

To check the layer end to end, I first wrote by hand an order the scheduler
now finds by itself: two deposits into one account, interleaved on H2 at its
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
txrace scenarios: the same table, the same transactions, the interleavings
from its PostgreSQL page, and a check for whether the anomaly showed. Each one
runs through the scheduler at every level in Hermitage's PostgreSQL row and
every level H2 has. Two things differ from postgres.md. The column is `val`,
because `value` is reserved in H2. And the OTV reader selects the whole table
each time, as Hermitage's MySQL test does, for a reason that comes up below.

PostgreSQL reproduces Hermitage's table exactly (✓ prevented, — can occur):

| Level | G0 | G1a | G1b | G1c | OTV | PMP | P4 | G-single | G2-item | G2 |
|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| read committed | ✓ | ✓ | ✓ | ✓ | ✓ | — | — | — | — | — |
| repeatable read | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | — | — |
| serializable | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

Hermitage doesn't cover H2, so this one is new:

| Level | G0 | G1a | G1b | G1c | OTV | PMP | P4 | G-single | G2-item | G2 |
|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| read uncommitted | ✓ | — | — | — | — | — | — | — | — | — |
| read committed | ✓ | ✓ | ✓ | ✓ | ✓ | — | — | — | — | — |
| repeatable read | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | — | — |
| snapshot | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | — | — |
| serializable | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | — | — |

It agrees with H2's own documentation, which calls its SERIALIZABLE partial
because it "doesn't ensure equivalence of concurrent and serializable
execution of transactions that perform write operations": write skew gets
through, as under snapshot isolation. The documentation also says phantoms
are possible under REPEATABLE READ, which Hermitage's PMP test doesn't show,
though one test can't rule them out.

My first version of this table had READ UNCOMMITTED preventing OTV, which
would be odd for a level that shows uncommitted writes. postgres.md's reader
takes one row per select, and by its first select the second writer has
already put 12 in row 1, so the reader never gets to see a value that writer
replaces. Read both rows at once, as Hermitage does for MySQL and SQL Server,
and H2 shows 12 next to the first writer's 19: it has seen the second
transaction, yet not all of it.

The stronger check is the other direction. For every anomaly a level allows,
the checker has to find an order no serial order explains on its own, without
being shown Hermitage's interleaving, and that order has to show the anomaly
itself. That second part matters: G1b's and G1c's transactions break
serializability at READ COMMITTED too, with a non-repeatable read and with
write skew, so a flagged order alone would have counted at a level that
prevents both. This holds on H2 and PostgreSQL with a bound of two
preemptions. And at a level that prevents them all, the checker has to judge
every order and flag none. Only PostgreSQL's SERIALIZABLE qualifies, and it
does.

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

## The bug I went looking for

I built txrace with one suspect in mind. My
[trust-platform](https://github.com/sahilkalgutkar/trust-platform) is an OAuth
provider, and its refresh grant reads the token, rejects it if it has been
consumed, issues a new one, and lets Hibernate write the consumption when the
transaction commits. Under READ COMMITTED, nothing stops a second refresh from
reading the token in between and passing the same check.

To test that without guessing at what the code sends, I turned on Hibernate's
SQL log, ran one real refresh, and replayed those statements in txrace with the
same text and in the same order. Of the 40 orders with at most two preemptions,
38 end in a way no serial order can, and without a bound it is 180 of 182. The
one with the fewest preemptions, abridged here to the first words of each
statement:

```text
     transaction 1                          transaction 2
  1  select … where … token_hash=?  -> rows
  2  select … where rte1_0.id=?  -> rows
  3  insert into refresh_tokens …  -> updated 1
  4  update refresh_tokens set …  -> updated 1
  5                                         select … where … token_hash=?  -> rows
  6                                         select … where rte1_0.id=?  -> rows
  7                                         insert into refresh_tokens …  -> updated 1
  8                                         update refresh_tokens set …  -> updated 1 after waiting for a lock
  9  COMMIT  -> done
 10                                         COMMIT  -> done
```

The first refresh has done everything but commit when the second reads the
token, so the second still sees it unused. Its update waits for the first one's
lock and goes through the moment the first commits. Both return a new token, and
the family ends up with two live ones, which is the state reuse detection exists
to prevent. Run one after the other, the second refresh is rejected as a replay.
Authorization codes had the same race one step earlier in the flow.

The fix consumes the token with `UPDATE … WHERE consumed_at IS NULL` right after
the check, and treats an update that changed nothing as a replay. All 24
orders of the fixed version with at most two preemptions pass, and so do all 36
without a bound. REPEATABLE READ would also have refused the second refresh,
which the zoo checks too, but as a serialization failure rather than a detected
replay, and the conditional update fixes it without changing the isolation
level.

The fix had a trap of its own, and txrace shows it as well. The refresh that
loses the conditional update waits for the winner, changes nothing, and still
holds the row's lock until its transaction ends. trust-platform revokes a
replayed token's family in a separate transaction, and that revocation updates
the same row. Started from inside the losing request, it would wait for a lock
its own request holds, forever. In this run, the third transaction is the
revocation, and it has to wait for the loser's rollback:

```text
  3  c1 t1  update refresh_tokens rte1_0 set consumed_at=? where … consumed_at is null …  -> updated 1
  4  c2 t1  update refresh_tokens rte1_0 set consumed_at=? where … consumed_at is null …  -> updated 0 after waiting for a lock
  …
  7  c1 t1  COMMIT  -> done
  8  c3 t1  update refresh_tokens rte1_0 set revoked_at=? where rte1_0.family_id=? …  -> updated 2 after waiting for a lock
  9  c2 t1  ROLLBACK  -> done
 10  c3 t1  update refresh_tokens rte1_0 set revoked_at=? where rte1_0.family_id=? …  -> updated 0
 11  c3 t1  COMMIT  -> done
```

So trust-platform now revokes the family after the losing grant has rolled back.

Reviewing that fix, I modelled the revocation as well, and it had the same race
from the other side. It read the family's tokens and saved each one revoked.
Racing a refresh of the family's live token, it read the family after the
refresh had consumed the token and before the refresh's new token was
committed, so the new token stayed live in a revoked family, and the save wrote
the consumed token back as it had been read, unconsumed. Revoking with one
`UPDATE … WHERE family_id = ?` isn't enough either: the update waits for the
refresh's lock, but its snapshot was taken before the new token existed. The
revocation now repeats that update until it revokes nothing, which is the
second pass in the trace above. With one pass, the checker still finds an order
that leaves a live token.

The fixes, with integration tests that race real requests on PostgreSQL, are in
[trust-platform#9](https://github.com/sahilkalgutkar/trust-platform/pull/9).
The specimens are [`RefreshTokenReuse`](src/test/java/com/sahilkalgutkar/txrace/zoo/RefreshTokenReuse.java),
[`CodeReuse`](src/test/java/com/sahilkalgutkar/txrace/zoo/CodeReuse.java) and
[`FamilyRevocation`](src/test/java/com/sahilkalgutkar/txrace/zoo/FamilyRevocation.java).

## A zoo of racy methods

The trust-platform races are three of six specimens, each kept next to its fix
and run on PostgreSQL at its default, READ COMMITTED. The checker has to catch
every bug, and pass every fix having judged every order with at most two
preemptions. A passing fix also has to show that its transactions did their
work: the checker excuses a transaction the database refused, so a fix whose
statements always failed would otherwise pass as a serial run of nothing.

| Specimen | The code | What goes wrong | The fix |
|---|---|---|---|
| [`Overdraft`](src/test/java/com/sahilkalgutkar/txrace/zoo/Overdraft.java) | check the balance, then `SET balance = balance - ?` | both payments pass the check, and 100 ends at -20 | check in the update: `WHERE balance >= ?` |
| [`DuplicateSignup`](src/test/java/com/sahilkalgutkar/txrace/zoo/DuplicateSignup.java) | look for the email, then insert it | one address, two accounts | a unique index, and `ON CONFLICT DO NOTHING` to say who lost |
| [`LastOwner`](src/test/java/com/sahilkalgutkar/txrace/zoo/LastOwner.java) | count the owners, leave if there are two | both leave, and the team has no owner | lock the team's row before counting |
| [`RefreshTokenReuse`](src/test/java/com/sahilkalgutkar/txrace/zoo/RefreshTokenReuse.java) | trust-platform's refresh grant | two live tokens in one family | a conditional update, with zero rows meaning a replay |
| [`CodeReuse`](src/test/java/com/sahilkalgutkar/txrace/zoo/CodeReuse.java) | trust-platform's code grant | one code, two sets of tokens | the same |
| [`FamilyRevocation`](src/test/java/com/sahilkalgutkar/txrace/zoo/FamilyRevocation.java) | trust-platform revoking a family, racing a refresh | the refresh's new token stays live | revoke with an update repeated until it finds nothing |

The overdraft is the one people get wrong after fixing the lost update: a
relative update keeps both payments, but the check was made on a balance the
other payment was about to change.

The last owner taught me something about the fix. Locking the team's row makes
the second owner wait, and at READ COMMITTED its count then runs after the first
owner has gone. At REPEATABLE READ the same fix fails, and the checker finds the
order: the snapshot is taken when the first statement starts, and the second
owner's first statement is the lock it waits on, so it counts from a snapshot
older than the wait.

## Stress testing against exploration

The obvious alternative to all of this is a stress test: start the
transactions on real threads at the same moment, many times, and look at what
they leave behind. [`ZooBenchmark`](src/test/java/com/sahilkalgutkar/txrace/zoo/ZooBenchmark.java)
runs one against each bug in the zoo, 1000 trials a row, and judges every
trial the way the checker judges an order, against the endings of the serial
orders. It releases the threads at the same instant, which is the best a
stress test can do, then up to 1 ms and up to 10 ms apart, which is closer to
how two requests arrive. The last row is the one-pass revocation, the fix that
looked right and wasn't. One run on my laptop (Apple silicon, the embedded
PostgreSQL 18):

| Bug | Exploration, up to two preemptions | Stress, together | up to 1 ms apart | up to 10 ms apart |
|---|---|---|---|---|
| `Overdraft` | 10 of 12 orders fail, first at #2 (1.1 s) | 1000/1000, first at #1 | 475/1000, first at #1 | 281/1000, first at #1 |
| `DuplicateSignup` | 12 of 14 orders fail, first at #2 (263 ms) | 1000/1000, first at #1 | 430/1000, first at #3 | 271/1000, first at #15 |
| `LastOwner` | 12 of 14 orders fail, first at #2 (260 ms) | 1000/1000, first at #1 | 528/1000, first at #4 | 251/1000, first at #1 |
| `RefreshTokenReuse` | 38 of 40 orders fail, first at #2 (2.4 s) | 1000/1000, first at #1 | 741/1000, first at #2 | 288/1000, first at #3 |
| `CodeReuse` | 38 of 40 orders fail, first at #2 (2.4 s) | 1000/1000, first at #1 | 830/1000, first at #1 | 306/1000, first at #2 |
| `FamilyRevocation` | 11 of 21 orders fail, first at #2 (2.5 s) | 48/1000, first at #7 | 260/1000, first at #7 | 50/1000, first at #15 |
| `FamilyRevocation`, one pass | 3 of 9 orders fail, first at #2 (1.2 s) | 0/1000, never | 180/1000, first at #3 | 10/1000, first at #21 |

I expected exploration to win at finding the bugs, and on most of these it
doesn't. With nothing between the statements and the database on the same
machine, a stress test that releases both transactions together catches five
of the six original bugs on its first trial. Spread the start out to 10 ms and
it still catches them in 15% to 31% of trials, across four runs. These are
wide races, and a few hundred trials find them.

The difference is the two revocation rows, where the window is narrow. The
revocation has to land after the refresh has consumed the token and before it
commits the new one. Released together, the revocation's first statement
nearly always runs before the refresh has consumed anything, which is
harmless, so the original bug showed in 48 trials of 1000 and the one-pass fix
in none. Only a start that happens to be spread out finds the window, and
spreading it further makes it rare again: the one-pass fix showed in 180
trials at up to 1 ms apart and in 10 at up to 10 ms. Exploration found both on
the second order it ran, and the whole check of the one-pass fix took 1.2
seconds.

The rest is what a stress test can't give however often it hits. A failing
trial says the bug happened, not how: there's no trace, and the next run may
not do it again. A passing stress test says the bug wasn't seen. A passing
check says every order with up to two preemptions ended the way a serial order
does, which is a statement about the fix rather than about the luck of the
run. The real fixes went through 3000 stress trials each without being flagged,
but that is the harness checking itself, not evidence the fixes are right.

Exploration's counts are the same on every run. The stress rates move: across
four runs, the overdraft at up to 10 ms apart was caught in 175, 176, 281 and
276 trials of 1000, and the one-pass fix released together in 0, 2, 0 and 0.

## Prior work

- **CHESS** (Musuvathi and Qadeer, PLDI 2007; Musuvathi et al., OSDI 2008)
  explores the thread interleavings of a program without storing states, under
  a bound on preemptions, and showed that most concurrency bugs need very few.
  The explorer is that search, with SQL statements as the steps and the
  database as the shared state.
- **[Hermitage](https://github.com/ept/hermitage)** (Martin Kleppmann) is the
  catalogue of isolation anomalies, with a table of which ones each database
  prevents. Reproducing its PostgreSQL row was my check that the scheduler runs
  statements in the order it says.
- **ACIDRain** (Warszawski and Bailis, SIGMOD 2017) found exploitable races of
  this read-check-write shape in widely used e-commerce applications, working
  from the SQL they log. It is the argument that these bugs matter outside a
  test suite: someone can send the second request on purpose.
- **[Elle](https://github.com/jepsen-io/elle)** (Kingsbury and Alvaro,
  VLDB 2020) checks histories from real concurrent runs for Adya's anomalies,
  using workloads designed so each read reveals the order of versions, such as
  appending to lists. It tests the database. txrace assumes the database is
  right and tests the code on top of it, which is why it compares outcomes
  with serial runs instead of building a dependency graph: application code
  doesn't write values that reveal their versions, and a JDBC proxy can't see
  which rows a statement touched.
- Adya's thesis, *Weak Consistency* (MIT, 1999), and Berenson et al.,
  *A Critique of ANSI SQL Isolation Levels* (SIGMOD 1995), are where the
  anomaly names come from.

## Limits

- **The number of orders.** It grows factorially with the steps. The two
  refreshes in the zoo already have 182 orders, and every order runs against a
  freshly reset database, so txrace is for two or three short transactions,
  not a whole request path. The bound keeps it practical, 40 of those 182 with
  two preemptions, and found every bug in the zoo. It also limits the claim: a
  passing check says no order within the bound ends badly, not that none does.
- **Outcomes, not dependencies.** The check compares what the transactions
  returned and what the observation reads. An anomaly that changes nothing
  observed passes, as write skew did when the transactions only counted rows,
  so what you observe decides what can be found.
- **The same start every time.** The starting state is put back before every
  order, and anything that differs on every run, such as generated keys,
  tokens and timestamps, has to be left out of the endings or ignored.
- **The same steps have to mean the same statements.** The scheduler replays
  the order of steps exactly, not what each step does. A transaction that
  loops over a `SELECT` without `ORDER BY` gets the rows in the order they lie
  on disk, which can change once vacuum has freed space, and then the same
  order of steps runs its updates in a different order. The explorer checks
  that each replay is offered the same transactions at every step, which
  doesn't catch this. I hit it in the benchmark: the revocation specimen's
  count of failing orders went from 11 in one run to 12 in the next, after the
  stress trials before it had churned the table. Sorting the rows it saves
  fixed that.
- **One JDBC connection per transaction.** Work done on another connection,
  another thread or a non-JDBC driver isn't scheduled. Code behind a framework
  has to be reached through its SQL, which is why I replayed Hibernate's log
  for trust-platform rather than calling the service.
- **Lock waits cost time.** On PostgreSQL each wait costs `deadlock_timeout`,
  once per order it occurs in, so a test database wants it set low. Databases
  other than H2 and PostgreSQL aren't watched for lock waits at all, so a step
  stuck on a lock there runs into the timeout.
- The interception gaps under [Known gaps](#known-gaps).

## Status

- [x] JDBC proxy, gate and trace
- [x] A scheduler that replays a given order of steps exactly
- [x] Noticing a statement that is waiting on another transaction's lock (Postgres and H2)
- [x] Exploring orders, with a bound on preemptions
- [x] Comparing each order against every serial order, and printing the shortest failing one
- [x] The Hermitage anomaly cases as a test suite
- [x] A real target: trust-platform's token grants, and a zoo of racy methods with their fixes
- [x] A JUnit extension, and a benchmark against plain stress testing

## Building

Java 21 or newer. The Maven wrapper fetches Maven itself. The tests run against
an in-process H2 database and an embedded PostgreSQL 18, which starts from
binaries in the build, so neither Docker nor a local install is needed.

```bash
./mvnw verify
```

The stress benchmark isn't part of the build, because it takes about ten
minutes and its numbers depend on the machine:

```bash
./mvnw test -Dtest=ZooBenchmark
```
