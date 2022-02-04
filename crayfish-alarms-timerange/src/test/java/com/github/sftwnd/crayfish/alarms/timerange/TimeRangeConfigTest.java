package com.github.sftwnd.crayfish.alarms.timerange;

import com.github.sftwnd.crayfish.common.expectation.Expectation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TimeRangeConfigTest {

    private static final Duration DURATION = Duration.ofMinutes(1);
    private static final Duration INTERVAL = Duration.ofSeconds(1);
    private static final Duration DELAY = Duration.ofMillis(125);
    private static final Duration COMPLETE_TIMEOUT = Duration.ofSeconds(5);

    private static final Expectation<Instant,Instant> EXPECTATION = instant -> instant;
    private static final Comparator<Instant> COMPARATOR = Instant::compareTo;
    private static final TimeRangeHolder.ResultTransformer<Instant,Instant> EXTRACTOR = instant -> instant;

    private static TimeRangeConfig<Instant, Instant> config;
    @Test
    void getDurationTest() {
        assertEquals(DURATION, config.getDuration(), "duration has wrong value");
    }

    @Test
    void getIntervalTest() {
        assertEquals(INTERVAL, config.getInterval(), "interval has wrong value");
    }

    @Test
    void getDelayTest() {
        assertEquals(DELAY, config.getDelay(), "delay has wrong value");
    }

    @Test
    void getCompleteTimeoutTest() {
        assertEquals(COMPLETE_TIMEOUT, config.getCompleteTimeout(), "completeTimeout has wrong value");
    }

    @Test
    void getExpectationTest() {
        Instant instant = Instant.now().plus(COMPLETE_TIMEOUT).minusSeconds(17);
        assertSame(EXPECTATION, config.getExpectation(), "getExpectation has return wrong value");
        assertEquals(instant, config.getExpectation().apply(instant), "expectation has produce wrong value");
    }

    @Test
    void getExtractorTest() {
        Instant instant = Instant.now().plus(DURATION).minusSeconds(13);
        assertSame(EXTRACTOR.apply(instant), config.getExtractor().apply(instant), "getExtractor has return function with same result");
        assertEquals(instant, config.getExtractor().apply(instant), "extractor has produce wrong value");
    }

    @Test
    void getComparatorTest() {
        Instant obj1 = Instant.now().plus(DELAY).minus(INTERVAL);
        Instant obj2 = Instant.from(obj1);
        assertSame(COMPARATOR, COMPARATOR, "getEComparator has return wrong value");
        assertEquals(0, config.getComparator().compare(obj1, obj2), "comparator has produce wrong value");
    }

    @BeforeAll
    static void startUp() {
        config = TimeRangeConfig.create(DURATION, INTERVAL, DELAY, COMPLETE_TIMEOUT, EXPECTATION, COMPARATOR);
    }

    @AfterAll
    static void tearDown() {
        config = null;
    }

}