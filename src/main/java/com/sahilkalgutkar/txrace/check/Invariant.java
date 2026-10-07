package com.sahilkalgutkar.txrace.check;

/**
 * A rule every ending must keep, for when comparing with the serial orders is too strict, such as
 * transactions that return something different every time they run.
 */
@FunctionalInterface
public interface Invariant {

    boolean holds(Ending ending);
}
