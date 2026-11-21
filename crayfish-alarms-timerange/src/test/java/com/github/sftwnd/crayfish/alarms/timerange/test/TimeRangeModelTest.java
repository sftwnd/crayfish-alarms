package com.github.sftwnd.crayfish.alarms.timerange.test;

import com.github.sftwnd.crayfish.alarms.timerange.ITimeRange;
import com.github.sftwnd.crayfish.alarms.timerange.ITimeRangeFactory;
import com.github.sftwnd.crayfish.common.expectation.Expected;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Random operations on a time range and on a reference model of its contract: the same rejected elements, the same
 * fired elements in the same order (the very objects, not only equal ones), the same durations and completions.
 */
class TimeRangeModelTest {

    private static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");
    private static final Duration COMPLETE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration YEAR = ChronoUnit.YEARS.getDuration();

    @Test
    void shortRangeWithoutComparatorTest() {
        for (Duration interval : intervals(Duration.ofSeconds(10))) {
            check(Duration.ofSeconds(10), interval, null, 1L);
        }
    }

    @Test
    void shortRangeWithComparatorTest() {
        for (Duration interval : intervals(Duration.ofSeconds(10))) {
            check(Duration.ofSeconds(10), interval, Comparator.comparingInt(Item::getId), 2L);
        }
    }

    @Test
    void shortRangeWithCoarseComparatorTest() {
        // distinct elements of a tick that the comparator takes for duplicates: the first one added stays
        for (Duration interval : intervals(Duration.ofSeconds(10))) {
            check(Duration.ofSeconds(10), interval, Comparator.comparingInt(item -> item.getId() / 3), 3L);
        }
    }

    @Test
    void negativeDurationTest() {
        check(Duration.ofSeconds(-10), Duration.ofSeconds(1), null, 4L);
        check(Duration.ofSeconds(-10), Duration.ofMillis(125), Comparator.comparingInt(Item::getId), 5L);
    }

    @Test
    void longRangeTest() {
        // longer than the 292 years of a long of nanoseconds: chunks of a century at most, of whole seconds
        for (Duration duration : new Duration[] { YEAR.multipliedBy(300), YEAR.multipliedBy(1000) }) {
            for (Duration interval : new Duration[] { Duration.ZERO, Duration.ofMillis(1500), Duration.ofDays(1) }) {
                check(duration, interval, null, 6L);
                check(duration, interval, Comparator.comparingInt(Item::getId), 7L);
            }
        }
    }

    private static Duration[] intervals(Duration duration) {
        return new Duration[] { Duration.ZERO, duration, Duration.ofSeconds(1), Duration.ofMillis(125), Duration.ofMillis(7), Duration.ofMillis(1) };
    }

    private static void check(Duration duration, Duration interval, Comparator<Item> comparator, long seed) {
        Random random = new Random(seed * 31 + duration.hashCode() * 17L + interval.hashCode());
        // the range of an odd seed starts within a second: the nanoseconds of a tick can be less than the ones of the start
        Instant time = seed % 2 == 1 ? T0.plusNanos(987_654_321) : T0;
        ITimeRange<Item, Item> range = ITimeRangeFactory.expected(duration, interval, COMPLETE_TIMEOUT, comparator).timeRange(time);
        Model model = new Model(range.getStartInstant(), range.getLastInstant(), comparator);
        Scenario scenario = new Scenario(random, range.getStartInstant(), range.getLastInstant());
        String name = "duration " + duration + ", interval " + interval + ", comparator " + (comparator != null) + ", seed " + seed;
        for (int op = 0; op < 6000; op++) {
            String step = name + ", operation " + op;
            int kind = random.nextInt(100);
            if (kind < 70) {
                List<Item> batch = scenario.batch();
                assertEquals(uids(model.addElements(batch)), uids(range.addElements(batch)), () -> step + ": rejected elements");
            } else if (kind < 95) {
                Instant now = scenario.now();
                assertEquals(uids(model.extractFiredElements(now)), uids(range.extractFiredElements(now)), () -> step + ": fired elements at " + now);
            } else if (kind < 99) {
                Instant now = scenario.now();
                assertEquals(model.duration(now), range.duration(now), () -> step + ": duration at " + now);
                assertEquals(model.isComplete(now), range.isComplete(now), () -> step + ": completion at " + now);
            } else {
                model.clean();
                range.clean();
            }
        }
        Instant end = range.getLastInstant().plus(COMPLETE_TIMEOUT);
        assertEquals(uids(model.extractFiredElements(end)), uids(range.extractFiredElements(end)), () -> name + ": the rest of the elements");
        assertEquals(model.isComplete(end), range.isComplete(end), () -> name + ": completion at the end");
    }

    private static List<Integer> uids(Collection<Item> items) {
        return items.stream().map(item -> item == null ? -1 : item.uid).collect(Collectors.toList());
    }

    /**
     * The elements and the moments of the operations: ticks repeat to make duplicates and elements of the same tick,
     * the moments of the extractions go forward in steps and sometimes jump
     */
    private static final class Scenario {
        private final Random random;
        private final Instant start;
        private final long span;
        private final boolean nanos;
        private final List<Instant> ticks = new ArrayList<>();
        private Instant cursor;
        private int uid;

        private Scenario(Random random, Instant start, Instant last) {
            this.random = random;
            this.start = start;
            Duration duration = Duration.between(start, last);
            // a range longer than a long of nanoseconds takes its ticks in seconds
            this.nanos = duration.compareTo(Duration.ofSeconds(9_000_000_000L)) < 0;
            this.span = this.nanos ? duration.toNanos() : duration.getSeconds();
            this.cursor = start.minus(duration.dividedBy(20));
            for (int i = 0; i < 64; i++) {
                this.ticks.add(tick());
            }
        }

