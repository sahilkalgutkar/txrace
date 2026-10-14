package com.sahilkalgutkar.txrace.junit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import com.sahilkalgutkar.txrace.check.Verdict;
import com.sahilkalgutkar.txrace.jdbc.H2Extension;
import com.sahilkalgutkar.txrace.schedule.Transaction;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

/**
 * Runs small race tests through the JUnit engine and checks what it made of them. The samples are
 * nested classes, which Surefire leaves alone, so they only run from here.
 */
class RaceExtensionTest {

    /** One account with 100 in it. Creates the table on the first run, so the samples need no setup of their own. */
    static void oneAccount(DataSource database) throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET DEFAULT_LOCK_TIMEOUT 10000");
            statement.execute("CREATE TABLE IF NOT EXISTS account (id INT PRIMARY KEY, balance INT NOT NULL)");
            statement.execute("DELETE FROM account");
            statement.execute("INSERT INTO account VALUES (1, 100)");
        }
    }

    static Object balance(DataSource database) throws SQLException {
        try (Connection connection = database.getConnection();
             ResultSet rows = connection.createStatement().executeQuery("SELECT balance FROM account")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /** Reads the balance and writes back the balance plus {@code amount}: the lost update. */
    static Transaction deposit(int amount) {
        return connection -> {
            int balance;
            try (ResultSet rows = connection.createStatement().executeQuery("SELECT balance FROM account")) {
                rows.next();
                balance = rows.getInt(1) + amount;
            }
            try (PreparedStatement update = connection.prepareStatement("UPDATE account SET balance = ?")) {
                update.setInt(1, balance);
                update.executeUpdate();
            }
            return balance;
        };
    }

    /** Adds {@code amount} in one statement, which no order can lose. */
    static Transaction increment(int amount) {
        return connection -> connection.createStatement()
                .executeUpdate("UPDATE account SET balance = balance + " + amount);
    }

    private static Events run(Class<?> sample) {
        return EngineTestKit.engine("junit-jupiter").selectors(selectClass(sample)).execute().testEvents();
    }

    private static TestExecutionResult resultOf(Class<?> sample) {
        Events tests = run(sample);
        assertThat(tests.finished().count()).isEqualTo(1);
        return tests.finished().stream().findFirst().orElseThrow().getRequiredPayload(TestExecutionResult.class);
    }

    private static Throwable failureOf(Class<?> sample) {
        TestExecutionResult result = resultOf(sample);
        assertThat(result.getStatus()).isEqualTo(TestExecutionResult.Status.FAILED);
        return result.getThrowable().orElseThrow();
    }

    private static void assertPasses(Class<?> sample) {
        TestExecutionResult result = resultOf(sample);
        assertThat(result.getStatus()).as(() -> result.getThrowable().map(Throwable::toString).orElse(""))
                .isEqualTo(TestExecutionResult.Status.SUCCESSFUL);
    }

    @ExtendWith(H2Extension.class)
    static class LostUpdate {
        @RaceTest
        void depositsAreNeverLost(Race race, DataSource database) throws SQLException {
            race.on(database).startingFrom(RaceExtensionTest::oneAccount).observing(RaceExtensionTest::balance)
                    .run(deposit(30), deposit(50));
        }
    }

    @Test
    void aRaceFailsTheTestWithTheCounterexample() {
        assertThat(failureOf(LostUpdate.class)).isInstanceOf(AssertionError.class).message()
                .contains("orders end in a way no serial order does. The one with the fewest preemptions (1)")
                .contains("transaction 1")
                .contains("1 then 2: transaction 1 returned 130, transaction 2 returned 180; observed 180");
    }

    @ExtendWith(H2Extension.class)
    static class Increments {
        @RaceTest
        void incrementsAreNeverLost(Race race, DataSource database) throws SQLException {
            race.on(database).startingFrom(RaceExtensionTest::oneAccount).observing(RaceExtensionTest::balance)
                    .run(increment(30), increment(50));
        }
    }

    @Test
    void codeWithoutARacePasses() {
        assertPasses(Increments.class);
    }

    @Test
    void theReportIsPublishedEitherWay() {
        List<ReportEntry> entries = run(Increments.class).reportingEntryPublished().stream()
                .map(event -> event.getRequiredPayload(ReportEntry.class)).toList();

        assertThat(entries).singleElement().satisfies(entry -> assertThat(entry.getKeyValuePairs().get("txrace"))
                .startsWith("2 transactions, ").endsWith("Every order ended the way some serial order does.\n"));
    }

    @ExtendWith(H2Extension.class)
    static class SerialOrdersOnly {
        @RaceTest(preemptions = 0)
        void depositsRunOneAfterTheOther(Race race, DataSource database) throws SQLException {
            Verdict verdict = race.on(database).startingFrom(RaceExtensionTest::oneAccount)
                    .observing(RaceExtensionTest::balance).run(deposit(30), deposit(50));

            assertThat(verdict.explored().runs()).hasSize(2);
        }
    }

    @Test
    void theBoundComesFromTheAnnotation() {
        // With no preemptions only the two serial orders run, and both are fine.
        assertPasses(SerialOrdersOnly.class);
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @RaceTest(preemptions = 1)
    @interface QuickRaceTest {
    }

    @ExtendWith(H2Extension.class)
    static class ComposedAnnotation {
        @QuickRaceTest
        void depositsAreNeverLost(Race race, DataSource database) throws SQLException {
            race.on(database).startingFrom(RaceExtensionTest::oneAccount).observing(RaceExtensionTest::balance)
                    .run(deposit(30), deposit(50));
        }
    }

    @Test
    void raceTestCanBeComposedIntoAnAnnotationOfYourOwn() {
        assertThat(failureOf(ComposedAnnotation.class))
                .hasMessageContaining("The one with the fewest preemptions (1)");
    }

    @ExtendWith(H2Extension.class)
    static class KnownRace {
        @RaceTest(expect = RaceTest.Expect.RACE)
        void depositsCanStillBeLost(Race race, DataSource database) throws SQLException {
            race.on(database).startingFrom(RaceExtensionTest::oneAccount).observing(RaceExtensionTest::balance)
                    .run(deposit(30), deposit(50));
        }
    }

    @ExtendWith(H2Extension.class)
    static class FixedRace {
        @RaceTest(expect = RaceTest.Expect.RACE)
        void depositsCanStillBeLost(Race race, DataSource database) throws SQLException {
            race.on(database).startingFrom(RaceExtensionTest::oneAccount).observing(RaceExtensionTest::balance)
                    .run(increment(30), increment(50));
        }
    }

    @Test
    void aKnownRacePassesWhileItIsThereAndFailsOnceItIsFixed() {
        assertPasses(KnownRace.class);
        assertThat(failureOf(FixedRace.class)).isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("expected some order to end in a way no serial order does, but none did")
                .hasMessageContaining("Every order ended the way some serial order does.");
    }

    @ExtendWith(H2Extension.class)
    static class Invariant {
        @RaceTest
        void theBalanceNeverDrops(Race race, DataSource database) throws SQLException {
            race.on(database).startingFrom(RaceExtensionTest::oneAccount).observing(RaceExtensionTest::balance)
                    .judgingBy(ending -> (int) ending.state() >= 100)
                    .run(deposit(30), deposit(50));
        }
    }

    @Test
    void anInvariantCanStandInForTheSerialOrders() {
        // Deposits lose money, but never below what was there, which is all this asks.
        assertPasses(Invariant.class);
    }

    /** Adds {@code amount}, and returns a token no other run will ever return. */
    static Transaction incrementWithToken(int amount) {
        return connection -> {
            increment(amount).run(connection);
            return UUID.randomUUID();
        };
    }

    @ExtendWith(H2Extension.class)
    static class Tokens {
        @RaceTest
        void incrementsAreNeverLost(Race race, DataSource database) throws SQLException {
            race.on(database).startingFrom(RaceExtensionTest::oneAccount).observing(RaceExtensionTest::balance)
                    .ignoringReturnValues().withTimeout(Duration.ofSeconds(10))
                    .run(incrementWithToken(30), incrementWithToken(50));
        }
    }

    @ExtendWith(H2Extension.class)
    static class TokensCompared {
        @RaceTest
        void incrementsAreNeverLost(Race race, DataSource database) throws SQLException {
            race.on(database).startingFrom(RaceExtensionTest::oneAccount).observing(RaceExtensionTest::balance)
                    .run(incrementWithToken(30), incrementWithToken(50));
        }
    }

    @Test
    void returnValuesThatChangeEveryRunCanBeIgnored() {
        assertPasses(Tokens.class);
        assertThat(failureOf(TokensCompared.class)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("use ignoringReturnValues()");
    }

    static class NeverRuns {
        @RaceTest
        void forgetsToRun(Race race) {
        }
    }

    static class NoRace {
        @RaceTest
        void takesNoRace() {
        }
    }

    @Test
    void aRaceTestThatNeverRunsItsRaceFails() {
        assertThat(failureOf(NeverRuns.class)).isInstanceOf(AssertionError.class)
                .hasMessage("forgetsToRun never ran its race: take a Race parameter and call run(...) with the"
                        + " transactions");
        assertThat(failureOf(NoRace.class)).hasMessageStartingWith("takesNoRace never ran its race");
    }

    @ExtendWith(H2Extension.class)
    static class OwnFailure {
        @RaceTest
        void failsForItsOwnReason(Race race, DataSource database) throws SQLException {
            race.on(database).startingFrom(RaceExtensionTest::oneAccount).observing(RaceExtensionTest::balance)
                    .run(deposit(30), deposit(50));
            throw new AssertionError("my own assertion");
        }
    }

    @Test
    void theTestsOwnFailureIsTheOneReported() {
        assertThat(failureOf(OwnFailure.class)).hasMessage("my own assertion");
    }

    @ExtendWith(RaceExtension.class)
    static class PlainTest {
        @Test
        void wantsARace(Race race) {
        }
    }

    @ExtendWith(RaceExtension.class)
    static class OrdinaryTest {
        @Test
        void takesNoRace() {
        }
    }

    @Test
    void anOrdinaryTestNextToTheExtensionIsLeftAlone() {
        assertPasses(OrdinaryTest.class);
    }

    static class TwoRaces {
        @RaceTest
        void wantsTwo(Race first, Race second) {
        }
    }

    @Test
    void onlyARaceTestGetsARace() {
        assertThat(failureOf(PlainTest.class)).isInstanceOf(ParameterResolutionException.class)
                .hasMessageContaining("only handed to a method annotated @RaceTest");
        assertThat(failureOf(TwoRaces.class)).isInstanceOf(ParameterResolutionException.class)
                .hasMessageContaining("a race test takes one Race");
    }

    @ExtendWith(H2Extension.class)
    static class RunsTwice {
        @RaceTest
        void runsTwice(Race race, DataSource database) throws SQLException {
            race.on(database).startingFrom(RaceExtensionTest::oneAccount).run(increment(30), increment(50));
            race.run(increment(30), increment(50));
        }
    }

    static class NoDatabase {
        @RaceTest
        void forgetsTheDatabase(Race race) throws SQLException {
            race.startingFrom(RaceExtensionTest::oneAccount).run(increment(30), increment(50));
        }
    }

    @ExtendWith(H2Extension.class)
    static class NoStartingState {
        @RaceTest
        void forgetsTheStartingState(Race race, DataSource database) throws SQLException {
            race.on(database).run(increment(30), increment(50));
        }
    }

    @ExtendWith(H2Extension.class)
    static class OneTransaction {
        @RaceTest
        void racesNobody(Race race, DataSource database) throws SQLException {
            race.on(database).startingFrom(RaceExtensionTest::oneAccount).run(increment(30));
        }
    }

    @Test
    void aRaceThatCannotRunSaysWhatIsMissing() {
        assertThat(failureOf(RunsTwice.class)).isInstanceOf(IllegalStateException.class)
                .hasMessage("this race has already run: a race test runs one race");
        assertThat(failureOf(NoDatabase.class)).isInstanceOf(IllegalStateException.class)
                .hasMessage("say which database the race runs on, with on(dataSource)");
        assertThat(failureOf(NoStartingState.class)).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("say what every run starts from, with startingFrom(setup)");
        assertThat(failureOf(OneTransaction.class)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a race needs at least two transactions, got 1");
    }
}
