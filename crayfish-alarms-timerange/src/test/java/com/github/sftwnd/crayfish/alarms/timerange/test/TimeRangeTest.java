package com.github.sftwnd.crayfish.alarms.timerange.test;

import com.github.sftwnd.crayfish.alarms.timerange.ITimeRange;
import com.github.sftwnd.crayfish.alarms.timerange.ITimeRangeFactory;
import com.github.sftwnd.crayfish.common.expectation.Expected;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;

class TimeRangeTest {

    private static final Duration INTERVAL = Duration.ofSeconds(15);
    private static final Duration COMPLETE_TIMEOUT = Duration.ofSeconds(10);

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
        assertEquals(List.of(timeRange.getStartInstant()), fired, "TimeRange has to return added instant");
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
        assertEquals(List.of(rejected), rejects,"result for timeRange.addElements({ null, element }) has to return one not null element");
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
        List<ExpectedTest> exclude = List.of(
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
        assertEquals(Set.of(elementA), new HashSet<>(elements), "TimeRange has to return first element");
    }

    @Test
    void addSkipFirstSecondFiredInstantElementTest() {
        addElements();
        this.timeRange.extractFiredElements(elementA.getTick());
        Set<ExpectedTest> elements = new HashSet<>(this.timeRange.extractFiredElements(elementB.getTick()));
        assertEquals(Set.of(elementB), elements, "TimeRange has to return second element");
    }

    @Test
    void addSkipFirstLastFiredInstantElementsTest() {
        addElements();
        this.timeRange.extractFiredElements(elementA.getTick());
        Set<ExpectedTest> elements = new HashSet<>(this.timeRange.extractFiredElements(now));
        assertEquals(Set.of(elementB,elementC), elements, "TimeRange has to return second and third element");
    }

    @Test
    void addDupElementTest() {
        this.timeRange.addElements(List.of(elementA, elementC));
        this.timeRange.addElements(List.of(elementA, elementB));
        Set<ExpectedTest> elements = new HashSet<>(this.timeRange.extractFiredElements());
        assertEquals(new HashSet<>(this.elements), elements, "TimeRange has to return second element");
    }

    @Test
    void addSameInstantElementsTest() {
        Instant instant = this.timeRange.getStartInstant().plusMillis(1);
        this.timeRange.addElements(List.of(expected(instant), expected(instant), expected(instant)));
        assertEquals(3, this.timeRange.extractFiredElements().size(), "TimeRange has to return three elements on the same instant");
    }

    @Test
    void addListWithDupElementTest() {
        this.timeRange.addElements(List.of(elementA, elementC, elementA, elementB, elementC, elementB));
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
        this.timeRange.addElements(List.of(expected(tick.plusMillis(100))));
        assertEquals(Duration.ofMillis(100), this.timeRange.duration(tick),
                "Duration on the end of timeRange has to be equals duration to the end plus completion");
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
    void getIntervalTest() {
        assertEquals(INTERVAL, Duration.ofSeconds(15),"TimeRange::getInterval - wrong result");
    }

    @Test
    void getCompleteTimeoutTest() {
        assertEquals(COMPLETE_TIMEOUT, this.completeTimeout, "TimeRange::getCompleteTimeout - wrong result");
    }

    @Test
    void durationZeroTest() {
        ITimeRange<ExpectedTest,ExpectedTest> mock = Mockito.spy(this.timeRange);
        Instant firstTick = Instant.now().minusNanos(1);
        ArgumentCaptor<Instant> argument = ArgumentCaptor.forClass(Instant.class);
        mock.duration();
        Instant nextTick = Instant.now().plusNanos(1);
        Mockito.verify(mock, times(1)).duration(argument.capture());
        Instant instant = argument.getValue();
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
        Collection<String> rejected = timeRange.addElements(List.of(strA, strB, strC, strD, strE));
        assertEquals(List.of(strE), new ArrayList<>(rejected), "timeRange.addElements has to reject strD");
        assertEquals( Stream.of(strA,strB,strC).sorted().collect(Collectors.toList()),
                timeRange.extractFiredElements(now.minusMillis(2)).stream().sorted().collect(Collectors.toList()),
                "constructPackable has to return three elements on now");
        assertEquals(List.of(strD), timeRange.extractFiredElements(now.plusMillis(1)), "constructPackable has to return one element");
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
        elements = List.of(elementA, elementB, elementC);
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

    @AllArgsConstructor
    static class ExpectedTest implements Expected<Instant>, Comparable<ExpectedTest> {
        @Getter
        private final Instant tick;

        @Override
        public int compareTo(ExpectedTest expectedTest) {
            return tick.compareTo(expectedTest.getTick());
        }
    }

    static ExpectedTest expected(Instant instant) {
        return new ExpectedTest(instant);
    }

}