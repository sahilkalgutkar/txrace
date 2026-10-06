package com.sahilkalgutkar.txrace.schedule;

import java.util.List;
import java.util.Set;

/** Picks which transaction takes the next step, for a run that decides as it goes rather than following a schedule. */
@FunctionalInterface
interface Chooser {

    /**
     * {@code taken} holds the transactions that have taken steps so far, in order. {@code ready}
     * holds those parked at the gate now, never empty, and the answer has to be one of them.
     */
    int next(List<Integer> taken, Set<Integer> ready);
}