        // a moment from a twentieth of the range before it to a twentieth after it
        private Instant tick() {
            long value = (long) ((random.nextDouble() * 1.1 - 0.05) * this.span);
            return this.nanos ? this.start.plusNanos(value) : this.start.plusSeconds(value).plusNanos(random.nextInt(1_000_000_000));
        }

        private List<Item> batch() {
            int size = random.nextInt(10) == 0 ? 200 + random.nextInt(800) : 1 + random.nextInt(20);
            // a batch of a tick near the cursor: elements of the chunk of the last extraction
            boolean nearCursor = random.nextInt(4) == 0;
            List<Item> batch = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                if (random.nextInt(50) == 0) {
                    batch.add(null);
                    continue;
                }
                Instant tick;
                int choice = random.nextInt(10);
                if (choice < 4) {
                    tick = this.ticks.get(random.nextInt(this.ticks.size()));
                } else if (nearCursor || choice < 6) {
                    tick = this.nanos ? this.cursor.plusNanos((long) (random.nextGaussian() * this.span / 200)) : this.cursor.plusSeconds((long) (random.nextGaussian() * this.span / 200));
                } else {
                    tick = tick();
                }
                batch.add(new Item(random.nextInt(12), tick, ++this.uid));
            }
            return batch;
        }

        private Instant now() {
            int choice = random.nextInt(20);
            if (choice == 0) {
                return tick();
            } else if (choice == 1) {
                return this.ticks.get(random.nextInt(this.ticks.size()));
            }
            long step = (long) (random.nextDouble() * this.span / 300);
            this.cursor = this.nanos ? this.cursor.plusNanos(step) : this.cursor.plusSeconds(step);
            return this.cursor;
        }
    }

    /**
     * The contract of a time range: an element of a tick that is a duplicate of a waiting element of the tick is not added
     * (by the comparator, or without one by the hash code and equals); the fired elements go in the order of their ticks,
     * then of the comparator or the hash code, then of their addition
     */
    private static final class Model {
        private final Instant start;
        private final Instant last;
        private final Instant complete;
        private final Comparator<Item> order;
        private final Comparator<Item> comparator;
        private final TreeMap<Instant, List<Item>> pending = new TreeMap<>();

        private Model(Instant start, Instant last, Comparator<Item> comparator) {
            this.start = start;
            this.last = last;
            this.complete = last.plus(COMPLETE_TIMEOUT);
            this.comparator = comparator;
            this.order = comparator == null ? Comparator.comparingInt(Item::hashCode) : comparator;
        }

        private List<Item> addElements(List<Item> batch) {
            List<Item> rejected = new ArrayList<>();
            for (Item item : batch) {
                if (item == null) {
                    continue;
                }
                if (item.getTick().isBefore(this.start) || !item.getTick().isBefore(this.last)) {
                    rejected.add(item);
                    continue;
                }
                List<Item> tick = this.pending.computeIfAbsent(item.getTick(), key -> new ArrayList<>());
                if (tick.stream().noneMatch(element -> duplicate(element, item))) {
                    tick.add(item);
                }
            }
            return rejected;
        }

        private boolean duplicate(Item element, Item item) {
            return this.comparator == null
                    ? element.hashCode() == item.hashCode() && element.equals(item)
                    : this.comparator.compare(element, item) == 0;
        }

        private List<Item> extractFiredElements(Instant now) {
            List<Item> fired = new ArrayList<>();
            Iterator<Map.Entry<Instant, List<Item>>> ticks = this.pending.headMap(now, true).entrySet().iterator();
            while (ticks.hasNext()) {
                List<Item> tick = new ArrayList<>(ticks.next().getValue());
                // a stable sort: the elements of the same order follow in the order of their addition
                tick.sort(this.order);
                fired.addAll(tick);
                ticks.remove();
            }
            return fired;
        }

        private Duration duration(Instant now) {
            Instant nearest = this.pending.isEmpty() ? null : this.pending.firstKey();
            if (now.isBefore(this.start)) {
                return between(now, nearest == null ? this.start : nearest);
            } else if (nearest == null) {
                return between(now, this.complete);
            } else if (now.isBefore(this.last)) {
                return between(now, nearest);
            }
            return Duration.ZERO;
        }

        private static Duration between(Instant now, Instant instant) {
            return instant.isAfter(now) ? Duration.between(now, instant) : Duration.ZERO;
        }

        private boolean isComplete(Instant now) {
            return this.pending.isEmpty() && !now.isBefore(this.complete);
        }

        private void clean() {
            this.pending.clear();
        }
    }

    /**
     * An element equal to another one of the same id and tick, with the hash code of a few values: elements of a tick
     * that have the same hash code and are not equal
     */
    static final class Item implements Expected<Instant> {
        private final int id;
        private final Instant tick;
        private final int uid;

        Item(int id, Instant tick, int uid) {
            this.id = id;
            this.tick = tick;
            this.uid = uid;
        }

        int getId() {
            return this.id;
        }

        @Override
        public Instant getTick() {
            return this.tick;
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof Item && ((Item) object).id == this.id && ((Item) object).tick.equals(this.tick);
        }

        @Override
        public int hashCode() {
            return this.id % 4;
        }

        @Override
        public String toString() {
            return "Item{" + this.id + ", " + this.tick + ", " + this.uid + '}';
        }
    }
}
