package com.github.sftwnd.crayfish.alarms.timerange.test;

import com.github.sftwnd.crayfish.alarms.timerange.ITimeRange;
import com.github.sftwnd.crayfish.alarms.timerange.ITimeRangeFactory;
import com.github.sftwnd.crayfish.common.expectation.Expected;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeRangeTest {

    private static final Duration INTERVAL = Duration.ofSeconds(15);
    private static final Duration COMPLETE_TIMEOUT = Duration.ofSeconds(10);
    // the start of the chunks of a second, a minute and ten minutes
    private static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");

    private Instant now;
    private Duration completeTimeout;
    private ITimeRange<ExpectedTest, ExpectedTest> timeRange;
    private ExpectedTest elementA;
    private ExpectedTest elementB;
    private ExpectedTest elementC;
    private Collection<ExpectedTest> elements;

    @Test
    void isNotExpiredTest() {
        assertFalse(timeRange.isExpired(now), "TimeRange hasn't got to be expired on now");
    }

    @Test
    void isExpiredTest() {
        assertTrue(timeRange.isExpired(now.plus(completeTimeout)), "TimeRange hasn't got to be expired on now+completeTimeout");
    }

    @Test
    void isNotExpiredCompleteNowTest() {
        ITimeRangeFactory<Expected<Instant>, Expected<Instant>> timeRangeFactory =
                ITimeRangeFactory.expected(
                        Duration.ofMinutes(-1L),
                        Duration.ofSeconds(15),
                        completeTimeout,
                        Comparator.comparing(Expected::getTick)
                );
        ITimeRange<Expected<Instant>,Expected<Instant>> timeRange =
                timeRangeFactory.timeRange(now.plus(1, ChronoUnit.HOURS));
        assertFalse(timeRange.isExpired(), "TimeRange hasn't got to be expired on Instant.now()");
        assertFalse(timeRange.isComplete(), "TimeRange hasn't got to be expired on Instant.now()");
    }

    @Test
    void isExpiredNowCompleteTest() {
        ITimeRangeFactory<ExpectedTest,ExpectedTest> timeRangeFactory = ITimeRangeFactory.create(
                Duration.ofMinutes(-1L), Duration.ofSeconds(15), completeTimeout,
                ITimeRange.Transformer.identity(), ExpectedTest::getTick, ITimeRange.Transformer.identity(), null);
        ITimeRange<ExpectedTest,ExpectedTest> timeRange = timeRangeFactory.timeRange(now.minus(1,ChronoUnit.HOURS));
        assertTrue(timeRange.isExpired(), "TimeRange has got to be expired on Instant.now()");
        assertTrue(timeRange.isComplete(), "TimeRange has got to be expired on Instant.now()");
    }

    @Test
    void temporalFactoryTest() {
        ITimeRangeFactory<Instant,Instant> timeRangeFactory = ITimeRangeFactory.temporal(
                Duration.ofMinutes(1), Duration.ofSeconds(1), completeTimeout, null);
        ITimeRange<Instant,Instant> timeRange = timeRangeFactory.timeRange(now.minus(15,ChronoUnit.SECONDS));
        timeRange.addElement(timeRange.getStartInstant());
        LockSupport.parkNanos(1);
        Collection<Instant> fired = timeRange.extractFiredElements();
        assertEquals(Collections.singletonList(timeRange.getStartInstant()), fired, "TimeRange has to return added instant");
    }

    @Test
    void emptyCompleteTest() {
        assertFalse(timeRange.isComplete(now), "empty TimeRange has to be incomplete on now");
        assertTrue(timeRange.isComplete(now.plus(completeTimeout)), "empty TimeRange has to be complete on now");
    }

    @Test
    void nonEmptyCompleteTest() {
        addElements();
        assertFalse(timeRange.isComplete(now), "empty TimeRange has to be incomplete on now");
        assertFalse(timeRange.isComplete(now.plus(completeTimeout)), "empty TimeRange has to be complete on now");
    }

    @Test
    void addElementsWithNullTest() {
        Collection<ExpectedTest> elements = new LinkedList<>();
        ExpectedTest rejected = new ExpectedTest(Instant.now().plus(30, ChronoUnit.DAYS));
        elements.add(null);
        elements.add(rejected);
        Collection<?> rejects = timeRange.addElements(elements);
        assertNotNull(rejects, "result for timeRange.addElement(null) has to be not null collection");
        assertEquals(Collections.singletonList(rejected), rejects,"result for timeRange.addElements({ null, element }) has to return one not null element");
    }

    @Test
    void addAndFiredElementsTest() {
        addElements();
        assertEquals(this.elements.stream().sorted().collect(Collectors.toList()),
                this.timeRange.extractFiredElements(now).stream().sorted().collect(Collectors.toList()),
                "TimeRange has to contains added elements");
    }

    @Test
    void addSkipOthersElementsTest() {
        List<ExpectedTest> exclude = Arrays.asList(
                expected(this.now.plus(this.completeTimeout).plus(1, ChronoUnit.MILLIS)),
                expected(this.timeRange.getStartInstant().plus(-1, ChronoUnit.MILLIS))
        );
        assertEquals(exclude, this.timeRange.addElements(exclude), "Elements before and after the range has to be excluded from addElements operation");
        assertEquals(Collections.emptyList(), this.timeRange.extractFiredElements(this.now.plus(this.completeTimeout)), "Range has to be empty after add elements not in the range");
    }

    @Test
    void addFirstFiredInstantElementTest() {
        addElements();
        Set<ExpectedTest> elements = new HashSet<>(this.timeRange.extractFiredElements(elementA.getTick()));
        assertEquals(Collections.singleton(elementA), new HashSet<>(elements), "TimeRange has to return first element");
    }

    @Test
    void addSkipFirstSecondFiredInstantElementTest() {
        addElements();
        this.timeRange.extractFiredElements(elementA.getTick());
        Set<ExpectedTest> elements = new HashSet<>(this.timeRange.extractFiredElements(elementB.getTick()));
        assertEquals(Collections.singleton(elementB), elements, "TimeRange has to return second element");
    }

    @Test
    void addSkipFirstLastFiredInstantElementsTest() {
        addElements();
        this.timeRange.extractFiredElements(elementA.getTick());
        Set<ExpectedTest> elements = new HashSet<>(this.timeRange.extractFiredElements(now));
        assertEquals(new HashSet<>(Arrays.asList(elementB,elementC)), elements, "TimeRange has to return second and third element");
    }

    @Test
    void addDupElementTest() {
        this.timeRange.addElements(Arrays.asList(elementA, elementC));
        this.timeRange.addElements(Arrays.asList(elementA, elementB));
        Set<ExpectedTest> elements = new HashSet<>(this.timeRange.extractFiredElements());
        assertEquals(new HashSet<>(this.elements), elements, "TimeRange has to return second element");
    }

    @Test
    void addSameInstantElementsTest() {
        Instant instant = this.timeRange.getStartInstant().plusMillis(1);
        this.timeRange.addElements(Arrays.asList(expected(instant), expected(instant), expected(instant)));
        assertEquals(3, this.timeRange.extractFiredElements().size(), "TimeRange has to return three elements on the same instant");
    }

    @Test
    void addListWithDupElementTest() {
        this.timeRange.addElements(Arrays.asList(elementA, elementC, elementA, elementB, elementC, elementB));
        Set<ExpectedTest> elements = new HashSet<>(this.timeRange.extractFiredElements());
        assertEquals(new HashSet<>(this.elements), elements, "TimeRange after non unique list add has to return distinct elements");
    }

    @Test
    void durationBeforeStartTest() {
        Instant instant = timeRange.getStartInstant().minusSeconds(1);
        assertEquals(Duration.ofSeconds(1), timeRange.duration(instant), "Duration of future timeRange has to be equals duration to start of timeRange");
    }

    @Test
    void durationOnStartTest() {
        addElements();
        assertEquals(Duration.between(timeRange.getStartInstant(), elementA.getTick()), timeRange.duration(timeRange.getStartInstant()),
                "Duration on startOf timeRange has to be equals duration to first element");
    }

    @Test
    void durationInTheMiddleTest() {
        addElements();
        timeRange.extractFiredElements(elementA.getTick());
        assertEquals(Duration.between(elementA.getTick(), elementB.getTick()), timeRange.duration(elementA.getTick()),
                "Duration on firstElement timeRange has to be equals duration to second element");
    }

    @Test
    void durationAfterLastElementTest() {
        addElements();
        assertEquals(Duration.ZERO, this.timeRange.duration(this.timeRange.getLastInstant()),
                "Duration after last element on timeRange with elements has to be equals ZERO");
    }

    @Test
    void durationNoElementsBeforeLastTimeTest() {
        Instant tick = timeRange.getLastInstant().minusSeconds(1);
        assertEquals(Duration.ofSeconds(1).plus(completeTimeout), this.timeRange.duration(tick),
                "Duration on the end of timeRange has to be equals duration to the end plus completion");
    }

    @Test
    void durationToExpectAfterLastElementTest() {
        Instant tick = timeRange.getLastInstant().minusMillis(200);
        this.timeRange.addElements(Collections.singletonList(expected(tick.plusMillis(100))));
        assertEquals(Duration.ofMillis(100), this.timeRange.duration(tick),
                "Duration on the end of timeRange has to be equals duration to the end plus completion");
    }

    @Test
    void cleanTest() {
        addElements();
        Instant limit = Instant.now().plus(Duration.ofDays(1));
        Collection<ExpectedTest> fired = timeRange.extractFiredElements(limit);
        assertFalse(fired.isEmpty(), "Collection has to be filled");
        timeRange.addElements(fired);
        timeRange.clean();
        assertTrue(timeRange.extractFiredElements(limit).isEmpty(), "Collection has to be empty after clean() call");
    }

    @Test
    void getStartInstantTest() {
        assertEquals(now.minus(1, ChronoUnit.MINUTES), timeRange.getStartInstant(), "TimeRange::getStartInstant - wrong result");
    }

    @Test
    void getLastInstantTest() {
        assertEquals(now, timeRange.getLastInstant(), "TimeRange::getLastInstant - wrong result");
    }

    @Test
    void completeTimeoutTest() {
        Instant expiration = timeRange.getLastInstant().plus(COMPLETE_TIMEOUT);
        assertFalse(timeRange.isExpired(expiration.minusNanos(1)), "TimeRange hasn't got to be expired before the end of its completeTimeout");
        assertTrue(timeRange.isExpired(expiration), "TimeRange has to be expired at the end of its completeTimeout");
    }

    @Test
    void durationZeroTest() {
        Recording<ExpectedTest,ExpectedTest> spy = new Recording<>(this.timeRange);
        Instant firstTick = Instant.now().minusNanos(1);
        spy.duration();
        Instant nextTick = Instant.now().plusNanos(1);
        assertEquals(1, spy.durationArguments.size(), "TimeRange::duration() has to call duration(Instant) once");
        Instant instant = spy.durationArguments.get(0);
        assertTrue(firstTick.isBefore(instant),"TimeRange::duration has to use instant after firstTick");
        assertTrue(nextTick.isAfter(instant),"TimeRange::duration has to use instant before nextTick");
    }

    @Test
    void constructPackableTest() {
        this.now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        this.completeTimeout = COMPLETE_TIMEOUT;
        ITimeRangeFactory<String,String> timeRangeFactory = ITimeRangeFactory.packable(
                Duration.ofMinutes(-1L), Duration.ofSeconds(15),
                completeTimeout, Instant::parse,
                Comparator.comparing(Instant::parse));
        ITimeRange<String,String> timeRange = timeRangeFactory.timeRange(now);
        String strA = now.minusSeconds(2).toString();
        String strB = now.minusSeconds(1).toString();
        String strC = now.minusMillis(2).toString();
        String strD = now.minusMillis(1).toString();
        String strE = now.toString();
        Collection<String> rejected = timeRange.addElements(Arrays.asList(strA, strB, strC, strD, strE));
        assertEquals(Collections.singletonList(strE), new ArrayList<>(rejected), "timeRange.addElements has to reject strD");
        assertEquals( Stream.of(strA,strB,strC).sorted().collect(Collectors.toList()),
                timeRange.extractFiredElements(now.minusMillis(2)).stream().sorted().collect(Collectors.toList()),
                "constructPackable has to return three elements on now");
        assertEquals(Collections.singletonList(strD), timeRange.extractFiredElements(now.plusMillis(1)), "constructPackable has to return one element");
    }

    @Test
    void extractSameHashCodeInstantsTest() {
        ITimeRange<Instant, Instant> range = ITimeRangeFactory.<Instant>temporal(
                Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ZERO, null).timeRange(T0);
        // two instants of one chunk with the same hash code
        Instant first = T0.plusNanos(1);
        Instant second = T0.plusSeconds(51);
        assertEquals(first.hashCode(), second.hashCode(), "The instants of the test have to have the same hash code");
        range.addElements(Arrays.asList(first, second));
        assertEquals(Collections.emptyList(), range.extractFiredElements(T0), "TimeRange has nothing to fire at T0");
        assertEquals(Arrays.asList(first, second), range.extractFiredElements(T0.plusSeconds(120)),
                "TimeRange has to keep the elements of different ticks with the same hash code");
    }

    @Test
    void extractInTheMiddleOfChunkTest() {
        assertEquals(ticks(reminders()), ticks(extractInTheMiddleOfChunk(null)),
                "TimeRange without a comparator has to fire the equal elements of different ticks");
    }

    @Test
    void extractInTheMiddleOfChunkWithComparatorTest() {
        assertEquals(ticks(reminders()), ticks(extractInTheMiddleOfChunk(Comparator.comparing(Reminder::getId))),
                "TimeRange has to compare the elements by the comparator only on the same tick");
    }

    @Test
    void firedOrderTest() {
        ITimeRange<Instant, Instant> range = ITimeRangeFactory.<Instant>temporal(
                Duration.ofMinutes(10), Duration.ofSeconds(1), Duration.ZERO, null).timeRange(T0);
        // an alarm in the middle of every chunk of the first 300 seconds
        List<Instant> alarms = IntStream.range(0, 300).mapToObj(i -> T0.plusMillis(i * 1000L + 500)).collect(Collectors.toList());
        range.addElements(alarms);
        assertEquals(alarms, new ArrayList<>(range.extractFiredElements(T0.plusSeconds(600))),
                "TimeRange has to fire the elements of its chunks in the order of their ticks");
    }

    @Test
    void firedOrderInChunkTest() {
        ITimeRange<Instant, Instant> range = ITimeRangeFactory.<Instant>temporal(
                Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ZERO, null).timeRange(T0);
        // 300 alarms of one chunk, 10 ms apart: the first 200 of them up to T0 + 2 s
        List<Instant> alarms = IntStream.range(0, 300).mapToObj(i -> T0.plusMillis(i * 10L + 1)).collect(Collectors.toList());
        range.addElements(alarms);
        assertEquals(alarms.subList(0, 200), new ArrayList<>(range.extractFiredElements(T0.plusSeconds(2))),
                "TimeRange has to fire the elements of the chunk of the moment in the order of their ticks");
        assertEquals(alarms.subList(200, 300), new ArrayList<>(range.extractFiredElements(T0.plusSeconds(600))),
                "TimeRange has to keep the elements of the chunk of the moment that have not fired");
    }

    @Test
    void sameTickSameHashCodeTest() {
        ITimeRange<String, String> range = ITimeRangeFactory.<String, Instant>packable(
                Duration.ofMinutes(1), Duration.ofSeconds(15), Duration.ZERO, element -> T0.plusSeconds(1), null).timeRange(T0);
        // "AaAa", "AaBB" and "BBAa" have the same hash code; the fourth element equals "AaBB", the second of them
        String equal = String.valueOf(new char[] {'A', 'a', 'B', 'B'});
        assertEquals(1, Arrays.asList("AaAa", "AaBB", "BBAa").stream().mapToInt(String::hashCode).distinct().count(),
                "The strings of the test have to have the same hash code");
        assertEquals(Collections.emptyList(), range.addElements(Arrays.asList("AaAa", "AaBB", equal, "BBAa")), "TimeRange has to accept the elements of its range");
        assertEquals(Arrays.asList("AaAa", "AaBB", "BBAa"), range.extractFiredElements(T0.plusSeconds(2)),
                "TimeRange without a comparator has to keep the distinct elements of a tick with the same hash code and one of the equal ones");
    }

    @Test
    void sameTickComparatorTest() {
        ITimeRange<String, String> range = ITimeRangeFactory.<String, Instant>packable(
                Duration.ofMinutes(1), Duration.ofSeconds(15), Duration.ZERO, element -> T0.plusSeconds(1),
                Comparator.comparing(String::length)).timeRange(T0);
        range.addElements(Arrays.asList("Aa", "BB", "C"));
        assertEquals(Arrays.asList("C", "Aa"), range.extractFiredElements(T0.plusSeconds(2)),
                "TimeRange has to order the elements of a tick and find their duplicates by the comparator");
    }

    @Test
    void expectationOnceTest() {
        AtomicInteger calls = new AtomicInteger();
        ITimeRange<Instant, Instant> range = ITimeRangeFactory.<Instant>create(
                Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ZERO, element -> {
                    calls.incrementAndGet();
                    return element;
                }, null).timeRange(T0);
        List<Instant> alarms = IntStream.range(0, 1000).mapToObj(i -> T0.plusMillis(i + 1L)).collect(Collectors.toList());
        range.addElements(alarms);
        assertEquals(alarms.size(), calls.get(), "TimeRange has to get the tick of an element once, when it is added");
        assertEquals(alarms.subList(0, 500), range.extractFiredElements(T0.plusMillis(500)), "TimeRange has to fire the first half of the elements");
        assertEquals(alarms.subList(500, 1000), range.extractFiredElements(T0.plusSeconds(1)), "TimeRange has to fire the second half of the elements");
        assertEquals(alarms.size(), calls.get(), "TimeRange has to get no tick on an extraction");
    }

    @Test
    void intervalNormalizationTest() {
        // not positive, below a millisecond and longer than the duration
        for (Duration interval : Arrays.asList(Duration.ZERO, Duration.ofSeconds(-15), Duration.ofNanos(500_000), Duration.ofHours(10))) {
            ITimeRange<Instant, Instant> range = ITimeRangeFactory.<Instant>temporal(
                    Duration.ofMinutes(1), interval, Duration.ZERO, null).timeRange(T0);
            assertEquals(Collections.emptyList(), range.addElements(Arrays.asList(T0.plusSeconds(1), T0.plusSeconds(59))),
                    () -> "TimeRange with the interval " + interval + " has to accept the elements of its range");
            assertEquals(Collections.singletonList(T0.plusSeconds(1)), range.extractFiredElements(T0.plusSeconds(2)),
                    () -> "TimeRange with the interval " + interval + " has to fire its first element");
            assertEquals(Collections.singletonList(T0.plusSeconds(59)), range.extractFiredElements(T0.plusSeconds(60)),
                    () -> "TimeRange with the interval " + interval + " has to fire its second element");
        }
    }

    @Test
    void addElementsFailureTest() {
        ITimeRange<TemporalAccessor, TemporalAccessor> range = ITimeRangeFactory.<TemporalAccessor>temporal(
                Duration.ofMinutes(1), Duration.ofSeconds(1), Duration.ZERO, null).timeRange(T0);
        // an element of the range, then a LocalDateTime, whose tick is no instant
        List<TemporalAccessor> batch = Arrays.asList(T0.plusSeconds(1), LocalDateTime.of(2030, 1, 1, 0, 0, 2));
        assertThrows(DateTimeException.class, () -> range.addElements(batch), "TimeRange has to fail on an element whose tick is no instant");
        assertEquals(Collections.emptyList(), range.extractFiredElements(T0.plusSeconds(60)), "TimeRange has to add no element of a call that fails");
        assertTrue(range.isComplete(T0.plusSeconds(60)), "TimeRange has to stay empty after a call that fails");
    }

    @Test
    void zeroDurationTest() {
        ITimeRange<Instant, Instant> range = ITimeRangeFactory.<Instant>temporal(
                Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1), null).timeRange(T0);
        assertEquals(range.getStartInstant(), range.getLastInstant(), "TimeRange of a zero duration has to start where it ends");
        assertEquals(Collections.singletonList(T0), new ArrayList<>(range.addElements(Collections.singletonList(T0))),
                "TimeRange of a zero duration has to reject every element");
        assertEquals(Collections.emptyList(), range.extractFiredElements(T0.plusSeconds(1)), "TimeRange of a zero duration has nothing to fire");
        assertFalse(range.isComplete(T0), "TimeRange of a zero duration hasn't got to be complete before its completeTimeout");
        assertTrue(range.isComplete(T0.plusSeconds(1)), "TimeRange of a zero duration has to be complete after its completeTimeout");
    }

    @Test
    void durationWithDelayTest() {
        // an element 10 s from now
        Instant instant = Instant.now().plusSeconds(10);
        ITimeRange<Instant, Instant> range = ITimeRangeFactory.<Instant>temporal(
                Duration.ofMinutes(1), Duration.ofSeconds(1), Duration.ZERO, null).timeRange(instant.minusSeconds(30));
        range.addElement(instant);
        Duration duration = range.duration(Duration.ofSeconds(4));
        assertTrue(duration.compareTo(Duration.ofSeconds(5)) > 0 && duration.compareTo(Duration.ofSeconds(6)) <= 0,
                () -> "TimeRange has to give the time from now plus the delay to its element, about 6 s: " + duration);
        assertEquals(Duration.ZERO, range.duration(Duration.ofSeconds(11)), "TimeRange has to give zero for an element due within the delay");
    }

    @Test
    void cleanNearestTest() {
        // a range of an hour, which starts an hour after T0
        ITimeRange<Instant, Instant> range = ITimeRangeFactory.<Instant>temporal(
                Duration.ofHours(1), Duration.ofMinutes(1), Duration.ZERO, null).timeRange(T0.plusSeconds(3600));
        range.addElement(T0.plusSeconds(5400));
        assertEquals(Duration.ofMinutes(90), range.duration(T0), "TimeRange has to wait for its element");
        range.clean();
        assertEquals(Duration.ofHours(1), range.duration(T0), "TimeRange has to wait for its start after clean()");
    }

    // the reminders of the test, extracted in the middle of their chunk and after it
    private static List<Reminder> extractInTheMiddleOfChunk(Comparator<? super Reminder> comparator) {
        ITimeRange<Reminder, Reminder> range = ITimeRangeFactory.<Reminder>expected(
                Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ZERO, comparator).timeRange(T0);
        range.addElements(reminders());
        List<Reminder> fired = new ArrayList<>(range.extractFiredElements(T0.plusSeconds(15)));
        fired.addAll(range.extractFiredElements(T0.plusSeconds(120)));
        return fired;
    }

    void addElements() {
        timeRange.addElements(elements);
    }

    @BeforeEach
    void startUp() {
        this.now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        this.completeTimeout = Duration.ofSeconds(10);
        ITimeRangeFactory<ExpectedTest,ExpectedTest> timeRangeFactory = ITimeRangeFactory.expected(Duration.ofMinutes(-1L), INTERVAL, COMPLETE_TIMEOUT, null);
        this.timeRange = timeRangeFactory.timeRange(now);
        this.elementA = expected(now.minusSeconds(40));
        this.elementB = expected(now.minusSeconds(5));
        this.elementC = expected(now.minusSeconds(4));
        elements = Arrays.asList(elementA, elementB, elementC);
    }

    @AfterEach
    void tearDown() {
        this.now = null;
        this.completeTimeout = null;
        this.timeRange = null;
        this.elementA = null;
        this.elementB = null;
        this.elements = null;
    }

    static class ExpectedTest implements Expected<Instant>, Comparable<ExpectedTest> {
        private final Instant tick;

        ExpectedTest(Instant tick) {
            this.tick = tick;
        }

        @Override
        public Instant getTick() {
            return tick;
        }

        @Override
        public int compareTo(ExpectedTest expectedTest) {
            return tick.compareTo(expectedTest.getTick());
        }
    }

    static ExpectedTest expected(Instant instant) {
        return new ExpectedTest(instant);
    }

    // an element that equals another one of the same id, whatever their ticks
    static final class Reminder implements Expected<Instant> {
        private final String id;
        private final Instant tick;

        Reminder(String id, Instant tick) {
            this.id = id;
            this.tick = tick;
        }

        @Override
        public Instant getTick() {
            return tick;
        }

        String getId() {
            return id;
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof Reminder && ((Reminder) object).id.equals(id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    // four reminders of one id in the first chunk of a minute from T0
    static List<Reminder> reminders() {
        return Arrays.asList(new Reminder("order-1", T0.plusSeconds(10)), new Reminder("order-1", T0.plusSeconds(12)),
                new Reminder("order-1", T0.plusSeconds(20)), new Reminder("order-1", T0.plusSeconds(30)));
    }

    static List<Instant> ticks(Collection<? extends Expected<Instant>> elements) {
        return elements.stream().map(Expected<Instant>::getTick).collect(Collectors.toList());
    }

    // records the arguments of duration(Instant), the other calls go to the range
    static class Recording<M,R> implements ITimeRange<M,R> {
        private final ITimeRange<M,R> timeRange;
        final List<Instant> durationArguments = new ArrayList<>();

        Recording(ITimeRange<M,R> timeRange) {
            this.timeRange = timeRange;
        }

        @Override
        public Instant getStartInstant() {
            return timeRange.getStartInstant();
        }

        @Override
        public Instant getLastInstant() {
            return timeRange.getLastInstant();
        }

        @Override
        public boolean isExpired(Instant instant) {
            return timeRange.isExpired(instant);
        }

        @Override
        public boolean isComplete(Instant instant) {
            return timeRange.isComplete(instant);
        }

        @Override
        public Collection<M> addElements(Collection<M> elements) {
            return timeRange.addElements(elements);
        }

        @Override
        public Collection<R> extractFiredElements(Instant instant) {
            return timeRange.extractFiredElements(instant);
        }

        @Override
        public void clean() {
            timeRange.clean();
        }

        @Override
        public Duration duration(Instant now) {
            durationArguments.add(now);
            return timeRange.duration(now);
        }
    }

}