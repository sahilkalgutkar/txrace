package com.sahilkalgutkar.txrace.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A test that races transactions against each other. The test method takes a {@link Race},
 * says where every run starts and which transactions to run, and the extension judges every order
 * they can run in once the method returns. With {@link Expect#SERIAL}, the default, the test fails
 * if any order ends in a way no serial order does, and the failure message is the counterexample.
 *
 * <pre>{@code
 * @RaceTest(preemptions = 2)
 * void twoPaymentsNeverOverdraw(Race race, DataSource database) throws SQLException {
 *     race.on(database)
 *             .startingFrom(Wallets::oneWithThirty)
 *             .observing(Wallets::balances)
 *             .run(pay(20), pay(20));
 * }
 * }</pre>
 */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Test
@ExtendWith(RaceExtension.class)
public @interface RaceTest {

    /**
     * The most preemptions an order may have. Each one is a switch away from a transaction that
     * could have gone on. Two is enough for most races, and the count of orders grows quickly past it.
     */
    int preemptions() default 2;

    /** What the orders have to show for the test to pass. */
    Expect expect() default Expect.SERIAL;

    /** What a race test expects of the orders it runs. */
    enum Expect {

        /**
         * Every order runs, and each ends the way some serial order of the transactions does, or
         * keeps the invariant if the race is judged by one.
         */
        SERIAL,

        /**
         * Some order ends in a way no serial order does. For a race that is known and not fixed
         * yet: the test keeps passing while the race is there, and fails once it is gone.
         */
        RACE
    }
}
