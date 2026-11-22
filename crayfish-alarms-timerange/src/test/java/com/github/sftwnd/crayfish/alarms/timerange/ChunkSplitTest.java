package com.github.sftwnd.crayfish.alarms.timerange;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A chunk with many elements is split into parts of its interval: it fires every element once, in the order of the
 * moments and of the comparator or the hash codes, drops the duplicates, and gives its first moment, whether it is split
 * cold, hot or into parts that are split again
 */
class ChunkSplitTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long MILLISECOND = 1_000_000L;
    private static final int ELEMENTS = 100_000;

    @Test
    void coldSplitTest() {
        for (Comparator<Integer> comparator : comparators()) {
            Model model = new Model(comparator, SECOND);
            Random random = new Random(1);
            for (int id = 0; id < ELEMENTS; id++) {
                model.add(id, (long) (random.nextDouble() * SECOND));
            }
            assertTrue(model.chunk.isSplit(), "A cold chunk with many elements has to be split");
            model.fireSteps();
        }
    }

    @Test
    void hotSplitTest() {
        for (Comparator<Integer> comparator : comparators()) {
            Model model = new Model(comparator, SECOND);
            model.add(0, 0L);
            model.fire(0L);
            assertFalse(model.chunk.isSplit(), "A small chunk has to stay whole when it fires");
            // the elements come to the hot chunk while it fires, some of them due already
            Random random = new Random(2);
            long limit = 0L;
            for (int id = 1; id < ELEMENTS; id++) {
                model.add(id, id % 50 == 0 ? (long) (random.nextDouble() * limit) : limit + (long) (random.nextDouble() * (SECOND - limit)));
                if (id % 1_000 == 0) {
                    limit += MILLISECOND;
                    model.fire(limit);
                }
            }
            assertTrue(model.chunk.isSplit(), "A hot chunk with many elements has to be split");
            model.fireSteps();
        }
    }

    @Test
    void narrowSplitTest() {
        // the elements of a millisecond of a chunk of a second: the part of the millisecond is split again and again
        for (Comparator<Integer> comparator : comparators()) {
            Model model = new Model(comparator, SECOND);
            Random random = new Random(3);
            long from = 500 * MILLISECOND;
            for (int id = 0; id < ELEMENTS; id++) {
                model.add(id, from + (long) (random.nextDouble() * MILLISECOND));
            }
            assertTrue(model.chunk.isSplit(), "A chunk with many elements has to be split");
            for (long limit = from; limit < from + MILLISECOND; limit += 10_000L) {
                model.fire(limit);
            }
            model.fire(Long.MAX_VALUE);
            assertTrue(model.chunk.isEmpty(), "A chunk that has fired every element has to be empty");
        }
    }

    @Test
    void sameMomentTest() {
        // every element of one moment: the parts cannot divide them, the part of the moment fires them in order
        for (Comparator<Integer> comparator : comparators()) {
            Model model = new Model(comparator, SECOND);
            for (int id = 0; id < 20_000; id++) {
                model.add(id, 123_456_789L);
            }
            model.fire(123_456_788L);
            model.fire(123_456_789L);
            assertTrue(model.chunk.isEmpty(), "A chunk that has fired every element has to be empty");
        }
    }

    // the elements are ordered by themselves: by their hash codes without a comparator, by the comparator with it
    private static List<Comparator<Integer>> comparators() {
        return Arrays.asList(null, Comparator.<Integer>naturalOrder());
    }

    /**
     * A chunk and the elements that it has to fire: the waiting elements by their moments, in their order
     */
    private static final class Model {
        private final Chunk<Integer> chunk;
        private final long span;
        private final TreeMap<Long, TreeSet<Integer>> waiting = new TreeMap<>();
        private final Map<Integer, Long> moments = new HashMap<>();
        private final Random random = new Random(4);

        private Model(Comparator<Integer> comparator, long span) {
            this.chunk = new Chunk<>(comparator, span);
            this.span = span;
        }

        // an element, and a duplicate of every tenth one
        private void add(int id, long moment) {
            this.chunk.add(moment, id);
            this.waiting.computeIfAbsent(moment, key -> new TreeSet<>()).add(id);
            this.moments.put(id, moment);
            if (id % 10 == 0) {
                this.chunk.add(moment, id);
            }
            assertEquals(this.waiting.firstKey().longValue(), this.chunk.firstMoment(), "A chunk has to give its earliest moment");
        }

        // an extraction fires the waiting elements up to the limit, in their order
        private void fire(long limit) {
            List<Integer> expected = new ArrayList<>();
            this.waiting.headMap(limit, true).values().forEach(expected::addAll);
            this.waiting.headMap(limit, true).clear();
            List<Integer> fired = new ArrayList<>();
            this.chunk.fire(limit, fired::add);
            assertEquals(expected, fired, () -> "A chunk has to fire the elements up to " + limit + " once, in order");
            long first = this.waiting.isEmpty() ? Long.MAX_VALUE : this.waiting.firstKey();
            assertEquals(first, this.chunk.firstMoment(),
                    "A chunk has to give its earliest moment after an extraction");
            assertEquals(this.waiting.isEmpty(), this.chunk.isEmpty(), "A chunk is empty when it has fired every element");
        }

        // extractions of a millisecond for 20 ms, then of a random step up to the end of the chunk, then of the rest
        private void fireSteps() {
            long limit = this.waiting.firstKey();
            for (int step = 0; step < 20; step++) {
                fire(limit);
                limit += MILLISECOND;
            }
            while (limit < this.span) {
                fire(limit);
                limit += (long) (this.random.nextDouble() * 100 * MILLISECOND);
            }
            fire(Long.MAX_VALUE);
            assertTrue(this.chunk.isEmpty(), "A chunk that has fired every element has to be empty");
            assertEquals(0, this.chunk.size(), "A chunk that has fired every element has to keep none");
        }
    }

}
