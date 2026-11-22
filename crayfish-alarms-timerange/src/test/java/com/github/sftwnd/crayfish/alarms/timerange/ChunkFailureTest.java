package com.github.sftwnd.crayfish.alarms.timerange;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An exception of the comparator, or of {@code hashCode} and {@code equals} without one, leaves the chunk as it was: an
 * addition that fails adds nothing, an extraction that fails takes nothing out, and later extractions fire every
 * element added once, in order. Every script runs once for each of its comparisons, failing on that one.
 */
class ChunkFailureTest {

    // the elements of a moment: the comparator, or the hash code, is called for the elements of a moment only
    private static final int PER_MOMENT = 10;

    @Test
    void coldChunkTest() {
        // the arrays fill again and again while the elements are added, then the chunk fires in two steps
        List<Operation> script = new ArrayList<>(additions(0, 300, 1));
        script.add(Operation.fire(moment(150)));
        script.add(Operation.fire(Long.MAX_VALUE));
        check(script);
    }

    @Test
    void hotChunkTest() {
        // a hot chunk: its heap is merged into the sorted elements as it grows, the extractions take the due elements
        // of both, and some of the elements are due already when they are added
        List<Operation> script = new ArrayList<>(additions(0, 60, 2));
        script.add(Operation.fire(moment(20)));
        List<Operation> late = additions(10, 400, 3);
        for (int i = 0; i < late.size(); i++) {
            script.add(late.get(i));
            if (i % 50 == 49) {
                script.add(Operation.fire(moment(20 + i / 2)));
            }
        }
        script.add(Operation.fire(Long.MAX_VALUE));
        check(script);
    }

    @Test
    void wholeChunkTest() {
        // a hot chunk with a large heap fires as a whole
        List<Operation> script = new ArrayList<>(additions(0, 20, 4));
        script.add(Operation.fire(moment(0)));
        script.addAll(additions(0, 200, 5));
        script.add(Operation.fire(Long.MAX_VALUE));
        check(script);
    }

    private static void check(List<Operation> script) {
        for (boolean withComparator : new boolean[] {true, false}) {
            Calls calls = new Calls();
            int total = run(script, withComparator, calls).comparisons;
            assertTrue(total > 0, "The script has to compare elements");
            int failures = 0;
            for (int failAt = 1; failAt <= total; failAt++) {
                Calls failing = new Calls();
                failing.failAt = failAt;
                Result result = run(script, withComparator, failing);
                failures += result.failures;
                String context = (withComparator ? "comparator" : "hash code") + ", failure on comparison " + failAt;
                List<Integer> expected = new ArrayList<>(result.expected);
                List<Integer> fired = new ArrayList<>(result.fired);
                expected.sort(null);
                fired.sort(null);
                assertEquals(expected, fired, () -> context + ": every element added has to fire once");
            }
            assertEquals(total, failures, "Every run of the script has to fail once");
        }
    }

    // the script with one failure at most; the chunk then fires what is left
    private static Result run(List<Operation> script, boolean withComparator, Calls calls) {
        Chunk<Item> chunk = new Chunk<>(withComparator ? calls.comparator() : null);
        Result result = new Result();
        for (Operation operation : script) {
            List<Item> fired = new ArrayList<>();
            try {
                if (operation.fire) {
                    chunk.fire(operation.limit, fired::add);
                } else {
                    chunk.add(moment(operation.id), new Item(operation.id, calls));
                    result.added(operation.id);
                }
            } catch (IllegalStateException failure) {
                result.failures++;
                assertTrue(fired.isEmpty(), "An extraction that fails has to fire nothing");
            }
            result.take(fired);
        }
        calls.failAt = 0;
        List<Item> rest = new ArrayList<>();
        chunk.fire(Long.MAX_VALUE, rest::add);
        result.take(rest);
        assertTrue(chunk.isEmpty(), "A chunk that has fired every element has to be empty");
        result.comparisons = calls.calls;
        return result;
    }

    // the elements of the ids [from, to) and a duplicate of every seventh one, in a random order
    private static List<Operation> additions(int from, int to, long seed) {
        List<Operation> additions = new ArrayList<>();
        for (int id = from; id < to; id++) {
            additions.add(Operation.add(id));
            if (id % 7 == 0) {
                additions.add(Operation.add(id));
            }
        }
        Collections.shuffle(additions, new Random(seed));
        return additions;
    }

    private static long moment(int id) {
        return (long) (id / PER_MOMENT) * 1_000L;
    }

    private static final class Operation {
        private final boolean fire;
        private final int id;
        private final long limit;

        private Operation(boolean fire, int id, long limit) {
            this.fire = fire;
            this.id = id;
            this.limit = limit;
        }

        private static Operation add(int id) {
            return new Operation(false, id, 0L);
        }

        private static Operation fire(long limit) {
            return new Operation(true, 0, limit);
        }
    }

    private static final class Result {
        // the elements waiting in the chunk: an element equal to one of them is a duplicate
        private final Set<Integer> waiting = new HashSet<>();
        private final List<Integer> expected = new ArrayList<>();
        private final List<Integer> fired = new ArrayList<>();
        private int failures;
        private int comparisons;

        private void added(int id) {
            if (this.waiting.add(id)) {
                this.expected.add(id);
            }
        }

        // an extraction fires its elements in the order of their moments and ids, and each of them once
        private void take(List<Item> elements) {
            for (int i = 1; i < elements.size(); i++) {
                assertTrue(elements.get(i - 1).id < elements.get(i).id, () -> "An extraction has to fire in order: " + elements);
            }
            elements.forEach(item -> {
                this.fired.add(item.id);
                this.waiting.remove(item.id);
            });
        }
    }

    // the comparisons of the elements, the hash codes and equals without a comparator, and the one that fails
    private static final class Calls {
        private int calls;
        private int failAt;

        private void call() {
            if (++this.calls == this.failAt) {
                throw new IllegalStateException("A failure of comparison " + this.calls);
            }
        }

        private Comparator<Item> comparator() {
            return (first, second) -> {
                call();
                return Integer.compare(first.id, second.id);
            };
        }
    }

    private static final class Item {
        private final int id;
        private final Calls calls;

        private Item(int id, Calls calls) {
            this.id = id;
            this.calls = calls;
        }

        @Override
        public int hashCode() {
            this.calls.call();
            return this.id;
        }

        @Override
        public boolean equals(Object other) {
            this.calls.call();
            return other instanceof Item && ((Item) other).id == this.id;
        }

        @Override
        public String toString() {
            return String.valueOf(this.id);
        }
    }
}
