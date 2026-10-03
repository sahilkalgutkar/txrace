package com.sahilkalgutkar.txrace.schedule;

import java.util.ArrayList;
import java.util.List;

/** Every schedule for transactions with the given numbers of steps. The explorer will do this properly. */
final class Interleavings {

    private Interleavings() {
    }

    static List<Schedule> of(int... steps) {
        List<Schedule> out = new ArrayList<>();
        extend(steps.clone(), new ArrayList<>(), out);
        return out;
    }

    private static void extend(int[] left, List<Integer> prefix, List<Schedule> out) {
        boolean any = false;
        for (int i = 0; i < left.length; i++) {
            if (left[i] > 0) {
                any = true;
                left[i]--;
                prefix.add(i + 1);
                extend(left, prefix, out);
                prefix.removeLast();
                left[i]++;
            }
        }
        if (!any) {
            out.add(new Schedule(prefix));
        }
    }
}
