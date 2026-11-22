package com.github.sftwnd.crayfish.alarms.timerange.test;

import com.github.sftwnd.crayfish.alarms.timerange.ITimeRange;
import com.github.sftwnd.crayfish.alarms.timerange.ITimeRangeFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An extraction that fails on an exception of the comparator or of the reducer takes nothing out of the range, also when
 * it fires several chunks: the next extractions fire every element once
 */
class TimeRangeFailureTest {

    private static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");
    // three chunks of a second, 40 elements in each, 10 of a moment
    private static final int ELEMENTS = 120;
    private static final Instant PARTIAL = T0.plusMillis(2_400);
    private static final Instant END = T0.plusSeconds(10);

    @Test
    void comparatorFailureTest() {
        int total = extractionCalls(true);
        assertTrue(total > 0, "The extraction has to compare elements");
        for (int failAt = 1; failAt <= total; failAt++) {
            check(true, failAt);
        }
    }

    @Test
    void reducerFailureTest() {
        int total = extractionCalls(false);
        assertEquals(100, total, "The extraction has to reduce the elements up to its moment");
        for (int failAt = 1; failAt <= total; failAt++) {
            check(false, failAt);
        }
    }

    // the calls of the comparator, or of the reducer, of the first extraction
    private static int extractionCalls(boolean comparator) {
        Calls calls = new Calls();
        ITimeRange<Item, Integer> range = range(calls);
        range.addElements(items());
        calls.reset();
        range.extractFiredElements(PARTIAL);
        return comparator ? calls.comparisons : calls.reductions;
    }

    private static void check(boolean comparator, int failAt) {
        Calls calls = new Calls();
        ITimeRange<Item, Integer> range = range(calls);
        range.addElements(items());
        calls.reset();
        if (comparator) {
            calls.comparisonFailure = failAt;
        } else {
            calls.reductionFailure = failAt;
        }
        String context = (comparator ? "comparison " : "reduction ") + failAt;
        assertThrows(IllegalStateException.class, () -> range.extractFiredElements(PARTIAL), () -> context + ": the extraction has to fail");
        List<Integer> partial = new ArrayList<>(range.extractFiredElements(PARTIAL));
        assertEquals(ids(0, 100), partial, () -> context + ": the extraction after a failure has to fire every due element once, in order");
        List<Integer> rest = new ArrayList<>(range.extractFiredElements(END));
        assertEquals(ids(100, ELEMENTS), rest, () -> context + ": the range has to keep the elements that are not due");
        assertTrue(range.isComplete(END.plusSeconds(1)), () -> context + ": the range has to be empty");
    }

    private static ITimeRange<Item, Integer> range(Calls calls) {
        ITimeRangeFactory<Item, Integer> factory = ITimeRangeFactory.create(
                Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ZERO,
                item -> item,
                item -> item.tick,
                item -> calls.reduce(item.id),
                (first, second) -> calls.compare(first.id, second.id));
        return factory.timeRange(T0);
    }

    // the ids in a random order; the moment of an id: 100 ms apart for every ten of them
    private static Collection<Item> items() {
        List<Item> items = IntStream.range(0, ELEMENTS)
                .mapToObj(id -> new Item(T0.plusMillis(id / 10 * 250L), id))
                .collect(Collectors.toList());
        Collections.shuffle(items, new Random(7));
        return items;
    }

    private static List<Integer> ids(int from, int to) {
        return IntStream.range(from, to).boxed().collect(Collectors.toList());
    }

    private static final class Calls {
        private int comparisons;
        private int reductions;
        private int comparisonFailure;
        private int reductionFailure;

        private void reset() {
            this.comparisons = 0;
            this.reductions = 0;
        }

        private int compare(int first, int second) {
            if (++this.comparisons == this.comparisonFailure) {
                throw new IllegalStateException("A failure of comparison " + this.comparisons);
            }
            return Integer.compare(first, second);
        }

        private int reduce(int id) {
            if (++this.reductions == this.reductionFailure) {
                throw new IllegalStateException("A failure of reduction " + this.reductions);
            }
            return id;
        }
    }

    private static final class Item {
        private final Instant tick;
        private final int id;

        private Item(Instant tick, int id) {
            this.tick = tick;
            this.id = id;
        }
    }
}
