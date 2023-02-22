package com.github.sftwnd.crayfish.alarms.timerange;

import com.github.sftwnd.crayfish.common.expectation.Expectation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class TimeRangeFactoryConfigTest {

    private static final Duration DURATION = Duration.ofMinutes(1);
    private static final Duration INTERVAL = Duration.ofSeconds(1);
    private static final Duration DELAY = Duration.ofMillis(125);
    private static final Duration COMPLETE_TIMEOUT = Duration.ofSeconds(5);

    private static final Expectation<Instant,Instant> EXPECTATION = instant -> instant;
    private static final Comparator<Instant> COMPARATOR = Instant::compareTo;
    private static final ITimeRange.Transformer<Instant,Instant> REDUCER = instant -> instant;

    private static ITimeRangeConfig<Instant, Instant, Instant> config;
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
    void getReducerTest() {
        Instant instant = Instant.now().plus(DURATION).minusSeconds(13);
        assertSame(REDUCER.apply(instant), config.getReducer().apply(instant), "getReducer has return function with same result");
        assertEquals(instant, config.getReducer().apply(instant), "reducer has produce wrong value");
    }

    @Test
    void getComparatorTest() {
        Instant obj1 = Instant.now().plus(DELAY).minus(INTERVAL);
        Instant obj2 = Instant.from(obj1);
        assertSame(COMPARATOR, COMPARATOR, "getEComparator has return wrong value");
        assertNotNull(config.getComparator(), "comparator has to be non-null");
        assertEquals(0, config.getComparator().compare(obj1, obj2), "comparator has produce wrong value");
    }

    @Test
    void fromConfig() {
        ITimeRangeConfig<?,?,?> config = TimeRangeFactoryConfigTest.config.immutable();
        assertNotNull(config, "immutable() result has to be not null");
        assertEquals(ImmutableTimeRangeConfig.class, config.getClass(), "Config class has to be ImmutableTimeRangeConfig");
    }
    @Test
    void fromImmutableConfig() {
        ITimeRangeConfig<?,?,?> immutableConfig = TimeRangeFactoryConfigTest.config.immutable();
        assertNotNull(config, "immutable() result has to be not null");
        assertSame(immutableConfig, immutableConfig.immutable(), "immutable() for ImmutableTimeRangeConfig has o return same object");
    }

    @BeforeAll
    static void startUp() {
        config = new ITimeRangeConfig<>() {
            @Nonnull @Override public Duration getDuration() { return DURATION;  }
            @Nonnull @Override public Duration getInterval() { return INTERVAL; }
            @Nonnull @Override public Duration getDelay() { return DELAY; }
            @Nonnull @Override public Duration getCompleteTimeout() { return COMPLETE_TIMEOUT; }
            @Nonnull @Override public ITimeRange.Transformer<Instant, Instant> getPreserver() { return ITimeRange.Transformer.identity(); }
            @Nonnull @Override public Expectation<Instant, ? extends TemporalAccessor> getExpectation() { return EXPECTATION; }
            @Nonnull @Override public ITimeRange.Transformer<Instant, Instant> getReducer() { return ITimeRange.Transformer.identity(); }
            @Nullable @Override public Comparator<? super Instant> getComparator() { return COMPARATOR; }
        };
    }

    @AfterAll
    static void tearDown() {
        config = null;
    }

}